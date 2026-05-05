package dev.vmonot

import com.intellij.icons.AllIcons
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.ActionGroup
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.editor.colors.EditorColorsManager
import com.intellij.openapi.editor.event.DocumentEvent
import com.intellij.openapi.editor.event.DocumentListener
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileEditor
import com.intellij.openapi.fileEditor.FileEditorPolicy
import com.intellij.openapi.fileEditor.FileEditorProvider
import com.intellij.openapi.fileEditor.FileEditorState
import com.intellij.openapi.fileEditor.TextEditor
import com.intellij.openapi.fileEditor.TextEditorWithPreview
import com.intellij.openapi.fileEditor.impl.text.TextEditorProvider
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.util.IconLoader
import com.intellij.openapi.util.UserDataHolderBase
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBPanel
import com.intellij.ui.jcef.JBCefApp
import com.intellij.ui.jcef.JBCefBrowser
import java.awt.BorderLayout
import java.beans.PropertyChangeListener
import java.nio.charset.StandardCharsets
import javax.swing.JComponent
import javax.swing.Icon
import javax.swing.Timer

private const val SPECIFICATION_MARKER_READ_LIMIT = 64 * 1024
private val YAML_SPECIFICATION_MARKER = Regex("""(?m)^\s*(openapi|swagger)\s*:""")
private val JSON_SPECIFICATION_MARKER = Regex(""""(openapi|swagger)"\s*:""")

class OpenApiPreviewEditorProvider : FileEditorProvider, DumbAware {
    override fun accept(project: Project, file: VirtualFile): Boolean {
        return acceptsFile(file)
    }

    override fun createEditor(project: Project, file: VirtualFile): FileEditor {
        val textEditor = TextEditorProvider.getInstance().createEditor(project, file) as TextEditor
        val previewEditor = OpenApiPreviewBrowser(project, file, textEditor)

        return OpenApiEditorWithPreview(
            textEditor,
            previewEditor,
        )
    }

    override fun getEditorTypeId(): String = EDITOR_TYPE_ID

    override fun getPolicy(): FileEditorPolicy = FileEditorPolicy.HIDE_OTHER_EDITORS

    companion object {
        const val EDITOR_TYPE_ID = "scalar-openapi-preview"

        fun acceptsFile(file: VirtualFile): Boolean {
            val extension = file.extension?.lowercase()
            if (extension !in setOf("yaml", "yml", "json")) {
                return false
            }

            return hasOpenApiSpecificationMarker(file)
        }
    }
}

private class OpenApiEditorWithPreview(
    textEditor: TextEditor,
    private val openApiPreviewEditor: OpenApiPreviewBrowser,
) : TextEditorWithPreview(
    textEditor,
    openApiPreviewEditor,
    "OpenAPI Preview",
    Layout.SHOW_EDITOR_AND_PREVIEW,
) {
    override fun createRightToolbarActionGroup(): ActionGroup {
        return DefaultActionGroup(
            listOf(
                ReloadPreviewAction(openApiPreviewEditor),
                SwitchPreviewRendererAction(openApiPreviewEditor),
            ),
        )
    }
}

private fun hasOpenApiSpecificationMarker(file: VirtualFile): Boolean =
    runCatching {
        file.inputStream.reader(StandardCharsets.UTF_8).use { reader ->
            val buffer = CharArray(SPECIFICATION_MARKER_READ_LIMIT)
            val length = reader.read(buffer)
            if (length <= 0) {
                false
            } else {
                val text = String(buffer, 0, length)
                YAML_SPECIFICATION_MARKER.containsMatchIn(text) || JSON_SPECIFICATION_MARKER.containsMatchIn(text)
            }
        }
    }.getOrDefault(false)

private class OpenApiPreviewBrowser(
    private val project: Project,
    private val file: VirtualFile,
    private val textEditor: TextEditor,
) : UserDataHolderBase(), FileEditor, Disposable {
    private val log = logger<OpenApiPreviewBrowser>()
    private val component = JBPanel<JBPanel<*>>(BorderLayout())
    private val previewContainer = JBPanel<JBPanel<*>>(BorderLayout())
    private val reloadTimer = Timer(350) { reloadPreview() }.apply {
        isRepeats = false
    }

    private var scalarBrowser: JBCefBrowser? = null
    private var officialPreview: FileEditor? = null
    private var officialPreviewClassLoader: ClassLoader? = null
    private var selectedRenderer = firstAvailableRenderer()
    private var disposed = false

    init {
        component.add(previewContainer, BorderLayout.CENTER)

        if (JBCefApp.isSupported()) {
            showSelectedRenderer()
        } else {
            showUnsupportedJcefMessage()
        }

        FileDocumentManager.getInstance().getDocument(file)?.addDocumentListener(object : DocumentListener {
            override fun documentChanged(event: DocumentEvent) {
                scheduleReload()
            }
        }, this)
    }

    private fun scheduleReload() {
        if (!disposed) {
            reloadTimer.restart()
        }
    }

    fun reloadPreview() {
        if (disposed) {
            return
        }

        ensureSelectedRendererIsAvailable()

        when (selectedRenderer) {
            PreviewRenderer.SCALAR -> loadScalarPreview()
            PreviewRenderer.REDOC, PreviewRenderer.SWAGGER_UI -> reloadOfficialPreview()
        }
    }

    fun nextRenderer(): PreviewRenderer {
        val renderers = availableRenderers()
        val currentIndex = renderers.indexOf(selectedRenderer).takeIf { it >= 0 } ?: 0

        return renderers[(currentIndex + 1) % renderers.size]
    }

    fun canSwitchRenderer(): Boolean = availableRenderers().size > 1

    fun switchToNextRenderer() {
        selectedRenderer = nextRenderer()
        showSelectedRenderer()
    }

    private fun showSelectedRenderer() {
        if (disposed) {
            return
        }

        if (!JBCefApp.isSupported()) {
            showUnsupportedJcefMessage()
            return
        }

        ensureSelectedRendererIsAvailable()

        previewContainer.removeAll()

        when (selectedRenderer) {
            PreviewRenderer.SCALAR -> showScalarPreview()
            PreviewRenderer.REDOC, PreviewRenderer.SWAGGER_UI -> showOfficialPreview(selectedRenderer)
        }

        previewContainer.revalidate()
        previewContainer.repaint()
    }

    private fun showScalarPreview() {
        disposeOfficialPreview()
        disposeScalarBrowser()

        val currentBrowser = JBCefBrowser().also {
            scalarBrowser = it
            previewContainer.add(it.component, BorderLayout.CENTER)
        }

        loadScalarPreview(currentBrowser)
    }

    private fun showOfficialPreview(renderer: PreviewRenderer) {
        disposeScalarBrowser()
        disposeOfficialPreview()

        if (!OfficialSwaggerPreviewBridge.isAvailable()) {
            selectedRenderer = firstAvailableRenderer()
            showScalarPreview()
            return
        }

        runCatching {
            when (renderer) {
                PreviewRenderer.REDOC -> OfficialSwaggerPreviewBridge.createRedoc(file, textEditor, project)
                PreviewRenderer.SWAGGER_UI -> OfficialSwaggerPreviewBridge.createSwaggerUi(file, textEditor, project)
                PreviewRenderer.SCALAR -> null
            }
        }.onSuccess { preview ->
            if (preview == null) {
                previewContainer.add(JBLabel("${renderer.presentableName} is not available."), BorderLayout.CENTER)
            } else {
                officialPreview = preview
                officialPreviewClassLoader = OfficialSwaggerPreviewBridge.currentPluginClassLoader()
                previewContainer.add(preview.component, BorderLayout.CENTER)
            }
        }.onFailure { error ->
            log.warn("Failed to create ${renderer.presentableName} preview for ${file.path}", error)
            previewContainer.add(
                JBLabel("${renderer.presentableName} preview failed to load: ${error.message ?: "Unknown error"}"),
                BorderLayout.CENTER,
            )
        }
    }

    private fun reloadOfficialPreview() {
        val currentPreview = officialPreview
        if (currentPreview == null) {
            showSelectedRenderer()
            return
        }
        if (officialPreviewClassLoader !== OfficialSwaggerPreviewBridge.currentPluginClassLoader()) {
            showSelectedRenderer()
            return
        }

        val reloaded = runCatching {
            OfficialSwaggerPreviewBridge.reload(currentPreview, file)
        }.onFailure { error ->
            log.warn("Failed to reload ${selectedRenderer.presentableName} preview for ${file.path}", error)
        }.getOrDefault(false)

        if (!reloaded) {
            showSelectedRenderer()
        }
    }

    private fun loadScalarPreview(currentBrowser: JBCefBrowser? = scalarBrowser) {
        val browser = currentBrowser ?: return
        if (disposed) {
            return
        }

        val html = runCatching {
            val specification = readSpecification()
            renderScalarPreviewHtml(file.name, specification, file.extension, isDarkEditorTheme())
        }.getOrElse { error ->
            log.warn("Failed to render OpenAPI preview for ${file.path}", error)
            renderErrorHtml(
                "Unable to load ${file.name}",
                error.message ?: "Unknown error",
                isDarkEditorTheme(),
            )
        }

        ApplicationManager.getApplication().invokeLater {
            if (!disposed) {
                browser.loadHTML(html, "https://scalar-openapi-preview.local/${file.name}")
            }
        }
    }

    private fun readSpecification(): String {
        val document = FileDocumentManager.getInstance().getDocument(file)
        return document?.text ?: VfsUtilCore.loadText(file)
    }

    override fun getComponent(): JComponent = component

    override fun getPreferredFocusedComponent(): JComponent =
        officialPreview?.preferredFocusedComponent ?: scalarBrowser?.component ?: component

    override fun getName(): String = "OpenAPI Preview"

    override fun setState(state: FileEditorState) = Unit

    override fun isModified(): Boolean = false

    override fun isValid(): Boolean = !disposed && file.isValid

    override fun addPropertyChangeListener(listener: PropertyChangeListener) = Unit

    override fun removePropertyChangeListener(listener: PropertyChangeListener) = Unit

    override fun getFile(): VirtualFile = file

    override fun dispose() {
        disposed = true
        reloadTimer.stop()
        disposeScalarBrowser()
        disposeOfficialPreview()
    }

    private fun showUnsupportedJcefMessage() {
        previewContainer.removeAll()
        previewContainer.add(
            JBLabel("OpenAPI preview requires JCEF, but JCEF is not available in this IDE runtime."),
            BorderLayout.CENTER,
        )
        previewContainer.revalidate()
        previewContainer.repaint()
    }

    private fun disposeScalarBrowser() {
        scalarBrowser?.let {
            Disposer.dispose(it)
        }
        scalarBrowser = null
    }

    private fun disposeOfficialPreview() {
        officialPreview?.let { preview ->
            runCatching {
                preview.dispose()
            }.onFailure { error ->
                log.warn("Failed to dispose ${selectedRenderer.presentableName} preview for ${file.path}", error)
            }
        }
        officialPreview = null
        officialPreviewClassLoader = null
    }

    private fun ensureSelectedRendererIsAvailable() {
        if (selectedRenderer !in availableRenderers()) {
            selectedRenderer = firstAvailableRenderer()
        }
    }

    private fun firstAvailableRenderer(): PreviewRenderer {
        return availableRenderers().firstOrNull() ?: PreviewRenderer.SCALAR
    }

    private fun availableRenderers(): List<PreviewRenderer> {
        val availableRenderers = if (OfficialSwaggerPreviewBridge.isAvailable()) {
            PreviewRenderer.entries.toSet()
        } else {
            setOf(PreviewRenderer.SCALAR)
        }

        return OpenApiPreviewSettings.instance.rendererOrder().filter { it in availableRenderers }
    }
}

private fun isDarkEditorTheme(): Boolean = EditorColorsManager.getInstance().isDarkEditor

private class ReloadPreviewAction(
    private val previewEditor: OpenApiPreviewBrowser,
) : AnAction("Reload Preview", "Reload OpenAPI preview", AllIcons.Actions.Refresh) {
    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT

    override fun actionPerformed(event: AnActionEvent) {
        previewEditor.reloadPreview()
    }
}

private class SwitchPreviewRendererAction(
    private val previewEditor: OpenApiPreviewBrowser,
) : AnAction() {
    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT

    override fun update(event: AnActionEvent) {
        val nextRenderer = previewEditor.nextRenderer()

        event.presentation.isEnabledAndVisible = previewEditor.canSwitchRenderer()
        event.presentation.text = "Switch to ${nextRenderer.presentableName}"
        event.presentation.description = "Switch OpenAPI preview to ${nextRenderer.presentableName}"
        event.presentation.icon = nextRenderer.icon
    }

    override fun actionPerformed(event: AnActionEvent) {
        previewEditor.switchToNextRenderer()
    }
}

internal enum class PreviewRenderer(
    val presentableName: String,
    val icon: Icon,
) {
    SCALAR("Scalar", PreviewIcons.scalar),
    REDOC("Redoc", PreviewIcons.redoc),
    SWAGGER_UI("Swagger UI", PreviewIcons.swaggerUi);

    override fun toString(): String = presentableName
}

private object PreviewIcons {
    val scalar: Icon = IconLoader.getIcon("/icons/scalar.svg", PreviewIcons::class.java)
    val redoc: Icon = IconLoader.getIcon("/icons/redoc.svg", PreviewIcons::class.java)
    val swaggerUi: Icon = IconLoader.getIcon("/icons/swagger-ui.svg", PreviewIcons::class.java)
}

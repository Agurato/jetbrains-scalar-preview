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
import java.util.Base64
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
        val previewEditor = OpenApiPreviewBrowser(file)

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
    private val file: VirtualFile,
) : UserDataHolderBase(), FileEditor, Disposable {
    private val log = logger<OpenApiPreviewBrowser>()
    private val component = JBPanel<JBPanel<*>>(BorderLayout())
    private val previewContainer = JBPanel<JBPanel<*>>(BorderLayout())
    private val reloadTimer = Timer(350) { loadPreview() }.apply {
        isRepeats = false
    }

    private var browser: JBCefBrowser? = null
    private var selectedRenderer = PreviewRenderer.SCALAR
    private var disposed = false

    init {
        component.add(previewContainer, BorderLayout.CENTER)

        if (JBCefApp.isSupported()) {
            browser = JBCefBrowser().also {
                previewContainer.add(it.component, BorderLayout.CENTER)
            }
            loadPreview()
        } else {
            previewContainer.add(
                JBLabel("OpenAPI preview requires JCEF, but JCEF is not available in this IDE runtime."),
                BorderLayout.CENTER,
            )
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
        loadPreview()
    }

    fun nextRenderer(): PreviewRenderer = selectedRenderer.next()

    fun switchToNextRenderer() {
        selectedRenderer = nextRenderer()
        loadPreview()
    }

    private fun loadPreview() {
        val currentBrowser = browser ?: return
        if (disposed) {
            return
        }

        val html = runCatching {
            val specification = readSpecification()
            renderPreviewHtml(selectedRenderer, file.name, specification, file.extension, isDarkEditorTheme())
        }.getOrElse { error ->
            log.warn("Failed to render OpenAPI preview for ${file.path}", error)
            renderErrorHtml(
                "Unable to load ${escapeHtml(file.name)}",
                escapeHtml(error.message ?: "Unknown error"),
                isDarkEditorTheme(),
            )
        }

        ApplicationManager.getApplication().invokeLater {
            if (!disposed) {
                currentBrowser.loadHTML(html, "https://scalar-openapi-preview.local/${file.name}")
            }
        }
    }

    private fun readSpecification(): String {
        val document = FileDocumentManager.getInstance().getDocument(file)
        return document?.text ?: VfsUtilCore.loadText(file)
    }

    override fun getComponent(): JComponent = component

    override fun getPreferredFocusedComponent(): JComponent = browser?.component ?: component

    override fun getName(): String = "Scalar"

    override fun setState(state: FileEditorState) = Unit

    override fun isModified(): Boolean = false

    override fun isValid(): Boolean = !disposed && file.isValid

    override fun addPropertyChangeListener(listener: PropertyChangeListener) = Unit

    override fun removePropertyChangeListener(listener: PropertyChangeListener) = Unit

    override fun getFile(): VirtualFile = file

    override fun dispose() {
        disposed = true
        reloadTimer.stop()
        browser?.let {
            Disposer.dispose(it)
        }
        browser = null
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

        event.presentation.text = "Switch to ${nextRenderer.presentableName}"
        event.presentation.description = "Switch OpenAPI preview to ${nextRenderer.presentableName}"
        event.presentation.icon = nextRenderer.icon
    }

    override fun actionPerformed(event: AnActionEvent) {
        previewEditor.switchToNextRenderer()
    }
}

private enum class PreviewRenderer(
    val presentableName: String,
    val icon: Icon,
) {
    SCALAR("Scalar", PreviewIcons.scalar),
    REDOC("Redoc", PreviewIcons.redoc),
    SWAGGER_UI("Swagger UI", PreviewIcons.swaggerUi);

    fun next(): PreviewRenderer {
        val renderers = entries
        return renderers[(ordinal + 1) % renderers.size]
    }

    override fun toString(): String = presentableName
}

private object PreviewIcons {
    val scalar: Icon = IconLoader.getIcon("/icons/scalar.svg", PreviewIcons::class.java)
    val redoc: Icon = IconLoader.getIcon("/icons/redoc.svg", PreviewIcons::class.java)
    val swaggerUi: Icon = IconLoader.getIcon("/icons/swagger-ui.svg", PreviewIcons::class.java)
}

private fun renderPreviewHtml(
    renderer: PreviewRenderer,
    fileName: String,
    specification: String,
    extension: String?,
    darkTheme: Boolean,
): String {
    val encodedSpecification = Base64.getEncoder().encodeToString(specification.toByteArray(StandardCharsets.UTF_8))
    val mimeType = when (extension?.lowercase()) {
        "json" -> "application/json"
        else -> "application/yaml"
    }

    return when (renderer) {
        PreviewRenderer.SCALAR -> renderScalarHtml(fileName, encodedSpecification, mimeType, darkTheme)
        PreviewRenderer.REDOC -> renderRedocHtml(fileName, encodedSpecification, mimeType, darkTheme)
        PreviewRenderer.SWAGGER_UI -> renderSwaggerUiHtml(fileName, encodedSpecification, mimeType, darkTheme)
    }
}

private fun renderScalarHtml(
    fileName: String,
    encodedSpecification: String,
    mimeType: String,
    darkTheme: Boolean,
): String = """
    <!doctype html>
    <html>
      <head>
        ${commonHead(fileName, darkTheme)}
        <script src="https://cdn.jsdelivr.net/npm/@scalar/api-reference"></script>
      </head>
      <body>
        <div id="app"></div>
        ${commonPreviewScript(encodedSpecification, mimeType)}
        <script>
          try {
            Scalar.createApiReference('#app', {
              content: specContent,
              darkMode: $darkTheme,
              forceDarkModeState: '${if (darkTheme) "dark" else "light"}',
              documentDownloadType: 'both',
              defaultHttpClient: {
                targetKey: 'shell',
                clientKey: 'curl'
              },
              agent: {
                disabled: true
              }
            })
          } catch (error) {
            showPreviewError(error)
          }
        </script>
      </body>
    </html>
""".trimIndent()

private fun renderRedocHtml(
    fileName: String,
    encodedSpecification: String,
    mimeType: String,
    darkTheme: Boolean,
): String = """
    <!doctype html>
    <html>
      <head>
        ${commonHead(fileName, darkTheme)}
        <script>
          window.process = window.process || {}
          window.process.env = window.process.env || {}
          window.process.env.NODE_ENV = window.process.env.NODE_ENV || 'production'
          window.process.cwd = window.process.cwd || function () { return '/' }
        </script>
        <script src="https://cdn.jsdelivr.net/npm/js-yaml@4/dist/js-yaml.min.js"></script>
        <script src="https://cdn.redoc.ly/redoc/latest/bundles/redoc.standalone.js"></script>
      </head>
      <body>
        <div id="app"></div>
        ${commonPreviewScript(encodedSpecification, mimeType)}
        <script>
          ${parseRedocScript()}
        </script>
        <script>
          try {
            const specDocument = parseOpenApiDocumentForRedoc(specContent, '$mimeType')

            Redoc.init(specDocument, {
              hideDownloadButton: false,
              scrollYOffset: 0,
              theme: {
                colors: {
                  tonalOffset: ${if (darkTheme) "0.2" else "0.3"},
                  primary: {
                    main: '${if (darkTheme) "#8ab4f8" else "#1976d2"}'
                  },
                  text: {
                    primary: '${if (darkTheme) "#ced0d6" else "#1f2328"}',
                    secondary: '${if (darkTheme) "#b8babf" else "#4b5563"}'
                  },
                  border: {
                    dark: '${if (darkTheme) "#45484f" else "#d0d7de"}',
                    light: '${if (darkTheme) "#2b2d32" else "#edf2f7"}'
                  },
                  responses: {
                    success: {
                      color: '${if (darkTheme) "#6ccf8d" else "#00aa13"}',
                      backgroundColor: '${if (darkTheme) "#173824" else "#f6fff8"}'
                    },
                    error: {
                      color: '${if (darkTheme) "#ff9b9b" else "#e53935"}',
                      backgroundColor: '${if (darkTheme) "#3a1f1f" else "#fff5f5"}'
                    }
                  },
                  http: {
                    get: '${if (darkTheme) "#8ab4f8" else "#1976d2"}',
                    post: '${if (darkTheme) "#6ccf8d" else "#00aa13"}',
                    put: '${if (darkTheme) "#f9c74f" else "#f57c00"}',
                    delete: '${if (darkTheme) "#ff9b9b" else "#e53935"}'
                  }
                },
                sidebar: {
                  backgroundColor: '${if (darkTheme) "#1e1f22" else "#ffffff"}',
                  textColor: '${if (darkTheme) "#ced0d6" else "#1f2328"}',
                  activeTextColor: '${if (darkTheme) "#8ab4f8" else "#1976d2"}'
                },
                rightPanel: {
                  backgroundColor: '${if (darkTheme) "#25272d" else "#263238"}',
                  textColor: '${if (darkTheme) "#ced0d6" else "#ffffff"}'
                }
              }
            }, document.getElementById('app'))
          } catch (error) {
            showPreviewError(error)
          }
        </script>
      </body>
    </html>
""".trimIndent()

private fun parseRedocScript(): String = """
    function parseOpenApiDocumentForRedoc(content, mimeType) {
      try {
        if (mimeType === 'application/json') {
          return JSON.parse(content)
        }

        if (!window.jsyaml || typeof window.jsyaml.load !== 'function') {
          throw new Error('YAML parser failed to load')
        }

        return window.jsyaml.load(content)
      } catch (error) {
        const message = error && error.message ? error.message : String(error)
        throw new Error('Unable to parse OpenAPI document for Redoc: ' + message)
      }
    }
""".trimIndent()

private fun renderSwaggerUiHtml(
    fileName: String,
    encodedSpecification: String,
    mimeType: String,
    darkTheme: Boolean,
): String = """
    <!doctype html>
    <html>
      <head>
        ${commonHead(fileName, darkTheme)}
        <link rel="stylesheet" href="https://cdn.jsdelivr.net/npm/swagger-ui-dist@5/swagger-ui.css" />
        <style>
          body {
            background: ${if (darkTheme) "#1e1f22" else "#ffffff"};
          }
          #app { height: auto; min-height: 100vh; }
          ${if (darkTheme) swaggerUiDarkStyles() else ""}
        </style>
        <script src="https://cdn.jsdelivr.net/npm/swagger-ui-dist@5/swagger-ui-bundle.js"></script>
        <script src="https://cdn.jsdelivr.net/npm/swagger-ui-dist@5/swagger-ui-standalone-preset.js"></script>
      </head>
      <body>
        <div id="app"></div>
        ${commonPreviewScript(encodedSpecification, mimeType)}
        <script>
          try {
            SwaggerUIBundle({
              url: specUrl,
              dom_id: '#app',
              deepLinking: true,
              presets: [
                SwaggerUIBundle.presets.apis,
                SwaggerUIStandalonePreset
              ],
              layout: 'BaseLayout'
            })
          } catch (error) {
            showPreviewError(error)
          }
        </script>
      </body>
    </html>
""".trimIndent()

private fun commonHead(fileName: String, darkTheme: Boolean): String = """
    <title>${escapeHtml(fileName)}</title>
    <meta charset="utf-8" />
    <meta name="viewport" content="width=device-width, initial-scale=1" />
    <style>
      html,
      body,
      #app {
        width: 100%;
        min-height: 100%;
        margin: 0;
      }

      body {
        font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", sans-serif;
        color: ${if (darkTheme) "#ced0d6" else "#1f2328"};
        background: ${if (darkTheme) "#1e1f22" else "#ffffff"};
      }

      .preview-error {
        box-sizing: border-box;
        max-width: 760px;
        margin: 32px auto;
        padding: 16px;
        border: 1px solid ${if (darkTheme) "#45484f" else "#c9c9c9"};
        border-radius: 6px;
        color: ${if (darkTheme) "#ced0d6" else "#1f2328"};
        background: ${if (darkTheme) "#1e1f22" else "#ffffff"};
      }

      .preview-error h1 {
        margin: 0 0 8px;
        font-size: 18px;
      }

      .preview-error p {
        margin: 0;
        font-size: 13px;
        line-height: 1.45;
      }
    </style>
""".trimIndent()

private fun swaggerUiDarkStyles(): String = """
    .swagger-ui,
    .swagger-ui .info .title,
    .swagger-ui .info li,
    .swagger-ui .info p,
    .swagger-ui .info table,
    .swagger-ui .opblock-tag,
    .swagger-ui .opblock .opblock-summary-description,
    .swagger-ui .opblock .opblock-section-header h4,
    .swagger-ui .opblock-description-wrapper p,
    .swagger-ui .parameter__name,
    .swagger-ui .parameter__type,
    .swagger-ui .response-col_status,
    .swagger-ui table thead tr td,
    .swagger-ui table thead tr th {
      color: #ced0d6;
    }

    .swagger-ui .scheme-container,
    .swagger-ui .opblock .opblock-section-header,
    .swagger-ui .model-box,
    .swagger-ui section.models {
      background: #25272d;
      box-shadow: none;
    }

    .swagger-ui input,
    .swagger-ui select,
    .swagger-ui textarea {
      color: #ced0d6;
      background: #1e1f22;
      border-color: #45484f;
    }
""".trimIndent()

private fun commonPreviewScript(encodedSpecification: String, mimeType: String): String = """
    <script>
      function decodeBase64Utf8(value) {
        const binary = atob(value)
        const bytes = new Uint8Array(binary.length)

        for (let index = 0; index < binary.length; index += 1) {
          bytes[index] = binary.charCodeAt(index)
        }

        return new TextDecoder('utf-8').decode(bytes)
      }

      function escapeHtml(value) {
        return String(value)
          .replace(/&/g, '&amp;')
          .replace(/</g, '&lt;')
          .replace(/>/g, '&gt;')
          .replace(/"/g, '&quot;')
          .replace(/'/g, '&#039;')
      }

      function showPreviewError(error) {
        const app = document.getElementById('app')
        const message = error && error.message ? error.message : String(error)

        app.innerHTML = '<div class="preview-error"><h1>Preview failed to load</h1><p>' + escapeHtml(message) + '</p></div>'
      }

      window.addEventListener('error', (event) => {
        showPreviewError(event.error || event.message)
      })

      const specContent = decodeBase64Utf8('$encodedSpecification')
      const specBlob = new Blob([specContent], { type: '$mimeType' })
      const specUrl = URL.createObjectURL(specBlob)
    </script>
""".trimIndent()

private fun renderErrorHtml(title: String, message: String, darkTheme: Boolean): String = """
    <!doctype html>
    <html>
      <head>
        ${commonHead("OpenAPI Preview Error", darkTheme)}
      </head>
      <body>
        <div id="app">
          <div class="preview-error">
            <h1>$title</h1>
            <p>$message</p>
          </div>
        </div>
      </body>
    </html>
""".trimIndent()

private fun escapeHtml(value: String): String =
    value
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
        .replace("'", "&#039;")

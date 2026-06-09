package dev.vmonot

import com.intellij.openapi.options.Configurable
import com.intellij.ui.ColoredListCellRenderer
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.ToolbarDecorator
import com.intellij.ui.components.ActionLink
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBPanel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTabbedPane
import com.intellij.util.ui.FormBuilder
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import javax.swing.DefaultListModel
import javax.swing.JComponent
import javax.swing.JList
import javax.swing.ListSelectionModel
import javax.swing.ScrollPaneConstants

internal class OpenApiPreviewSettingsConfigurable : Configurable {
    private var settingsPanel: OpenApiPreviewSettingsPanel? = null

    override fun getDisplayName(): String = "Scalar OpenAPI Preview"

    override fun createComponent(): JComponent {
        val panel = OpenApiPreviewSettingsPanel()
        settingsPanel = panel

        return panel.component
    }

    override fun isModified(): Boolean {
        return settingsPanel?.rendererOrder() != OpenApiPreviewSettings.instance.rendererOrder() ||
            settingsPanel?.hiddenScalarClients() != OpenApiPreviewSettings.instance.hiddenScalarClients()
    }

    override fun apply() {
        settingsPanel?.let {
            OpenApiPreviewSettings.instance.setRendererOrder(it.rendererOrder())
            OpenApiPreviewSettings.instance.setHiddenScalarClients(it.hiddenScalarClients())
        }
    }

    override fun reset() {
        settingsPanel?.setRendererOrder(OpenApiPreviewSettings.instance.rendererOrder())
        settingsPanel?.setHiddenScalarClients(OpenApiPreviewSettings.instance.hiddenScalarClients())
    }

    override fun disposeUIResources() {
        settingsPanel = null
    }
}

private class OpenApiPreviewSettingsPanel {
    private val rendererListModel = DefaultListModel<PreviewRenderer>()
    private val rendererList = JBList(rendererListModel).apply {
        cellRenderer = RendererListCellRenderer()
        selectionMode = ListSelectionModel.SINGLE_SELECTION
        visibleRowCount = PreviewRenderer.entries.size
    }
    private val clientCheckboxes = mutableMapOf<String, JBCheckBox>()
    private val languageCheckboxes = mutableMapOf<String, JBCheckBox>()

    val component: JComponent

    init {
        val tabbedPane = JBTabbedPane()

        // General Tab
        val generalPanel = FormBuilder.createFormBuilder()
            .addComponent(JBLabel("OpenAPI preview renderer order:"))
            .addComponent(createRendererOrderPanel())
            .addComponent(JBLabel("The first available renderer is used when opening a preview. The switch action follows this order."))
            .addComponentFillVertically(JBPanel<JBPanel<*>>(), 0)
            .panel.apply {
                border = JBUI.Borders.empty(12)
            }
        tabbedPane.addTab("General", generalPanel)

        // Scalar Tab
        val scalarFormBuilder = FormBuilder.createFormBuilder()
            .addComponent(JBLabel("Select HTTP clients to display in the Scalar preview:"))
            .addVerticalGap(8)

        val actionsPanel = JBPanel<JBPanel<*>>(java.awt.FlowLayout(java.awt.FlowLayout.LEFT, 0, 0))
        actionsPanel.add(ActionLink("Select all") {
            clientCheckboxes.values.forEach { it.isSelected = true }
            languageCheckboxes.values.forEach { it.isSelected = true }
        })
        actionsPanel.add(JBLabel(" | ").apply { border = JBUI.Borders.empty(0, 4) })
        actionsPanel.add(ActionLink("Unselect all") {
            clientCheckboxes.values.forEach { it.isSelected = false }
            languageCheckboxes.values.forEach { it.isSelected = false }
        })
        scalarFormBuilder.addComponent(actionsPanel)
        scalarFormBuilder.addVerticalGap(4)

        SCALAR_CLIENTS.forEach { (language, clients) ->
            val langCheckbox = JBCheckBox(language.replaceFirstChar { it.uppercase() })
            langCheckbox.font = langCheckbox.font.deriveFont(java.awt.Font.BOLD)
            languageCheckboxes[language] = langCheckbox
            
            val langChildCheckboxes = mutableListOf<JBCheckBox>()
            
            langCheckbox.addActionListener {
                val selected = langCheckbox.isSelected
                langChildCheckboxes.forEach { it.isSelected = selected }
            }

            scalarFormBuilder.addVerticalGap(8)
            scalarFormBuilder.addComponent(langCheckbox)

            clients.forEach { client ->
                val checkbox = JBCheckBox(client)
                langChildCheckboxes.add(checkbox)
                val key = "$language:$client"
                clientCheckboxes[key] = checkbox
                
                checkbox.addActionListener {
                    langCheckbox.isSelected = langChildCheckboxes.all { it.isSelected }
                }

                val panel = JBPanel<JBPanel<*>>(BorderLayout())
                panel.border = JBUI.Borders.emptyLeft(24)
                panel.add(checkbox, BorderLayout.WEST)
                scalarFormBuilder.addComponent(panel)
            }
        }

        val scalarPanel = JBScrollPane(
            scalarFormBuilder.addComponentFillVertically(JBPanel<JBPanel<*>>(), 0).panel,
            ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED,
            ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER
        ).apply {
            border = JBUI.Borders.empty()
            viewportBorder = JBUI.Borders.empty(12)
        }
        tabbedPane.addTab("Scalar", scalarPanel)

        component = tabbedPane

        setRendererOrder(OpenApiPreviewSettings.instance.rendererOrder())
        setHiddenScalarClients(OpenApiPreviewSettings.instance.hiddenScalarClients())
    }

    fun rendererOrder(): List<PreviewRenderer> {
        return (0 until rendererListModel.size()).map { rendererListModel.getElementAt(it) }
    }

    fun setRendererOrder(renderers: List<PreviewRenderer>) {
        rendererListModel.clear()
        renderers.forEach { rendererListModel.addElement(it) }
        rendererList.selectedIndex = 0
    }

    fun hiddenScalarClients(): Set<String> {
        return clientCheckboxes.filterValues { !it.isSelected }.keys
    }

    fun setHiddenScalarClients(clients: Set<String>) {
        clientCheckboxes.forEach { (key, checkbox) ->
            checkbox.isSelected = !clients.contains(key)
        }
        SCALAR_CLIENTS.forEach { (language, languageClients) ->
            val allVisible = languageClients.none { clients.contains("$language:$it") }
            languageCheckboxes[language]?.isSelected = allVisible
        }
    }

    private fun createRendererOrderPanel(): JComponent {
        return ToolbarDecorator.createDecorator(rendererList)
            .disableAddAction()
            .disableRemoveAction()
            .setMoveUpAction { moveSelectedRenderer(-1) }
            .setMoveDownAction { moveSelectedRenderer(1) }
            .createPanel()
    }

    private fun moveSelectedRenderer(offset: Int) {
        val currentIndex = rendererList.selectedIndex
        val nextIndex = currentIndex + offset
        if (currentIndex !in 0 until rendererListModel.size() || nextIndex !in 0 until rendererListModel.size()) {
            return
        }

        val renderer = rendererListModel.getElementAt(currentIndex)
        rendererListModel.removeElementAt(currentIndex)
        rendererListModel.insertElementAt(renderer, nextIndex)
        rendererList.selectedIndex = nextIndex
    }

    companion object {
        private val SCALAR_CLIENTS = mapOf(
            "c" to listOf("libcurl"),
            "clojure" to listOf("clj_http"),
            "csharp" to listOf("httpclient", "restsharp"),
            "dart" to listOf("http"),
            "fsharp" to listOf("httpclient"),
            "go" to listOf("native"),
            "http" to listOf("http1.1"),
            "java" to listOf("asynchttp", "nethttp", "okhttp", "unirest"),
            "js" to listOf("axios", "fetch", "jquery", "ofetch", "xhr"),
            "kotlin" to listOf("okhttp"),
            "node" to listOf("axios", "fetch", "ofetch", "undici"),
            "objc" to listOf("nsurlsession"),
            "ocaml" to listOf("cohttp"),
            "php" to listOf("curl", "guzzle", "laravel"),
            "powershell" to listOf("restmethod", "webrequest"),
            "python" to listOf("aiohttp", "httpx_async", "httpx_sync", "python3", "requests"),
            "r" to listOf("httr2"),
            "ruby" to listOf("native"),
            "rust" to listOf("reqwest"),
            "shell" to listOf("curl", "httpie", "wget"),
            "swift" to listOf("nsurlsession"),
        )
    }
}

private class RendererListCellRenderer : ColoredListCellRenderer<PreviewRenderer>() {
    override fun customizeCellRenderer(
        list: JList<out PreviewRenderer>,
        value: PreviewRenderer?,
        index: Int,
        selected: Boolean,
        hasFocus: Boolean,
    ) {
        if (value == null) {
            return
        }

        icon = value.icon
        append(value.presentableName, SimpleTextAttributes.REGULAR_ATTRIBUTES)
    }
}
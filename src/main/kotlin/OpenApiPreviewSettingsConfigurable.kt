package dev.vmonot

import com.intellij.openapi.options.Configurable
import com.intellij.ui.ColoredListCellRenderer
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.ToolbarDecorator
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBPanel
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import javax.swing.DefaultListModel
import javax.swing.JComponent
import javax.swing.JList
import javax.swing.ListSelectionModel

internal class OpenApiPreviewSettingsConfigurable : Configurable {
    private var settingsPanel: OpenApiPreviewSettingsPanel? = null

    override fun getDisplayName(): String = "Scalar OpenAPI Preview"

    override fun createComponent(): JComponent {
        val panel = OpenApiPreviewSettingsPanel()
        settingsPanel = panel

        return panel.component
    }

    override fun isModified(): Boolean {
        return settingsPanel?.rendererOrder() != OpenApiPreviewSettings.instance.rendererOrder()
    }

    override fun apply() {
        settingsPanel?.let {
            OpenApiPreviewSettings.instance.setRendererOrder(it.rendererOrder())
        }
    }

    override fun reset() {
        settingsPanel?.setRendererOrder(OpenApiPreviewSettings.instance.rendererOrder())
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

    val component: JComponent = JBPanel<JBPanel<*>>(BorderLayout(0, JBUI.scale(8))).apply {
        border = JBUI.Borders.empty(12)
        add(JBLabel("OpenAPI preview renderer order:"), BorderLayout.NORTH)
        add(createRendererOrderPanel(), BorderLayout.CENTER)
        add(
            JBLabel("The first available renderer is used when opening a preview. The switch action follows this order."),
            BorderLayout.SOUTH,
        )
    }

    init {
        setRendererOrder(OpenApiPreviewSettings.instance.rendererOrder())
    }

    fun rendererOrder(): List<PreviewRenderer> {
        return (0 until rendererListModel.size()).map { rendererListModel.getElementAt(it) }
    }

    fun setRendererOrder(renderers: List<PreviewRenderer>) {
        rendererListModel.clear()
        renderers.forEach { rendererListModel.addElement(it) }
        rendererList.selectedIndex = 0
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

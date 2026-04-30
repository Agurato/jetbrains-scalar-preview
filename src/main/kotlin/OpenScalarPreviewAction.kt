package dev.vmonot

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.DumbAwareAction

class OpenScalarPreviewAction : DumbAwareAction() {
    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(event: AnActionEvent) {
        val project = event.project
        val file = event.getData(CommonDataKeys.VIRTUAL_FILE)
        val isAvailable = project != null && file != null && OpenApiPreviewEditorProvider.acceptsFile(file)

        event.presentation.isEnabledAndVisible = isAvailable
    }

    override fun actionPerformed(event: AnActionEvent) {
        val project = event.project ?: return
        val file = event.getData(CommonDataKeys.VIRTUAL_FILE) ?: return
        val fileEditorManager = FileEditorManager.getInstance(project)

        fileEditorManager.openFile(file, true, false)
        fileEditorManager.setSelectedEditor(file, OpenApiPreviewEditorProvider.EDITOR_TYPE_ID)
    }
}

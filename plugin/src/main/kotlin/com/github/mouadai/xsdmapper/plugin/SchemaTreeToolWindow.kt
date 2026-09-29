package com.github.mouadai.xsdmapper.plugin

import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.ui.content.ContentFactory

class SchemaTreeToolWindowFactory : ToolWindowFactory, DumbAware {
    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val panel = SchemaTreePanel(project)
        val content = ContentFactory.getInstance().createContent(panel, null, false)
        content.setDisposer(panel)
        toolWindow.contentManager.addContent(content)

        FileEditorManager.getInstance(project).selectedFiles.firstOrNull { isXsd(it) }?.let(panel::load)
    }
}

object SchemaTreeToolWindow {
    const val ID = "Schema Tree"

    /** Opens the tool window and loads [file] into it. */
    fun show(project: Project, file: VirtualFile) {
        val toolWindow = ToolWindowManager.getInstance(project).getToolWindow(ID) ?: return
        toolWindow.activate {
            toolWindow.contentManager.contents
                .firstNotNullOfOrNull { it.component as? SchemaTreePanel }
                ?.load(file)
        }
    }
}

fun isXsd(file: VirtualFile): Boolean = !file.isDirectory && file.extension.equals("xsd", ignoreCase = true)

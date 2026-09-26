package com.thusitha.callstackexplorer.ui

import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.content.ContentFactory
import java.util.WeakHashMap

class CallStackToolWindowFactory : ToolWindowFactory, DumbAware {

    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val panel = SavedStacksPanel(project)
        panels[project] = panel
        val content = ContentFactory.getInstance().createContent(panel, "", false)
        toolWindow.contentManager.addContent(content)
    }

    companion object {
        private val panels = WeakHashMap<Project, SavedStacksPanel>()

        /** Refreshes the tool window's panel if it has been created for [project]. */
        fun refresh(project: Project) {
            val panel = panels[project] ?: return
            com.intellij.openapi.application.ApplicationManager.getApplication().invokeLater {
                panel.refresh()
            }
        }
    }
}

package com.thusitha.callstackexplorer.ui

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.text.StringUtil
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.ui.content.ContentFactory
import com.thusitha.callstackexplorer.capture.SnapshotKind
import com.thusitha.callstackexplorer.compare.SnapshotDiffResult
import java.util.WeakHashMap

/**
 * Tabs, like the Debug tool window: "Call Stacks" and "Values" are fixed;
 * each comparison opens as its own closable tab.
 */
class CallStackToolWindowFactory : ToolWindowFactory, DumbAware {

    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val factory = ContentFactory.getInstance()
        val stacks = SnapshotBrowserPanel(project, SnapshotKind.STACK)
        val values = SnapshotBrowserPanel(project, SnapshotKind.VALUES)
        panels[project] = listOf(stacks, values)

        toolWindow.contentManager.addContent(factory.createContent(stacks, "Call Stacks", false).apply { isCloseable = false })
        toolWindow.contentManager.addContent(factory.createContent(values, "Values", false).apply { isCloseable = false })
    }

    companion object {
        const val TOOL_WINDOW_ID = "Call Stack Explorer"
        private val panels = WeakHashMap<Project, List<SnapshotBrowserPanel>>()

        /** Refreshes both tabs, if the tool window has been created for [project]. */
        fun refresh(project: Project) {
            val list = panels[project] ?: return
            ApplicationManager.getApplication().invokeLater {
                if (!project.isDisposed) list.forEach { it.refresh() }
            }
        }

        /** Opens [result] as a new closable tab and brings the tool window forward. Call on the EDT. */
        fun showComparison(project: Project, result: SnapshotDiffResult) {
            val toolWindow = ToolWindowManager.getInstance(project).getToolWindow(TOOL_WINDOW_ID) ?: return
            toolWindow.show {
                val title = "${StringUtil.shortenTextWithEllipsis(result.before.label, 18, 0)} ↔ " +
                    StringUtil.shortenTextWithEllipsis(result.after.label, 18, 0)
                val content = ContentFactory.getInstance().createContent(ComparisonPanel(project, result), title, false)
                content.isCloseable = true
                content.description = "${result.before.label}  ↔  ${result.after.label}"
                toolWindow.contentManager.addContent(content)
                toolWindow.contentManager.setSelectedContent(content, true)
            }
        }
    }
}

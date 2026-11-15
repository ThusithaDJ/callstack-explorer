package com.thusitha.callstackexplorer.action

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.xdebugger.XDebuggerManager
import com.intellij.xdebugger.frame.XValue
import com.intellij.xdebugger.impl.ui.tree.actions.XDebuggerTreeActionBase
import com.thusitha.callstackexplorer.capture.SnapshotSaver
import com.thusitha.callstackexplorer.capture.StackSnapshotBuilder
import com.thusitha.callstackexplorer.settings.CallStackExplorerSettings

/**
 * "Save Value to Call Stack Explorer" in the debugger Variables/Watches popup:
 * stores just the selected objects (deeper than a full-stack capture), so they
 * can be compared with the same variables in a later debug run.
 */
class SaveValuesAction : AnAction() {

    // Reads Swing tree selection, so stay on the EDT.
    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT

    override fun update(e: AnActionEvent) {
        val project = e.project
        val session = project?.let { XDebuggerManager.getInstance(it).currentSession }
        val hasSelection = XDebuggerTreeActionBase.getSelectedNodes(e.dataContext).isNotEmpty()
        e.presentation.isEnabledAndVisible = session != null && session.isSuspended && hasSelection
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val session = XDebuggerManager.getInstance(project).currentSession ?: return
        if (!session.isSuspended) return

        val values: List<Pair<String, XValue>> = XDebuggerTreeActionBase.getSelectedNodes(e.dataContext)
            .mapNotNull { node -> (node.valueContainer as? XValue)?.let { node.name.orEmpty() to it } }
        if (values.isEmpty()) return

        val position = session.currentStackFrame?.sourcePosition
        val location = "${position?.file?.name ?: "frame"}:${(position?.line ?: -1) + 1}"
        val names = values.joinToString(", ") { it.first }
        val label = "Values: $names @ $location"
        val depth = CallStackExplorerSettings.getInstance(project).settingsState.valueCaptureDepth

        SnapshotSaver.captureAndSave(
            project,
            "Saving object values",
            "Reading $names (up to $depth levels deep) from the debugger. Large collections can take a while."
        ) { indicator ->
            StackSnapshotBuilder.captureValues(session, label, values, depth, indicator = indicator)
        }
    }
}

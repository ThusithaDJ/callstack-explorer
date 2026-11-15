package com.thusitha.callstackexplorer.action

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.ui.Messages
import com.intellij.xdebugger.XDebuggerManager
import com.thusitha.callstackexplorer.capture.SnapshotSaver
import com.thusitha.callstackexplorer.capture.SnapshotTextRenderer
import com.thusitha.callstackexplorer.capture.StackSnapshotBuilder
import com.thusitha.callstackexplorer.settings.CallStackExplorerSettings

class SaveStackAction : AnAction() {

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        val project = e.project
        val session = project?.let { XDebuggerManager.getInstance(it).currentSession }
        e.presentation.isEnabled = session != null && session.isSuspended
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val session = XDebuggerManager.getInstance(project).currentSession ?: return
        if (!session.isSuspended) return

        val frame = session.currentStackFrame
        val position = frame?.sourcePosition
        val fileName = position?.file?.name ?: "breakpoint"
        val line = (position?.line ?: -1) + 1
        val timestamp = SnapshotTextRenderer.TIMESTAMP.format(java.time.Instant.now())
        val defaultLabel = "$fileName:$line — $timestamp"

        val label = Messages.showInputDialog(
            project,
            "Label for this snapshot:",
            "Save Call Stack",
            Messages.getQuestionIcon(),
            defaultLabel,
            null
        ) ?: return

        val maxDepth = CallStackExplorerSettings.getInstance(project).settingsState.maxVariableDepth

        SnapshotSaver.captureAndSave(
            project,
            "Capturing call stack",
            "Reading frames and variable values from the debugger. Large objects or a remote JVM can make this slow."
        ) { indicator ->
            StackSnapshotBuilder.captureFull(session, label, maxDepth, indicator = indicator)
        }
    }
}

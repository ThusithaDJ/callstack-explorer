package com.thusitha.callstackexplorer.action

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.xdebugger.XDebuggerManager
import com.thusitha.callstackexplorer.capture.StackSnapshotBuilder
import com.thusitha.callstackexplorer.settings.CallStackExplorerSettings
import com.thusitha.callstackexplorer.storage.SnapshotStorageService
import com.thusitha.callstackexplorer.ui.CallStackToolWindowFactory
import java.time.format.DateTimeFormatter
import java.time.ZoneId

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
        val timestamp = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
            .withZone(ZoneId.systemDefault())
            .format(java.time.Instant.now())
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

        ProgressManager.getInstance().run(object : Task.Backgroundable(project, "Capturing Call Stack", false) {
            override fun run(indicator: ProgressIndicator) {
                val snapshot = StackSnapshotBuilder.captureFull(session, label, maxDepth)
                if (snapshot == null) {
                    return
                }
                val storage = SnapshotStorageService.getInstance(project)
                storage.save(snapshot)
                storage.pruneToLimit(CallStackExplorerSettings.getInstance(project).settingsState.maxSnapshotsRetained)
                CallStackToolWindowFactory.refresh(project)
            }
        })
    }
}

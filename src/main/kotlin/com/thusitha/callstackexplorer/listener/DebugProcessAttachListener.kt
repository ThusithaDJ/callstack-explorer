package com.thusitha.callstackexplorer.listener

import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.thusitha.callstackexplorer.capture.StackSnapshotBuilder
import com.thusitha.callstackexplorer.settings.CallStackExplorerSettings
import com.thusitha.callstackexplorer.storage.SnapshotStorageService
import com.thusitha.callstackexplorer.ui.CallStackToolWindowFactory
import com.intellij.xdebugger.XDebugProcess
import com.intellij.xdebugger.XDebugSession
import com.intellij.xdebugger.XDebugSessionListener
import com.intellij.xdebugger.XDebuggerManagerListener

/**
 * Attaches an [XDebugSessionListener] to every debug session as it starts, so
 * auto-capture (if enabled) can fire on `sessionPaused()` without the user
 * having to do anything.
 */
class DebugProcessAttachListener : XDebuggerManagerListener {

    override fun processStarted(debugProcess: XDebugProcess) {
        val session = debugProcess.session
        val project = session.project

        // Debounce: skip auto-capture if the last one for this (thread, location) fired recently.
        val lastCaptureKeyToTime = HashMap<String, Long>()

        session.addSessionListener(object : XDebugSessionListener {
            override fun sessionPaused() {
                val settings = CallStackExplorerSettings.getInstance(project)
                if (!settings.settingsState.autoCaptureOnPause) return

                val frame = session.currentStackFrame
                val position = frame?.sourcePosition
                val key = "${position?.file?.url}:${position?.line}"
                val now = System.currentTimeMillis()
                val last = lastCaptureKeyToTime[key] ?: 0L
                if (now - last < settings.settingsState.autoCaptureDebounceMs) return
                lastCaptureKeyToTime[key] = now

                captureAsync(project, session, autoLabelFor(session))
            }
        })
    }

    private fun autoLabelFor(session: XDebugSession): String {
        val frame = session.currentStackFrame
        val position = frame?.sourcePosition
        val fileName = position?.file?.name ?: "breakpoint"
        val line = (position?.line ?: -1) + 1
        return "$fileName:$line (auto)"
    }

    private fun captureAsync(project: Project, session: XDebugSession, label: String) {
        ProgressManager.getInstance().run(object : Task.Backgroundable(project, "Capturing Call Stack", false) {
            override fun run(indicator: com.intellij.openapi.progress.ProgressIndicator) {
                val settings = CallStackExplorerSettings.getInstance(project).settingsState
                val snapshot = StackSnapshotBuilder.captureFull(session, label, settings.maxVariableDepth)
                    ?: return
                val storage = SnapshotStorageService.getInstance(project)
                storage.save(snapshot)
                storage.pruneToLimit(settings.maxSnapshotsRetained)
                CallStackToolWindowFactory.refresh(project)
            }
        })
    }
}

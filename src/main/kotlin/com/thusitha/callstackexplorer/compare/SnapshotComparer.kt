package com.thusitha.callstackexplorer.compare

import com.intellij.diff.DiffContentFactory
import com.intellij.diff.DiffManager
import com.intellij.diff.requests.SimpleDiffRequest
import com.intellij.notification.NotificationType
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.xdebugger.XDebuggerManager
import com.thusitha.callstackexplorer.capture.CaptureRunner
import com.thusitha.callstackexplorer.capture.SnapshotKind
import com.thusitha.callstackexplorer.capture.SnapshotModel
import com.thusitha.callstackexplorer.capture.SnapshotTextRenderer
import com.thusitha.callstackexplorer.capture.StackSnapshotBuilder
import com.thusitha.callstackexplorer.settings.CallStackExplorerSettings
import com.thusitha.callstackexplorer.ui.CallStackToolWindowFactory
import com.thusitha.callstackexplorer.ui.ComparisonPanel

/**
 * Opens comparisons as a tab in the Call Stack Explorer tool window, rendered
 * like the debugger's Variables view (see [ComparisonPanel]).
 */
object SnapshotComparer {

    /** Compares two saved snapshots, the older one as "before". */
    fun compare(project: Project, first: SnapshotModel, second: SnapshotModel) {
        val (before, after) = if (first.capturedAt <= second.capturedAt) first to second else second to first
        show(project, before, after)
    }

    fun diff(project: Project, before: SnapshotModel, after: SnapshotModel): SnapshotDiffResult =
        SnapshotDiff.compare(before, after, CallStackExplorerSettings.getInstance(project).settingsState.ignoreObjectIdsInCompare)

    private fun show(project: Project, before: SnapshotModel, after: SnapshotModel) {
        val result = diff(project, before, after)
        ApplicationManager.getApplication().invokeLater {
            if (!project.isDisposed) CallStackToolWindowFactory.showComparison(project, result)
        }
    }

    /**
     * Captures the same shape as [saved] from the currently suspended session
     * (the same named variables for a value snapshot, the whole stack
     * otherwise) and diffs saved (left) against live (right).
     */
    fun compareWithLive(project: Project, saved: SnapshotModel) {
        val session = XDebuggerManager.getInstance(project).currentSession
        if (session == null || !session.isSuspended) {
            CaptureRunner.notify(project, "Pause the debugger at a breakpoint first, then compare.", NotificationType.WARNING)
            return
        }
        val isValues = saved.effectiveKind == SnapshotKind.VALUES
        val names = topLevelNames(saved)

        CaptureRunner.run(
            project,
            "Capturing current values to compare",
            if (isValues) "Reading ${names.joinToString(", ")} from the paused debugger."
            else "Reading frames and variable values from the paused debugger.",
            capture = { indicator -> captureLiveMatching(project, session, saved, indicator) }
        ) { live, _, _ ->
            when {
                live == null ->
                    CaptureRunner.notify(project, "Could not read the current debugger state.", NotificationType.WARNING)
                isEmptyValues(live) ->
                    CaptureRunner.notify(
                        project,
                        "None of ${names.joinToString(", ")} exist in the current frame - select the matching frame and try again.",
                        NotificationType.WARNING
                    )
                else -> show(project, saved, live)
            }
        }
    }

    /** Captures the live session in the same shape as [saved]; blocks, so call off the EDT. */
    fun captureLiveMatching(
        project: Project,
        session: com.intellij.xdebugger.XDebugSession,
        saved: SnapshotModel,
        indicator: com.intellij.openapi.progress.ProgressIndicator?
    ): SnapshotModel? {
        val settings = CallStackExplorerSettings.getInstance(project).settingsState
        return if (saved.effectiveKind == SnapshotKind.VALUES) {
            StackSnapshotBuilder.captureNamedVariables(
                session, "Current debug session", topLevelNames(saved), settings.valueCaptureDepth, indicator = indicator
            )
        } else {
            StackSnapshotBuilder.captureFull(
                session, "Current debug session", settings.maxVariableDepth, indicator = indicator
            )
        }
    }

    fun isEmptyValues(snapshot: SnapshotModel): Boolean =
        snapshot.effectiveKind == SnapshotKind.VALUES && snapshot.threads.all { t -> t.frames.all { it.variables.isEmpty() } }

    private fun topLevelNames(snapshot: SnapshotModel): List<String> =
        snapshot.threads.flatMap { t -> t.frames.flatMap { f -> f.variables.map { it.name } } }.distinct()

    /** The plain side-by-side text diff, offered from the comparison tab. */
    fun showTextDiff(project: Project, left: SnapshotModel, right: SnapshotModel) {
        val ignoreIds = CallStackExplorerSettings.getInstance(project).settingsState.ignoreObjectIdsInCompare
        val leftText = SnapshotTextRenderer.render(left, ignoreIds)
        val rightText = SnapshotTextRenderer.render(right, ignoreIds)
        ApplicationManager.getApplication().invokeLater {
            if (project.isDisposed) return@invokeLater
            val factory = DiffContentFactory.getInstance()
            val request = SimpleDiffRequest(
                "Compare Snapshots",
                factory.create(project, leftText),
                factory.create(project, rightText),
                SnapshotTextRenderer.header(left),
                SnapshotTextRenderer.header(right)
            )
            DiffManager.getInstance().showDiff(project, request)
        }
    }
}

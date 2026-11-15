package com.thusitha.callstackexplorer.capture

import com.intellij.notification.NotificationType
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.project.Project
import com.thusitha.callstackexplorer.settings.CallStackExplorerSettings
import com.thusitha.callstackexplorer.storage.SnapshotStorageService
import com.thusitha.callstackexplorer.ui.CallStackToolWindowFactory

/** Capture → persist → prune → refresh tool window, with the slow-capture notice from [CaptureRunner]. */
object SnapshotSaver {

    fun captureAndSave(
        project: Project,
        title: String,
        slowMessage: String,
        capture: (ProgressIndicator) -> SnapshotModel?
    ) {
        CaptureRunner.run(project, title, slowMessage, capture) { snapshot, elapsedMs, wasSlow ->
            if (snapshot == null) {
                if (wasSlow) {
                    CaptureRunner.notify(project, "Nothing was captured - the debugger is no longer suspended.", NotificationType.WARNING)
                }
                return@run
            }
            val storage = SnapshotStorageService.getInstance(project)
            storage.save(snapshot)
            storage.pruneToLimit(CallStackExplorerSettings.getInstance(project).settingsState.maxSnapshotsRetained)
            CallStackToolWindowFactory.refresh(project)
            if (wasSlow) {
                CaptureRunner.notify(project, "Saved '${snapshot.label}' in ${CaptureRunner.formatElapsed(elapsedMs)}.")
            }
        }
    }
}

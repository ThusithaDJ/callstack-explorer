package com.thusitha.callstackexplorer.capture

import com.intellij.notification.Notification
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.util.concurrency.AppExecutorUtil
import com.thusitha.callstackexplorer.settings.CallStackExplorerSettings
import java.util.concurrent.TimeUnit

/**
 * Runs a debugger capture in a cancellable background task. If it is still
 * running after the configured delay, a sticky notice appears in the
 * bottom-right corner explaining what the IDE is busy with; it is replaced by a
 * short "done" balloon when the capture finishes. Fast captures stay silent.
 */
object CaptureRunner {

    const val NOTIFICATION_GROUP = "Call Stack Explorer"
    private const val PROGRESS_GROUP = "Call Stack Explorer Progress"

    fun <T : Any> run(
        project: Project,
        title: String,
        slowMessage: String,
        capture: (ProgressIndicator) -> T?,
        onFinished: (result: T?, elapsedMs: Long, wasSlow: Boolean) -> Unit
    ) {
        ProgressManager.getInstance().run(object : Task.Backgroundable(project, title, true) {
            override fun run(indicator: ProgressIndicator) {
                val delayMs = CallStackExplorerSettings.getInstance(project).settingsState.slowCaptureNoticeMs
                val notice = SlowNotice(project, title, slowMessage)
                val scheduled = AppExecutorUtil.getAppScheduledExecutorService()
                    .schedule({ notice.show() }, delayMs.coerceAtLeast(0), TimeUnit.MILLISECONDS)

                val started = System.nanoTime()
                var result: T? = null
                try {
                    result = capture(indicator)
                } finally {
                    scheduled.cancel(false)
                    val wasSlow = notice.dismiss()
                    val elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)
                    onFinished(result, elapsedMs, wasSlow)
                }
            }
        })
    }

    fun notify(project: Project, content: String, type: NotificationType = NotificationType.INFORMATION) {
        NotificationGroupManager.getInstance()
            .getNotificationGroup(NOTIFICATION_GROUP)
            .createNotification(content, type)
            .notify(project)
    }

    fun formatElapsed(ms: Long): String = if (ms < 1000) "${ms} ms" else String.format("%.1f s", ms / 1000.0)

    /** Guards against the scheduled show racing with completion. */
    private class SlowNotice(private val project: Project, private val title: String, private val message: String) {
        private var notification: Notification? = null
        private var finished = false

        @Synchronized
        fun show() {
            if (finished || project.isDisposed) return
            notification = NotificationGroupManager.getInstance()
                .getNotificationGroup(PROGRESS_GROUP)
                .createNotification("$title...", message, NotificationType.INFORMATION)
                .also { it.notify(project) }
        }

        /** Returns true if the notice had been shown, i.e. the capture was slow. */
        @Synchronized
        fun dismiss(): Boolean {
            finished = true
            val shown = notification ?: return false
            shown.expire()
            notification = null
            return true
        }
    }
}

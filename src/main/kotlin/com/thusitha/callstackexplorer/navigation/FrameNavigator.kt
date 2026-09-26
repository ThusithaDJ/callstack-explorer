package com.thusitha.callstackexplorer.navigation

import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.psi.JavaPsiFacade
import com.intellij.psi.impl.compiled.ClsFileImpl
import com.intellij.psi.search.GlobalSearchScope
import com.thusitha.callstackexplorer.capture.FrameSnapshot

object FrameNavigator {

    /**
     * Opens the source location for [frame]. Prefers the file URL captured at
     * snapshot time (works even if the class was since deleted, as long as the
     * file itself still exists); falls back to resolving the class by name so
     * navigation still works if the snapshot's file moved.
     */
    fun navigate(project: Project, frame: FrameSnapshot) {
        val fileFromUrl = frame.fileUrl?.let { VirtualFileManager.getInstance().findFileByUrl(it) }

        val virtualFile = fileFromUrl ?: run {
            val psiClass = JavaPsiFacade.getInstance(project)
                .findClass(frame.className, GlobalSearchScope.allScope(project))
            val vFile = psiClass?.containingFile?.virtualFile
            if (vFile != null && psiClass.containingFile is ClsFileImpl) {
                notify(project, "Showing decompiled source for ${frame.className} — original source not found", NotificationType.WARNING)
            }
            vFile
        }

        if (virtualFile == null) {
            notify(project, "Source not found for ${frame.className}", NotificationType.WARNING)
            return
        }

        val line = (frame.lineNumber - 1).coerceAtLeast(0)
        OpenFileDescriptor(project, virtualFile, line, 0).navigate(true)
    }

    private fun notify(project: Project, message: String, type: NotificationType) {
        NotificationGroupManager.getInstance()
            .getNotificationGroup("Call Stack Explorer")
            .createNotification(message, type)
            .notify(project)
    }
}

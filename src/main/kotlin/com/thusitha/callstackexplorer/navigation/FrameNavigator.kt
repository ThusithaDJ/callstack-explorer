package com.thusitha.callstackexplorer.navigation

import com.intellij.notification.NotificationType
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.markup.HighlighterTargetArea
import com.intellij.openapi.editor.markup.RangeHighlighter
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.psi.JavaPsiFacade
import com.intellij.psi.impl.compiled.ClsFileImpl
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.xdebugger.ui.DebuggerColors
import com.thusitha.callstackexplorer.capture.CaptureRunner
import com.thusitha.callstackexplorer.capture.FrameSnapshot

object FrameNavigator {

    private var lastHighlighter: RangeHighlighter? = null
    private var lastEditor: Editor? = null

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
                CaptureRunner.notify(project, "Showing decompiled source for ${frame.className} — original source not found", NotificationType.WARNING)
            }
            vFile
        }

        if (virtualFile == null) {
            CaptureRunner.notify(project, "Source not found for ${frame.className}", NotificationType.WARNING)
            return
        }

        val line = (frame.lineNumber - 1).coerceAtLeast(0)
        OpenFileDescriptor(project, virtualFile, line, 0).navigate(true)
        highlightLine(project, line)
    }

    /** Marks [line] the same way the debugger highlights its current execution line. */
    private fun highlightLine(project: Project, line: Int) {
        val editor = FileEditorManager.getInstance(project).selectedTextEditor ?: return
        clearHighlight()

        val document = editor.document
        if (line >= document.lineCount) return

        lastHighlighter = editor.markupModel.addRangeHighlighter(
            DebuggerColors.EXECUTIONPOINT_ATTRIBUTES,
            document.getLineStartOffset(line),
            document.getLineEndOffset(line),
            DebuggerColors.EXECUTION_LINE_HIGHLIGHTERLAYER,
            HighlighterTargetArea.LINES_IN_RANGE
        )
        lastEditor = editor
    }

    /** Removes the current execution-line highlight, if any. */
    fun clearHighlight() {
        val highlighter = lastHighlighter ?: return
        val editor = lastEditor
        if (editor != null && !editor.isDisposed) {
            editor.markupModel.removeHighlighter(highlighter)
        }
        lastHighlighter = null
        lastEditor = null
    }
}

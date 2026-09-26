package com.thusitha.callstackexplorer.ui

import com.intellij.icons.AllIcons
import com.intellij.ui.ColoredTreeCellRenderer
import com.intellij.ui.SimpleTextAttributes
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import javax.swing.JTree

private val TIMESTAMP_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneId.systemDefault())

class SnapshotTreeCellRenderer : ColoredTreeCellRenderer() {

    override fun customizeCellRenderer(
        tree: JTree,
        value: Any?,
        selected: Boolean,
        expanded: Boolean,
        leaf: Boolean,
        row: Int,
        hasFocus: Boolean
    ) {
        when (value) {
            is SnapshotNode -> {
                icon = AllIcons.Vcs.History
                append(value.summary.label, SimpleTextAttributes.REGULAR_ATTRIBUTES)
                append("  ${TIMESTAMP_FORMAT.format(value.summary.capturedAt)}", SimpleTextAttributes.GRAYED_ATTRIBUTES)
            }
            is ThreadNode -> {
                icon = AllIcons.Debugger.ThreadSuspended
                append(value.thread.threadName, SimpleTextAttributes.REGULAR_ATTRIBUTES)
                append("  (${value.thread.frames.size} frames)", SimpleTextAttributes.GRAYED_ATTRIBUTES)
            }
            is FrameNode -> {
                icon = AllIcons.Debugger.Frame
                val frame = value.frame
                append("${frame.className}.${frame.methodName}()", SimpleTextAttributes.REGULAR_ATTRIBUTES)
                append(":${frame.lineNumber}", SimpleTextAttributes.GRAYED_ATTRIBUTES)
            }
            is VariableNode -> {
                icon = AllIcons.Debugger.Value
                val v = value.variable
                append("${v.name}: ", SimpleTextAttributes.REGULAR_ATTRIBUTES)
                append(v.type, SimpleTextAttributes.GRAY_ATTRIBUTES)
                append(" = ${v.valuePreview}", SimpleTextAttributes.REGULAR_ATTRIBUTES)
            }
            else -> {
                append(value.toString())
            }
        }
    }
}

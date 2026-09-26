package com.thusitha.callstackexplorer.ui

import com.thusitha.callstackexplorer.capture.FrameSnapshot
import com.thusitha.callstackexplorer.capture.SnapshotSummary
import com.thusitha.callstackexplorer.capture.ThreadSnapshot
import com.thusitha.callstackexplorer.capture.VariableSnapshot
import javax.swing.tree.DefaultMutableTreeNode

/** Root → [SnapshotNode] → [ThreadNode] → [FrameNode] → [VariableNode] (recursively). */
class SnapshotNode(val summary: SnapshotSummary) : DefaultMutableTreeNode(summary) {
    var loaded = false
}

class ThreadNode(val thread: ThreadSnapshot) : DefaultMutableTreeNode(thread) {
    init {
        thread.frames.forEach { add(FrameNode(it)) }
    }
}

class FrameNode(val frame: FrameSnapshot) : DefaultMutableTreeNode(frame) {
    init {
        frame.variables.forEach { add(VariableNode(it)) }
    }
}

class VariableNode(val variable: VariableSnapshot) : DefaultMutableTreeNode(variable) {
    init {
        variable.children.forEach { add(VariableNode(it)) }
    }
}

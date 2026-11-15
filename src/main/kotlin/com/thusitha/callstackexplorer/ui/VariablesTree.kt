package com.thusitha.callstackexplorer.ui

import com.intellij.ui.ColoredTreeCellRenderer
import com.intellij.ui.TreeSpeedSearch
import com.intellij.ui.treeStructure.Tree
import com.intellij.util.ui.tree.TreeUtil
import com.thusitha.callstackexplorer.capture.VariableSnapshot
import javax.swing.JTree
import javax.swing.tree.DefaultMutableTreeNode
import javax.swing.tree.DefaultTreeModel

/** Read-only "Variables" pane for stored values, styled like the debugger's. */
class VariablesTree(emptyText: String) : Tree(DefaultTreeModel(DefaultMutableTreeNode())) {

    private class VariableNode(val variable: VariableSnapshot, val topLevel: Boolean) : DefaultMutableTreeNode(variable) {
        init {
            variable.children.forEach { add(VariableNode(it, topLevel = false)) }
        }

        override fun toString(): String = "${variable.name} = ${variable.valuePreview}"
    }

    init {
        isRootVisible = false
        showsRootHandles = true
        this.emptyText.text = emptyText
        cellRenderer = object : ColoredTreeCellRenderer() {
            override fun customizeCellRenderer(
                tree: JTree, value: Any?, selected: Boolean, expanded: Boolean,
                leaf: Boolean, row: Int, hasFocus: Boolean
            ) {
                val node = value as? VariableNode ?: return
                val v = node.variable
                icon = DebuggerStyle.iconFor(v.name, v.type, v.children.isNotEmpty(), node.topLevel)
                DebuggerStyle.append(this, v.name, v.type, v.valuePreview)
            }
        }
        TreeSpeedSearch.installOn(this)
    }

    fun setVariables(variables: List<VariableSnapshot>) {
        val root = DefaultMutableTreeNode()
        variables.forEach { root.add(VariableNode(it, topLevel = true)) }
        (model as DefaultTreeModel).setRoot(root)
        // Like the debugger: top-level variables visible, each collapsed until opened.
        TreeUtil.expand(this, 1)
    }

    fun clear() = setVariables(emptyList())
}

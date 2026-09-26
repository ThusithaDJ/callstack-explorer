package com.thusitha.callstackexplorer.ui

import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionPlaces
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.ui.PopupHandler
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.treeStructure.Tree
import com.thusitha.callstackexplorer.navigation.FrameNavigator
import com.thusitha.callstackexplorer.storage.SnapshotStorageService
import java.awt.BorderLayout
import java.awt.event.KeyAdapter
import java.awt.event.KeyEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.JTree
import javax.swing.event.TreeExpansionEvent
import javax.swing.event.TreeWillExpandListener
import javax.swing.tree.DefaultMutableTreeNode
import javax.swing.tree.DefaultTreeModel
import javax.swing.tree.TreeSelectionModel

class SavedStacksPanel(private val project: Project) : JPanel(BorderLayout()) {

    private val root = DefaultMutableTreeNode("Saved Call Stacks")
    private val treeModel = DefaultTreeModel(root)
    private val tree = Tree(treeModel)

    init {
        tree.selectionModel.selectionMode = TreeSelectionModel.SINGLE_TREE_SELECTION
        tree.isRootVisible = false
        tree.showsRootHandles = true
        tree.cellRenderer = SnapshotTreeCellRenderer()

        tree.addTreeWillExpandListener(object : TreeWillExpandListener {
            override fun treeWillExpand(event: TreeExpansionEvent) {
                val node = event.path.lastPathComponent
                if (node is SnapshotNode && !node.loaded) {
                    loadSnapshotChildren(node)
                }
            }

            override fun treeWillCollapse(event: TreeExpansionEvent) {}
        })

        tree.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                if (e.clickCount == 2) navigateToSelection()
            }
        })
        tree.addKeyListener(object : KeyAdapter() {
            override fun keyPressed(e: KeyEvent) {
                if (e.keyCode == KeyEvent.VK_ENTER) navigateToSelection()
            }
        })

        val actionGroup = createToolbarActions()
        val toolbar = ActionManager.getInstance().createActionToolbar(
            "CallStackExplorer.Toolbar", actionGroup, false
        )
        toolbar.targetComponent = this

        PopupHandler.installPopupMenu(tree, actionGroup, "CallStackExplorer.Popup")

        add(toolbar.component, BorderLayout.WEST)
        add(JBScrollPane(tree), BorderLayout.CENTER)

        refresh()
    }

    fun refresh() {
        root.removeAllChildren()
        val storage = SnapshotStorageService.getInstance(project)
        storage.list().forEach { root.add(SnapshotNode(it)) }
        treeModel.reload()
    }

    private fun loadSnapshotChildren(node: SnapshotNode) {
        node.loaded = true
        val snapshot = SnapshotStorageService.getInstance(project).load(node.summary.id) ?: return
        snapshot.threads.forEach { node.add(ThreadNode(it)) }
        treeModel.reload(node)
    }

    private fun navigateToSelection() {
        val node = tree.lastSelectedPathComponent as? FrameNode ?: return
        FrameNavigator.navigate(project, node.frame)
    }

    private fun selectedSnapshotNode(): SnapshotNode? {
        var node = tree.lastSelectedPathComponent as? DefaultMutableTreeNode ?: return null
        while (node !is SnapshotNode) {
            node = node.parent as? DefaultMutableTreeNode ?: return null
        }
        return node
    }

    private fun createToolbarActions(): DefaultActionGroup {
        val group = DefaultActionGroup()

        group.add(object : AnAction("Refresh", "Reload saved snapshots", AllIcons.Actions.Refresh) {
            override fun getActionUpdateThread() = ActionUpdateThread.BGT
            override fun actionPerformed(e: AnActionEvent) = refresh()
        })

        group.add(object : AnAction("Rename", "Rename the selected snapshot", AllIcons.Actions.Edit) {
            override fun getActionUpdateThread() = ActionUpdateThread.BGT
            override fun update(e: AnActionEvent) {
                e.presentation.isEnabled = selectedSnapshotNode() != null
            }

            override fun actionPerformed(e: AnActionEvent) {
                val node = selectedSnapshotNode() ?: return
                val newLabel = Messages.showInputDialog(
                    project, "New label:", "Rename Snapshot", null, node.summary.label, null
                ) ?: return
                SnapshotStorageService.getInstance(project).rename(node.summary.id, newLabel)
                refresh()
            }
        })

        group.add(object : AnAction("Delete", "Delete the selected snapshot", AllIcons.Actions.GC) {
            override fun getActionUpdateThread() = ActionUpdateThread.BGT
            override fun update(e: AnActionEvent) {
                e.presentation.isEnabled = selectedSnapshotNode() != null
            }

            override fun actionPerformed(e: AnActionEvent) {
                val node = selectedSnapshotNode() ?: return
                SnapshotStorageService.getInstance(project).delete(node.summary.id)
                refresh()
            }
        })

        group.add(object : AnAction("Clear All", "Delete all saved snapshots", AllIcons.Actions.DeleteTag) {
            override fun getActionUpdateThread() = ActionUpdateThread.BGT
            override fun actionPerformed(e: AnActionEvent) {
                val result = Messages.showYesNoDialog(
                    project, "Delete all saved call stack snapshots?", "Clear All", null
                )
                if (result == Messages.YES) {
                    SnapshotStorageService.getInstance(project).clearAll()
                    refresh()
                }
            }
        })

        return group
    }
}

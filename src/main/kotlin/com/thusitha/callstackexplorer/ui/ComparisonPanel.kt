package com.thusitha.callstackexplorer.ui

import com.intellij.icons.AllIcons
import com.intellij.ide.BrowserUtil
import com.intellij.notification.NotificationAction
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.fileChooser.FileChooserFactory
import com.intellij.openapi.fileChooser.FileSaverDescriptor
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.DumbAwareToggleAction
import com.intellij.openapi.project.Project
import com.intellij.ui.ColoredTreeCellRenderer
import com.intellij.ui.DoubleClickListener
import com.intellij.ui.ScrollPaneFactory
import com.intellij.ui.SimpleColoredComponent
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.TreeSpeedSearch
import com.intellij.ui.treeStructure.Tree
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.tree.TreeUtil
import com.thusitha.callstackexplorer.capture.CaptureRunner
import com.thusitha.callstackexplorer.capture.SnapshotTextRenderer
import com.thusitha.callstackexplorer.compare.ComparisonExporter
import com.thusitha.callstackexplorer.compare.DiffKind
import com.thusitha.callstackexplorer.compare.DiffNode
import com.thusitha.callstackexplorer.compare.DiffStatus
import com.thusitha.callstackexplorer.compare.SnapshotComparer
import com.thusitha.callstackexplorer.compare.SnapshotDiffResult
import com.thusitha.callstackexplorer.navigation.FrameNavigator
import java.awt.BorderLayout
import java.awt.datatransfer.StringSelection
import java.awt.event.MouseEvent
import java.nio.file.Files
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import javax.swing.BoxLayout
import javax.swing.JPanel
import javax.swing.JTree
import javax.swing.tree.DefaultMutableTreeNode
import javax.swing.tree.DefaultTreeModel
import javax.swing.tree.TreePath

/**
 * A comparison tab: the "after" values in a tree that looks like the
 * debugger's Variables view, with changed values in the debugger's blue and
 * the previous value struck through beside them.
 */
class ComparisonPanel(private val project: Project, private val result: SnapshotDiffResult) : JPanel(BorderLayout()) {

    private class Row(val diff: DiffNode, val indent: Int) : DefaultMutableTreeNode(diff) {
        override fun toString(): String = "${diff.name} = ${diff.displayValue.orEmpty()}"
    }

    private val tree = Tree(DefaultTreeModel(DefaultMutableTreeNode()))
    private var onlyChanges = false

    init {
        tree.isRootVisible = false
        tree.showsRootHandles = true
        tree.cellRenderer = DiffRenderer()
        tree.emptyText.text = if (result.hasChanges) "Nothing to show" else "No differences"
        TreeSpeedSearch.installOn(tree)
        object : DoubleClickListener() {
            override fun onDoubleClick(event: MouseEvent): Boolean {
                val row = tree.lastSelectedPathComponent as? Row ?: return false
                val frame = row.diff.frame ?: return false
                FrameNavigator.navigate(project, frame)
                return true
            }
        }.installOn(tree)

        val toolbar = ActionManager.getInstance().createActionToolbar("CallStackExplorer.Compare", createActions(), true)
        toolbar.targetComponent = this

        val top = JPanel(BorderLayout())
        top.add(toolbar.component, BorderLayout.NORTH)
        top.add(createHeader(), BorderLayout.CENTER)

        add(top, BorderLayout.NORTH)
        add(ScrollPaneFactory.createScrollPane(tree, true), BorderLayout.CENTER)
        rebuild()
    }

    // ---- header ----------------------------------------------------------------

    private fun createHeader(): JPanel {
        val panel = JPanel()
        panel.layout = BoxLayout(panel, BoxLayout.Y_AXIS)
        panel.border = JBUI.Borders.empty(4, 8, 6, 8)
        panel.add(sideLine("Before", result.before.label, result.before.capturedAt))
        panel.add(sideLine("After", result.after.label, result.after.capturedAt))
        panel.add(SimpleColoredComponent().apply {
            ipad = JBUI.insetsTop(4)
            icon = if (result.hasChanges) AllIcons.Actions.Diff else AllIcons.General.InspectionsOK
            append(result.summary(), if (result.hasChanges) DebuggerStyle.changedAttributes else DebuggerStyle.addedAttributes)
        })
        return panel
    }

    private fun sideLine(side: String, label: String, at: java.time.Instant) = SimpleColoredComponent().apply {
        append("$side:  ".padEnd(9), SimpleTextAttributes.GRAYED_BOLD_ATTRIBUTES)
        append(label, SimpleTextAttributes.REGULAR_ATTRIBUTES)
        append("   ${TIMESTAMP.format(at)}", SimpleTextAttributes.GRAYED_ATTRIBUTES)
    }

    // ---- tree ------------------------------------------------------------------

    private fun rebuild() {
        val root = DefaultMutableTreeNode()
        result.roots.forEach { addRow(root, it, 0) }
        (tree.model as DefaultTreeModel).setRoot(root)
        expandToChanges(root)
    }

    private fun addRow(parent: DefaultMutableTreeNode, diff: DiffNode, depth: Int) {
        if (onlyChanges && !diff.hasChanges) return
        val row = Row(diff, depth)
        parent.add(row)
        diff.children.forEach { addRow(row, it, depth + 1) }
    }

    /** Opens every branch that leads to a difference, so the evidence is visible straight away. */
    private fun expandToChanges(root: DefaultMutableTreeNode) {
        TreeUtil.expand(tree, 1)
        val enumeration = root.depthFirstEnumeration()
        while (enumeration.hasMoreElements()) {
            val row = enumeration.nextElement() as? Row ?: continue
            if (row.diff.status != DiffStatus.UNCHANGED && row.parent is Row) {
                tree.expandPath(TreePath((row.parent as Row).path))
            }
        }
    }

    private inner class DiffRenderer : ColoredTreeCellRenderer() {
        override fun customizeCellRenderer(
            tree: JTree, value: Any?, selected: Boolean, expanded: Boolean,
            leaf: Boolean, row: Int, hasFocus: Boolean
        ) {
            val r = value as? Row ?: return
            val d = r.diff
            when (d.kind) {
                DiffKind.THREAD -> {
                    icon = AllIcons.Debugger.ThreadSuspended
                    append(d.name, statusOr(d, SimpleTextAttributes.REGULAR_BOLD_ATTRIBUTES))
                }
                DiffKind.FRAME -> {
                    icon = AllIcons.Debugger.Frame
                    append(d.name, statusOr(d, SimpleTextAttributes.REGULAR_ATTRIBUTES))
                    append("  ")
                    if (d.status == DiffStatus.CHANGED) {
                        append(d.afterValue.orEmpty(), DebuggerStyle.changedAttributes)
                        append("  ")
                        append(d.beforeValue.orEmpty(), DebuggerStyle.oldValueAttributes)
                    } else {
                        append(d.displayValue.orEmpty(), SimpleTextAttributes.GRAYED_ATTRIBUTES)
                    }
                }
                DiffKind.VARIABLE -> renderVariable(d, r.indent)
            }
            when (d.status) {
                DiffStatus.ADDED -> append("   new", SimpleTextAttributes.GRAYED_SMALL_ATTRIBUTES)
                DiffStatus.REMOVED -> append("   removed", SimpleTextAttributes.GRAYED_SMALL_ATTRIBUTES)
                DiffStatus.UNCHANGED -> if (d.changeCount > 0 && !expanded) {
                    append("   ${d.changeCount} change(s) inside", SimpleTextAttributes.GRAYED_SMALL_ATTRIBUTES)
                }
                DiffStatus.CHANGED -> {}
            }
        }

        private fun renderVariable(d: DiffNode, depth: Int) {
            val type = d.displayType.orEmpty()
            val isTopLevel = depth == 0 || (depth == 1 && result.roots.firstOrNull()?.kind == DiffKind.FRAME)
            icon = DebuggerStyle.iconFor(d.name, type, d.children.isNotEmpty(), isTopLevel)
            when (d.status) {
                DiffStatus.UNCHANGED -> DebuggerStyle.append(this, d.name, d.displayType, d.displayValue)
                DiffStatus.CHANGED -> {
                    DebuggerStyle.append(this, d.name, d.afterType, d.afterValue, valueAttributes = DebuggerStyle.changedAttributes)
                    append("    was ", SimpleTextAttributes.GRAYED_SMALL_ATTRIBUTES)
                    if (!d.beforeType.isNullOrEmpty() && d.beforeType != d.afterType) {
                        append("{${d.beforeType}} ", DebuggerStyle.oldValueAttributes)
                    }
                    append(d.beforeValue.orEmpty().replace('\n', ' '), DebuggerStyle.oldValueAttributes)
                }
                DiffStatus.ADDED -> DebuggerStyle.append(
                    this, d.name, d.afterType, d.afterValue,
                    nameAttributes = DebuggerStyle.addedAttributes, valueAttributes = DebuggerStyle.addedAttributes
                )
                DiffStatus.REMOVED -> DebuggerStyle.append(
                    this, d.name, d.beforeType, d.beforeValue,
                    nameAttributes = DebuggerStyle.removedAttributes, valueAttributes = DebuggerStyle.removedAttributes
                )
            }
        }

        private fun statusOr(d: DiffNode, normal: SimpleTextAttributes): SimpleTextAttributes = when (d.status) {
            DiffStatus.ADDED -> DebuggerStyle.addedAttributes
            DiffStatus.REMOVED -> DebuggerStyle.removedAttributes
            else -> normal
        }
    }

    // ---- actions ---------------------------------------------------------------

    private fun createActions() = DefaultActionGroup().apply {
        add(object : DumbAwareToggleAction("Show Only Changes", "Hide values that are the same on both sides", AllIcons.General.Filter) {
            override fun getActionUpdateThread() = ActionUpdateThread.EDT
            override fun isSelected(e: AnActionEvent) = onlyChanges
            override fun setSelected(e: AnActionEvent, state: Boolean) {
                onlyChanges = state
                rebuild()
            }
        })
        add(simple("Expand All", "Expand every node", AllIcons.Actions.Expandall) { TreeUtil.expandAll(tree) })
        add(simple("Collapse All", "Collapse every node", AllIcons.Actions.Collapseall) { TreeUtil.collapseAll(tree, 0) })
        addSeparator()
        add(simple("Export as HTML", "Save this comparison as a web page you can share as evidence", AllIcons.ToolbarDecorator.Export) {
            exportHtml()
        })
        add(simple("Copy as Text", "Copy the comparison as indented text with +/-/~ markers", AllIcons.Actions.Copy) {
            CopyPasteManager.getInstance().setContents(StringSelection(ComparisonExporter.toText(result, onlyChanges)))
            CaptureRunner.notify(project, "Comparison copied as text.")
        })
        add(simple("Open as Text Diff", "Show both snapshots in the side-by-side text diff viewer", AllIcons.Actions.ListChanges) {
            SnapshotComparer.showTextDiff(project, result.before, result.after)
        })
    }

    private fun simple(text: String, description: String, icon: javax.swing.Icon, perform: () -> Unit) =
        object : DumbAwareAction(text, description, icon) {
            override fun getActionUpdateThread() = ActionUpdateThread.EDT
            override fun actionPerformed(e: AnActionEvent) = perform()
        }

    private fun exportHtml() {
        val descriptor = FileSaverDescriptor("Export Comparison", "Save the comparison as an HTML page", "html")
        val defaultName = "comparison-" + FILE_STAMP.format(result.after.capturedAt) + ".html"
        val wrapper = FileChooserFactory.getInstance().createSaveFileDialog(descriptor, project)
            .save(null as com.intellij.openapi.vfs.VirtualFile?, defaultName) ?: return
        val path = wrapper.file.toPath()
        try {
            Files.writeString(path, ComparisonExporter.toHtml(result, onlyChanges))
        } catch (e: Exception) {
            CaptureRunner.notify(project, "Could not write ${path}: ${e.message}", NotificationType.ERROR)
            return
        }
        NotificationGroupManager.getInstance()
            .getNotificationGroup(CaptureRunner.NOTIFICATION_GROUP)
            .createNotification("Comparison exported to ${path.fileName}", NotificationType.INFORMATION)
            .addAction(NotificationAction.createSimpleExpiring("Open in browser") { BrowserUtil.browse(path.toFile()) })
            .notify(project)
    }

    companion object {
        private val TIMESTAMP = SnapshotTextRenderer.TIMESTAMP
        private val FILE_STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").withZone(ZoneId.systemDefault())
    }
}

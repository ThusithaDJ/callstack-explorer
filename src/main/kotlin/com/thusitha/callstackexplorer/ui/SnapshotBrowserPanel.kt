package com.thusitha.callstackexplorer.ui

import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonShortcuts
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.ui.CollectionListModel
import com.intellij.ui.ColoredListCellRenderer
import com.intellij.ui.DoubleClickListener
import com.intellij.ui.OnePixelSplitter
import com.intellij.ui.PopupHandler
import com.intellij.ui.ScrollPaneFactory
import com.intellij.ui.SimpleColoredComponent
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.components.JBList
import com.intellij.util.ui.JBUI
import com.intellij.xdebugger.XDebuggerManager
import com.thusitha.callstackexplorer.capture.CaptureRunner
import com.thusitha.callstackexplorer.capture.FrameSnapshot
import com.thusitha.callstackexplorer.capture.SnapshotKind
import com.thusitha.callstackexplorer.capture.SnapshotModel
import com.thusitha.callstackexplorer.capture.SnapshotSummary
import com.thusitha.callstackexplorer.capture.SnapshotTextRenderer
import com.thusitha.callstackexplorer.compare.SnapshotComparer
import com.thusitha.callstackexplorer.navigation.FrameNavigator
import com.thusitha.callstackexplorer.storage.SnapshotStorageService
import java.awt.BorderLayout
import java.awt.datatransfer.StringSelection
import java.awt.event.KeyEvent
import java.awt.event.MouseEvent
import javax.swing.JComponent
import javax.swing.JList
import javax.swing.JPanel
import javax.swing.KeyStroke
import javax.swing.ListSelectionModel

/**
 * One tab of the tool window, laid out like the Debug tool window:
 *
 * - [SnapshotKind.STACK]:  Snapshots | Frames | Variables
 * - [SnapshotKind.VALUES]: Snapshots | Variables
 */
class SnapshotBrowserPanel(private val project: Project, private val kind: SnapshotKind) : JPanel(BorderLayout()) {

    private val isStack = kind == SnapshotKind.STACK

    private val snapshotModel = CollectionListModel<SnapshotSummary>()
    private val snapshotList = JBList(snapshotModel)

    private val frameModel = CollectionListModel<FrameSnapshot>()
    private val frameList = JBList(frameModel)
    private val threadHeader = SimpleColoredComponent()

    private val variablesTree = VariablesTree(if (isStack) "Select a frame" else "Select saved values")
    private val variablesHeader = SimpleColoredComponent()

    /** Snapshot currently shown in the Frames/Variables panes. */
    private var current: SnapshotModel? = null

    init {
        setUpSnapshotList()
        if (isStack) setUpFrameList()

        val variablesPanel = titled(variablesHeader, ScrollPaneFactory.createScrollPane(variablesTree, true))
        val snapshotsPanel = ScrollPaneFactory.createScrollPane(snapshotList, true)

        val content: JComponent = if (isStack) {
            val framesPanel = titled(threadHeader, ScrollPaneFactory.createScrollPane(frameList, true))
            OnePixelSplitter(false, "CallStackExplorer.stacks.outer", 0.3f).apply {
                firstComponent = snapshotsPanel
                secondComponent = OnePixelSplitter(false, "CallStackExplorer.stacks.inner", 0.4f).apply {
                    firstComponent = framesPanel
                    secondComponent = variablesPanel
                }
            }
        } else {
            OnePixelSplitter(false, "CallStackExplorer.values", 0.35f).apply {
                firstComponent = snapshotsPanel
                secondComponent = variablesPanel
            }
        }

        val actions = createActions()
        val toolbar = ActionManager.getInstance().createActionToolbar("CallStackExplorer.Toolbar", actions, false)
        toolbar.targetComponent = this
        PopupHandler.installPopupMenu(snapshotList, actions, "CallStackExplorer.Popup")

        add(toolbar.component, BorderLayout.WEST)
        add(content, BorderLayout.CENTER)
        refresh()
    }

    // ---- layout ----------------------------------------------------------------

    private fun titled(header: SimpleColoredComponent, body: JComponent): JComponent = JPanel(BorderLayout()).apply {
        header.border = JBUI.Borders.empty(3, 6)
        add(header, BorderLayout.NORTH)
        add(body, BorderLayout.CENTER)
    }

    private fun setUpSnapshotList() {
        snapshotList.selectionMode = ListSelectionModel.MULTIPLE_INTERVAL_SELECTION
        snapshotList.emptyText.text = if (isStack) "No saved call stacks" else "No saved values"
        snapshotList.emptyText.appendSecondaryText(
            if (isStack) "Pause at a breakpoint and click Save Call Stack"
            else "Right-click variables in the debugger → Save Value",
            SimpleTextAttributes.GRAYED_ATTRIBUTES, null
        )
        snapshotList.cellRenderer = object : ColoredListCellRenderer<SnapshotSummary>() {
            override fun customizeCellRenderer(
                list: JList<out SnapshotSummary>, value: SnapshotSummary?, index: Int, selected: Boolean, hasFocus: Boolean
            ) {
                value ?: return
                icon = if (isStack) AllIcons.Vcs.History else AllIcons.Debugger.Watch
                append(value.label)
                append("  ${SnapshotTextRenderer.TIMESTAMP.format(value.capturedAt)}", SimpleTextAttributes.GRAYED_ATTRIBUTES)
            }
        }
        snapshotList.addListSelectionListener { e -> if (!e.valueIsAdjusting) showSelectedSnapshot() }
    }

    private fun setUpFrameList() {
        frameList.selectionMode = ListSelectionModel.SINGLE_SELECTION
        frameList.emptyText.text = "Select a saved call stack"
        frameList.cellRenderer = object : ColoredListCellRenderer<FrameSnapshot>() {
            override fun customizeCellRenderer(
                list: JList<out FrameSnapshot>, frame: FrameSnapshot?, index: Int, selected: Boolean, hasFocus: Boolean
            ) {
                frame ?: return
                // Same shape as the debugger's Frames view: "method:line, Class (package)"
                icon = AllIcons.Debugger.Frame
                append("${frame.methodName}:${frame.lineNumber}, ")
                append(frame.className.substringAfterLast('.'))
                val pkg = frame.className.substringBeforeLast('.', "")
                if (pkg.isNotEmpty()) append(" ($pkg)", SimpleTextAttributes.GRAYED_ITALIC_ATTRIBUTES)
            }
        }
        frameList.addListSelectionListener { e ->
            if (!e.valueIsAdjusting) {
                val frame = frameList.selectedValue
                if (frame == null) variablesTree.clear() else variablesTree.setVariables(frame.variables)
                updateVariablesHeader(frame)
            }
        }
        object : DoubleClickListener() {
            override fun onDoubleClick(event: MouseEvent): Boolean {
                frameList.selectedValue?.let { FrameNavigator.navigate(project, it) } ?: return false
                return true
            }
        }.installOn(frameList)
        frameList.registerKeyboardAction(
            { frameList.selectedValue?.let { FrameNavigator.navigate(project, it) } },
            KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, 0), JComponent.WHEN_FOCUSED
        )
    }

    // ---- data ------------------------------------------------------------------

    fun refresh() {
        val selectedIds = snapshotList.selectedValuesList.map { it.id }.toSet()
        val summaries = SnapshotStorageService.getInstance(project).list()
            .filter { it.effectiveKind == kind }
        snapshotModel.replaceAll(summaries)
        val indices = summaries.indices.filter { summaries[it].id in selectedIds }.toIntArray()
        if (indices.isNotEmpty()) snapshotList.selectedIndices = indices
        else if (summaries.isNotEmpty() && current == null) snapshotList.selectedIndex = 0
        if (summaries.isEmpty()) showSelectedSnapshot()
    }

    private fun showSelectedSnapshot() {
        val summary = snapshotList.selectedValue
        val snapshot = summary?.let { SnapshotStorageService.getInstance(project).load(it.id) }
        current = snapshot

        threadHeader.clear()
        variablesHeader.clear()
        if (snapshot == null) {
            frameModel.removeAll()
            variablesTree.clear()
            return
        }
        if (isStack) {
            val thread = snapshot.threads.firstOrNull()
            threadHeader.icon = AllIcons.Debugger.ThreadSuspended
            threadHeader.append(thread?.threadName ?: "No thread", SimpleTextAttributes.REGULAR_ATTRIBUTES)
            frameModel.replaceAll(snapshot.threads.flatMap { it.frames })
            if (frameModel.size > 0) frameList.selectedIndex = 0 else variablesTree.clear()
        } else {
            val frame = snapshot.threads.firstOrNull()?.frames?.firstOrNull()
            variablesTree.setVariables(snapshot.threads.flatMap { t -> t.frames.flatMap { it.variables } })
            updateVariablesHeader(frame)
        }
    }

    private fun updateVariablesHeader(frame: FrameSnapshot?) {
        variablesHeader.clear()
        if (frame == null) return
        variablesHeader.icon = AllIcons.Debugger.Frame
        variablesHeader.append(if (isStack) "Variables  " else "Saved at  ", SimpleTextAttributes.GRAYED_ATTRIBUTES)
        variablesHeader.append("${frame.className.substringAfterLast('.')}.${frame.methodName}():${frame.lineNumber}")
    }

    private fun selectedSnapshots(): List<SnapshotModel> {
        val storage = SnapshotStorageService.getInstance(project)
        return snapshotList.selectedValuesList.mapNotNull { storage.load(it.id) }
    }

    private fun deleteSelected() {
        val selected = snapshotList.selectedValuesList
        if (selected.isEmpty()) return
        SnapshotStorageService.getInstance(project).deleteAll(selected.map { it.id })
        FrameNavigator.clearHighlight()
        current = null
        refresh()
    }

    // ---- actions ---------------------------------------------------------------

    private fun createActions(): DefaultActionGroup {
        val group = DefaultActionGroup()
        val noun = if (isStack) "call stack" else "values"

        group.add(action("Refresh", "Reload saved snapshots", AllIcons.Actions.Refresh) { refresh() })
        group.addSeparator()

        group.add(action(
            "Compare Selected", "Compare the two selected snapshots, shown like the debugger's Variables view",
            AllIcons.Actions.Diff, enabled = { snapshotList.selectedValuesList.size == 2 }
        ) {
            val s = selectedSnapshots()
            if (s.size == 2) SnapshotComparer.compare(project, s[0], s[1])
        })

        group.add(action(
            "Compare with Current Debug Session",
            "Compare the selected snapshot with the same ${if (isStack) "stack" else "variables"} in the paused debugger",
            AllIcons.Debugger.Db_set_breakpoint,
            enabled = {
                snapshotList.selectedValuesList.size == 1 &&
                    XDebuggerManager.getInstance(project).currentSession?.isSuspended == true
            }
        ) {
            selectedSnapshots().singleOrNull()?.let { SnapshotComparer.compareWithLive(project, it) }
        })

        if (!isStack) {
            group.add(action(
                "Jump to Source", "Open the line where these values were saved", AllIcons.Actions.EditSource,
                enabled = { current != null }
            ) {
                current?.threads?.firstOrNull()?.frames?.firstOrNull()?.let { FrameNavigator.navigate(project, it) }
            })
        }

        group.add(action(
            "Copy for AI Assistant", "Copy the selected snapshot(s) as Markdown for Copilot, Claude, or another AI chat",
            AllIcons.Actions.Copy, enabled = { snapshotList.selectedValuesList.isNotEmpty() }
        ) {
            val snapshots = selectedSnapshots()
            if (snapshots.isNotEmpty()) {
                CopyPasteManager.getInstance().setContents(
                    StringSelection(snapshots.joinToString("\n") { SnapshotTextRenderer.toMarkdown(it) })
                )
                CaptureRunner.notify(project, "Copied ${snapshots.size} snapshot(s) as Markdown - paste into your AI chat.")
            }
        })
        group.addSeparator()

        group.add(action(
            "Rename", "Rename the selected $noun snapshot", AllIcons.Actions.Edit,
            enabled = { snapshotList.selectedValuesList.size == 1 }
        ) {
            val summary = snapshotList.selectedValue ?: return@action
            val newLabel = Messages.showInputDialog(project, "New label:", "Rename Snapshot", null, summary.label, null)
                ?: return@action
            SnapshotStorageService.getInstance(project).rename(summary.id, newLabel)
            refresh()
        })

        group.add(action(
            "Delete", "Delete the selected snapshot(s)", AllIcons.Actions.GC,
            enabled = { snapshotList.selectedValuesList.isNotEmpty() }
        ) { deleteSelected() }.also { it.registerCustomShortcutSet(CommonShortcuts.getDelete(), snapshotList) })

        group.add(action(
            "Clear All", "Delete all saved ${if (isStack) "call stacks" else "values"}", AllIcons.Actions.DeleteTag,
            enabled = { snapshotModel.size > 0 }
        ) {
            val what = if (isStack) "call stack snapshots" else "saved values"
            if (Messages.showYesNoDialog(project, "Delete all $what?", "Clear All", null) == Messages.YES) {
                SnapshotStorageService.getInstance(project).deleteAll(snapshotModel.items.map { it.id })
                FrameNavigator.clearHighlight()
                current = null
                refresh()
            }
        })

        if (isStack) {
            group.addSeparator()
            group.add(action("Remove Highlight", "Clear the highlighted call stack line", AllIcons.Actions.Cancel) {
                FrameNavigator.clearHighlight()
            })
        }
        return group
    }

    private fun action(
        text: String,
        description: String,
        icon: javax.swing.Icon,
        enabled: () -> Boolean = { true },
        perform: () -> Unit
    ): AnAction = object : DumbAwareAction(text, description, icon) {
        override fun getActionUpdateThread() = ActionUpdateThread.EDT
        override fun update(e: AnActionEvent) {
            e.presentation.isEnabled = enabled()
        }

        override fun actionPerformed(e: AnActionEvent) = perform()
    }
}

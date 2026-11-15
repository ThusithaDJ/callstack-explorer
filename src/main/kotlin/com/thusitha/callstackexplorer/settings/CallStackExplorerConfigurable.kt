package com.thusitha.callstackexplorer.settings

import com.intellij.openapi.options.Configurable
import com.intellij.openapi.options.ConfigurationException
import com.intellij.openapi.project.Project
import com.intellij.ui.TitledSeparator
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBTextArea
import com.intellij.util.ui.FormBuilder
import com.intellij.util.ui.UIUtil
import com.thusitha.callstackexplorer.mcp.McpServerService
import javax.swing.JComponent
import javax.swing.JSpinner
import javax.swing.SpinnerNumberModel

class CallStackExplorerConfigurable(private val project: Project) : Configurable {

    private val autoCaptureCheckBox = JBCheckBox("Auto-capture on every breakpoint pause")
    private val maxDepthSpinner = JSpinner(SpinnerNumberModel(1, 0, 10, 1))
    private val maxSnapshotsSpinner = JSpinner(SpinnerNumberModel(50, 1, 1000, 1))
    private val debounceSpinner = JSpinner(SpinnerNumberModel(1000, 0, 60000, 100))
    private val valueDepthSpinner = JSpinner(SpinnerNumberModel(3, 1, 10, 1))
    private val slowNoticeSpinner = JSpinner(SpinnerNumberModel(700, 0, 60000, 100))
    private val ignoreIdsCheckBox = JBCheckBox("Ignore JVM object ids (e.g. @1234) when comparing")

    private val mcpEnabledCheckBox = JBCheckBox("Let AI assistants read debugger values (local MCP server)")
    private val mcpPortSpinner = JSpinner(SpinnerNumberModel(47821, 1024, 65535, 1)).apply {
        editor = JSpinner.NumberEditor(this, "#")
    }
    private val mcpStatusLabel = JBLabel()
    private val mcpHelp = JBTextArea().apply {
        isEditable = false
        lineWrap = true
        wrapStyleWord = true
        background = UIUtil.getPanelBackground()
        font = UIUtil.getLabelFont(UIUtil.FontSize.SMALL)
    }

    private var panel: JComponent? = null

    override fun getDisplayName(): String = "Call Stack Explorer"

    override fun createComponent(): JComponent {
        val built = FormBuilder.createFormBuilder()
            .addComponent(TitledSeparator("Capture"))
            .addComponent(autoCaptureCheckBox)
            .addLabeledComponent("Max variable capture depth:", maxDepthSpinner)
            .addLabeledComponent("\"Save Value\" capture depth:", valueDepthSpinner)
            .addLabeledComponent("Max snapshots to retain:", maxSnapshotsSpinner)
            .addLabeledComponent("Auto-capture debounce (ms):", debounceSpinner)
            .addLabeledComponent("Show progress notice after (ms):", slowNoticeSpinner)
            .addComponent(TitledSeparator("Compare"))
            .addComponent(ignoreIdsCheckBox)
            .addComponent(TitledSeparator("AI Assistants (IDE-wide)"))
            .addComponent(mcpEnabledCheckBox)
            .addLabeledComponent("Port:", mcpPortSpinner)
            .addComponent(mcpStatusLabel)
            .addComponent(mcpHelp)
            .addComponentFillVertically(javax.swing.JPanel(), 0)
            .panel
        panel = built
        reset()
        return built
    }

    override fun isModified(): Boolean {
        val state = CallStackExplorerSettings.getInstance(project).settingsState
        val mcp = McpServerSettings.getInstance().settingsState
        return autoCaptureCheckBox.isSelected != state.autoCaptureOnPause ||
            (maxDepthSpinner.value as Int) != state.maxVariableDepth ||
            (maxSnapshotsSpinner.value as Int) != state.maxSnapshotsRetained ||
            (debounceSpinner.value as Int).toLong() != state.autoCaptureDebounceMs ||
            (valueDepthSpinner.value as Int) != state.valueCaptureDepth ||
            (slowNoticeSpinner.value as Int).toLong() != state.slowCaptureNoticeMs ||
            ignoreIdsCheckBox.isSelected != state.ignoreObjectIdsInCompare ||
            mcpEnabledCheckBox.isSelected != mcp.enabled ||
            (mcpPortSpinner.value as Int) != mcp.port
    }

    override fun apply() {
        val state = CallStackExplorerSettings.getInstance(project).settingsState
        state.autoCaptureOnPause = autoCaptureCheckBox.isSelected
        state.maxVariableDepth = maxDepthSpinner.value as Int
        state.maxSnapshotsRetained = maxSnapshotsSpinner.value as Int
        state.autoCaptureDebounceMs = (debounceSpinner.value as Int).toLong()
        state.valueCaptureDepth = valueDepthSpinner.value as Int
        state.slowCaptureNoticeMs = (slowNoticeSpinner.value as Int).toLong()
        state.ignoreObjectIdsInCompare = ignoreIdsCheckBox.isSelected

        val mcp = McpServerSettings.getInstance().settingsState
        mcp.enabled = mcpEnabledCheckBox.isSelected
        mcp.port = mcpPortSpinner.value as Int
        val error = McpServerService.getInstance().syncWithSettings()
        updateMcpStatus()
        if (error != null) throw ConfigurationException(error)
    }

    override fun reset() {
        val state = CallStackExplorerSettings.getInstance(project).settingsState
        autoCaptureCheckBox.isSelected = state.autoCaptureOnPause
        maxDepthSpinner.value = state.maxVariableDepth
        maxSnapshotsSpinner.value = state.maxSnapshotsRetained
        debounceSpinner.value = state.autoCaptureDebounceMs.toInt()
        valueDepthSpinner.value = state.valueCaptureDepth
        slowNoticeSpinner.value = state.slowCaptureNoticeMs.toInt()
        ignoreIdsCheckBox.isSelected = state.ignoreObjectIdsInCompare

        val mcp = McpServerSettings.getInstance().settingsState
        mcpEnabledCheckBox.isSelected = mcp.enabled
        mcpPortSpinner.value = mcp.port
        updateMcpStatus()
    }

    private fun updateMcpStatus() {
        val service = McpServerService.getInstance()
        val url = service.endpointUrl
        mcpStatusLabel.text = if (service.isRunning) "Running at $url" else "Not running"
        mcpHelp.text = """
            Listens on 127.0.0.1 only. Connect your assistant to the endpoint above, for example:
              Claude Code:  claude mcp add --transport http call-stack-explorer $url
              Copilot (VS Code .vscode/mcp.json, or JetBrains Copilot MCP settings):
                { "servers": { "call-stack-explorer": { "type": "http", "url": "$url" } } }
            Tools: get_live_debug_state, list_snapshots, get_snapshot, compare_snapshots, compare_live_with_snapshot.
            Note: variable values (which may include secrets or personal data) are shared with the connected assistant.
        """.trimIndent()
    }
}

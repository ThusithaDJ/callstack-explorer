package com.thusitha.callstackexplorer.settings

import com.intellij.openapi.options.Configurable
import com.intellij.openapi.project.Project
import com.intellij.ui.components.JBCheckBox
import com.intellij.util.ui.FormBuilder
import javax.swing.JComponent
import javax.swing.JSpinner
import javax.swing.SpinnerNumberModel

class CallStackExplorerConfigurable(private val project: Project) : Configurable {

    private val autoCaptureCheckBox = JBCheckBox("Auto-capture on every breakpoint pause")
    private val maxDepthSpinner = JSpinner(SpinnerNumberModel(1, 0, 10, 1))
    private val maxSnapshotsSpinner = JSpinner(SpinnerNumberModel(50, 1, 1000, 1))
    private val debounceSpinner = JSpinner(SpinnerNumberModel(1000, 0, 60000, 100))

    private var panel: JComponent? = null

    override fun getDisplayName(): String = "Call Stack Explorer"

    override fun createComponent(): JComponent {
        val built = FormBuilder.createFormBuilder()
            .addComponent(autoCaptureCheckBox)
            .addLabeledComponent("Max variable capture depth:", maxDepthSpinner)
            .addLabeledComponent("Max snapshots to retain:", maxSnapshotsSpinner)
            .addLabeledComponent("Auto-capture debounce (ms):", debounceSpinner)
            .addComponentFillVertically(javax.swing.JPanel(), 0)
            .panel
        panel = built
        reset()
        return built
    }

    override fun isModified(): Boolean {
        val state = CallStackExplorerSettings.getInstance(project).settingsState
        return autoCaptureCheckBox.isSelected != state.autoCaptureOnPause ||
            (maxDepthSpinner.value as Int) != state.maxVariableDepth ||
            (maxSnapshotsSpinner.value as Int) != state.maxSnapshotsRetained ||
            (debounceSpinner.value as Int).toLong() != state.autoCaptureDebounceMs
    }

    override fun apply() {
        val state = CallStackExplorerSettings.getInstance(project).settingsState
        state.autoCaptureOnPause = autoCaptureCheckBox.isSelected
        state.maxVariableDepth = maxDepthSpinner.value as Int
        state.maxSnapshotsRetained = maxSnapshotsSpinner.value as Int
        state.autoCaptureDebounceMs = (debounceSpinner.value as Int).toLong()
    }

    override fun reset() {
        val state = CallStackExplorerSettings.getInstance(project).settingsState
        autoCaptureCheckBox.isSelected = state.autoCaptureOnPause
        maxDepthSpinner.value = state.maxVariableDepth
        maxSnapshotsSpinner.value = state.maxSnapshotsRetained
        debounceSpinner.value = state.autoCaptureDebounceMs.toInt()
    }
}

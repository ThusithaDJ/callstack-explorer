package com.thusitha.callstackexplorer.settings

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.project.Project

class SettingsState {
    var autoCaptureOnPause: Boolean = false
    var maxVariableDepth: Int = 1
    var maxSnapshotsRetained: Int = 50
    var autoCaptureDebounceMs: Long = 1000L

    /** How deep "Save Value" walks into the selected objects' fields. */
    var valueCaptureDepth: Int = 3

    /** A capture running longer than this shows a progress notice in the bottom-right corner. */
    var slowCaptureNoticeMs: Long = 700L

    /** Strip JVM object ids like `@1234` before comparing, so separate runs diff cleanly. */
    var ignoreObjectIdsInCompare: Boolean = true
}

@Service(Service.Level.PROJECT)
@State(name = "CallStackExplorerSettings", storages = [Storage("callStackExplorer.xml")])
class CallStackExplorerSettings : PersistentStateComponent<SettingsState> {

    var settingsState = SettingsState()
        private set

    override fun getState(): SettingsState = settingsState

    override fun loadState(state: SettingsState) {
        this.settingsState = state
    }

    companion object {
        fun getInstance(project: Project): CallStackExplorerSettings =
            project.getService(CallStackExplorerSettings::class.java)
    }
}

class McpSettingsState {
    var enabled: Boolean = false
    var port: Int = 47821
}

/** IDE-wide, because a single MCP server serves every open project. */
@Service(Service.Level.APP)
@State(name = "CallStackExplorerMcpSettings", storages = [Storage("callStackExplorerMcp.xml")])
class McpServerSettings : PersistentStateComponent<McpSettingsState> {

    var settingsState = McpSettingsState()
        private set

    override fun getState(): McpSettingsState = settingsState

    override fun loadState(state: McpSettingsState) {
        this.settingsState = state
    }

    companion object {
        fun getInstance(): McpServerSettings =
            ApplicationManager.getApplication().getService(McpServerSettings::class.java)
    }
}

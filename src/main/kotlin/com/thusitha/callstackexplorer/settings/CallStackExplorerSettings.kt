package com.thusitha.callstackexplorer.settings

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

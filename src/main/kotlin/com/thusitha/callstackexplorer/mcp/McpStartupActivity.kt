package com.thusitha.callstackexplorer.mcp

import com.intellij.notification.NotificationType
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity
import com.thusitha.callstackexplorer.capture.CaptureRunner

/** Starts the MCP server (if enabled) when the first project opens. Idempotent for later projects. */
class McpStartupActivity : ProjectActivity {
    override suspend fun execute(project: Project) {
        McpServerService.getInstance().syncWithSettings()?.let { error ->
            CaptureRunner.notify(project, error, NotificationType.WARNING)
        }
    }
}

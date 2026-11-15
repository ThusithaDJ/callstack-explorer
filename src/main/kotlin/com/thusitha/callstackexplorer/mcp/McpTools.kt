package com.thusitha.callstackexplorer.mcp

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectManager
import com.intellij.xdebugger.XDebugSession
import com.intellij.xdebugger.XDebuggerManager
import com.thusitha.callstackexplorer.capture.CaptureRunner
import com.thusitha.callstackexplorer.capture.SnapshotModel
import com.thusitha.callstackexplorer.capture.SnapshotTextRenderer
import com.thusitha.callstackexplorer.capture.StackSnapshotBuilder
import com.thusitha.callstackexplorer.compare.SnapshotComparer
import com.thusitha.callstackexplorer.compare.SnapshotDiff
import com.thusitha.callstackexplorer.compare.SnapshotDiffResult
import com.thusitha.callstackexplorer.settings.CallStackExplorerSettings
import com.thusitha.callstackexplorer.storage.SnapshotStorageService
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/** The tools exposed over MCP. Every result is plain text/Markdown, which every client handles. */
internal class McpTools {

    private class ToolError(message: String) : Exception(message)

    fun definitions(): JsonArray = JsonArray().apply {
        add(tool(
            "list_snapshots",
            "List call stack / object value snapshots saved in Call Stack Explorer, newest first, with their ids.",
            props = mapOf(PROJECT_PROP)
        ))
        add(tool(
            "get_snapshot",
            "Get a saved snapshot (threads, frames and variable values) as Markdown.",
            props = mapOf("id" to strProp("Snapshot id from list_snapshots."), PROJECT_PROP),
            required = listOf("id")
        ))
        add(tool(
            "get_live_debug_state",
            "Read the current call stack and variable values from the IDE debugger while it is paused at a breakpoint.",
            props = mapOf(
                "max_depth" to JsonObject().apply {
                    addProperty("type", "integer")
                    addProperty("description", "How many levels of object fields to expand (1-6). Defaults to the user's setting.")
                },
                PROJECT_PROP
            )
        ))
        add(tool(
            "compare_snapshots",
            "Compare two saved snapshots (older first): lists each changed, added and removed value as path: before → after. JVM object ids like @1234 are ignored.",
            props = mapOf("id_a" to strProp("First snapshot id."), "id_b" to strProp("Second snapshot id."), PROJECT_PROP),
            required = listOf("id_a", "id_b")
        ))
        add(tool(
            "compare_live_with_snapshot",
            "Capture the same variables (or whole stack) from the paused debugger and diff them against a saved " +
                "snapshot - e.g. to see what changed since a previous debug run.",
            props = mapOf("id" to strProp("Saved snapshot id to compare against."), PROJECT_PROP),
            required = listOf("id")
        ))
    }

    fun call(name: String, args: JsonObject): JsonObject = try {
        val text = when (name) {
            "list_snapshots" -> listSnapshots(args)
            "get_snapshot" -> getSnapshot(args)
            "get_live_debug_state" -> liveState(args)
            "compare_snapshots" -> compareSnapshots(args)
            "compare_live_with_snapshot" -> compareLive(args)
            else -> throw ToolError("Unknown tool: $name")
        }
        textResult(text, isError = false)
    } catch (e: ToolError) {
        textResult(e.message ?: "Error", isError = true)
    }

    // ---- tools ---------------------------------------------------------------

    private fun listSnapshots(args: JsonObject): String {
        val project = resolveProject(args)
        val list = SnapshotStorageService.getInstance(project).list()
        if (list.isEmpty()) return "No saved snapshots in project '${project.name}'."
        return buildString {
            append("Saved snapshots in project '${project.name}' (newest first):\n")
            list.forEach {
                append("- id: ").append(it.id)
                    .append(" | ").append(it.effectiveKind.name.lowercase())
                    .append(" | ").append(it.capturedAt)
                    .append(" | ").append(it.label).append('\n')
            }
        }
    }

    private fun getSnapshot(args: JsonObject): String {
        val project = resolveProject(args)
        return SnapshotTextRenderer.toMarkdown(load(project, args.str("id")))
    }

    private fun liveState(args: JsonObject): String {
        val project = resolveProject(args)
        val session = suspendedSession(project)
        val depth = args.get("max_depth")?.takeIf { it.isJsonPrimitive }?.asInt?.coerceIn(1, 6)
            ?: CallStackExplorerSettings.getInstance(project).settingsState.maxVariableDepth.coerceAtLeast(1)
        val snapshot = captureVisibly(project, "AI assistant is reading the debugger") { indicator ->
            StackSnapshotBuilder.captureFull(session, "Live debugger state", depth, indicator = indicator)
        } ?: throw ToolError("Could not read the debugger state - it may have resumed.")
        return SnapshotTextRenderer.toMarkdown(snapshot)
    }

    private fun compareSnapshots(args: JsonObject): String {
        val project = resolveProject(args)
        var a = load(project, args.str("id_a"))
        var b = load(project, args.str("id_b"))
        if (a.capturedAt > b.capturedAt) a = b.also { b = a }
        return describeDiff(SnapshotDiff.compare(a, b, ignoreObjectIds = true), "Before", "After")
    }

    private fun compareLive(args: JsonObject): String {
        val project = resolveProject(args)
        val saved = load(project, args.str("id"))
        val session = suspendedSession(project)
        val live = captureVisibly(project, "AI assistant is comparing debugger values") { indicator ->
            SnapshotComparer.captureLiveMatching(project, session, saved, indicator)
        } ?: throw ToolError("Could not read the debugger state - it may have resumed.")
        if (SnapshotComparer.isEmptyValues(live)) {
            throw ToolError("None of the saved variables exist in the debugger's current frame.")
        }
        return describeDiff(SnapshotDiff.compare(saved, live, ignoreObjectIds = true), "Saved", "Live")
    }

    /** Summary plus one "path: before → after" line per difference (JVM object ids ignored). */
    private fun describeDiff(result: SnapshotDiffResult, beforeName: String, afterName: String): String = buildString {
        append(beforeName).append(": ").append(SnapshotTextRenderer.header(result.before)).append('\n')
        append(afterName).append(": ").append(SnapshotTextRenderer.header(result.after)).append('\n')
        append(result.summary()).append('\n')
        val changes = result.changeList()
        if (changes.isNotEmpty()) {
            append('\n')
            changes.forEach { append(it).append('\n') }
        }
    }

    // ---- helpers -------------------------------------------------------------

    /**
     * Runs the capture through [CaptureRunner] so the user gets the same
     * progress / slow-capture notice as for their own captures, and can see
     * that an AI tool is reading their debugger.
     */
    private fun captureVisibly(
        project: Project,
        title: String,
        capture: (com.intellij.openapi.progress.ProgressIndicator) -> SnapshotModel?
    ): SnapshotModel? {
        val future = CompletableFuture<SnapshotModel?>()
        ApplicationManager.getApplication().invokeLater {
            try {
                CaptureRunner.run(project, title, "Reading frames and variable values for an MCP client.", capture) { result, _, _ ->
                    future.complete(result)
                }
            } catch (e: Throwable) {
                future.completeExceptionally(e)
            }
        }
        return try {
            future.get(CAPTURE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        } catch (e: TimeoutException) {
            throw ToolError("Timed out after ${CAPTURE_TIMEOUT_SECONDS}s reading the debugger.")
        } catch (e: Exception) {
            throw ToolError("Capture failed: ${e.cause?.message ?: e.message}")
        }
    }

    private fun suspendedSession(project: Project): XDebugSession {
        val session = XDebuggerManager.getInstance(project).currentSession
            ?: XDebuggerManager.getInstance(project).debugSessions.firstOrNull { it.isSuspended }
        if (session == null || !session.isSuspended) {
            throw ToolError("The debugger in project '${project.name}' is not paused at a breakpoint.")
        }
        return session
    }

    private fun load(project: Project, id: String): SnapshotModel =
        SnapshotStorageService.getInstance(project).load(id)
            ?: throw ToolError("No snapshot with id '$id' in project '${project.name}'. Use list_snapshots.")

    /** Named project, else the one paused in the debugger, else the only/first open project. */
    private fun resolveProject(args: JsonObject): Project {
        val open = ProjectManager.getInstance().openProjects.filter { !it.isDisposed && !it.isDefault }
        if (open.isEmpty()) throw ToolError("No project is open in the IDE.")
        val requested = args.get("project")?.takeIf { it.isJsonPrimitive }?.asString
        if (!requested.isNullOrBlank()) {
            return open.firstOrNull { it.name.equals(requested, ignoreCase = true) }
                ?: throw ToolError("No open project named '$requested'. Open projects: ${open.joinToString { it.name }}")
        }
        return open.firstOrNull { p -> XDebuggerManager.getInstance(p).debugSessions.any { it.isSuspended } }
            ?: open.first()
    }

    private fun JsonObject.str(key: String): String =
        get(key)?.takeIf { it.isJsonPrimitive }?.asString ?: throw ToolError("Missing argument '$key'.")

    private fun textResult(text: String, isError: Boolean) = JsonObject().apply {
        add("content", JsonArray().apply {
            add(JsonObject().apply {
                addProperty("type", "text")
                addProperty("text", text)
            })
        })
        addProperty("isError", isError)
    }

    private fun tool(name: String, description: String, props: Map<String, JsonObject>, required: List<String> = emptyList()) =
        JsonObject().apply {
            addProperty("name", name)
            addProperty("description", description)
            add("inputSchema", JsonObject().apply {
                addProperty("type", "object")
                add("properties", JsonObject().apply { props.forEach { (k, v) -> add(k, v) } })
                if (required.isNotEmpty()) add("required", JsonArray().apply { required.forEach(::add) })
            })
        }

    private fun strProp(description: String) = JsonObject().apply {
        addProperty("type", "string")
        addProperty("description", description)
    }

    companion object {
        private const val CAPTURE_TIMEOUT_SECONDS = 120L
        private val PROJECT_PROP = "project" to JsonObject().apply {
            addProperty("type", "string")
            addProperty("description", "IDE project name. Optional - defaults to the project paused in the debugger.")
        }
    }
}

package com.thusitha.callstackexplorer.mcp

import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.util.concurrency.AppExecutorUtil
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import com.thusitha.callstackexplorer.settings.McpServerSettings
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.URI

/**
 * A minimal Model Context Protocol server (Streamable HTTP transport, JSON
 * responses only) so AI assistants - Claude Code, GitHub Copilot, Cursor, etc.
 * - can read saved snapshots and the live paused debugger state.
 *
 * Bound to the loopback interface only. Requests whose Host or Origin header
 * is not localhost are rejected, which stops web pages from reaching it via
 * DNS rebinding.
 */
@Service(Service.Level.APP)
class McpServerService : Disposable {

    private val log = Logger.getInstance(McpServerService::class.java)
    private val gson = Gson()
    private val tools = McpTools()
    private var server: HttpServer? = null
    private var boundPort: Int = -1

    val isRunning: Boolean @Synchronized get() = server != null
    val endpointUrl: String get() = "http://127.0.0.1:${McpServerSettings.getInstance().settingsState.port}/mcp"

    /** Starts, stops, or restarts the server to match the saved settings. Returns an error message on failure. */
    @Synchronized
    fun syncWithSettings(): String? {
        val settings = McpServerSettings.getInstance().settingsState
        if (!settings.enabled) {
            stop()
            return null
        }
        if (server != null && boundPort == settings.port) return null
        stop()
        return try {
            val http = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), settings.port), 0)
            http.createContext("/mcp") { exchange -> handle(exchange) }
            http.executor = AppExecutorUtil.createBoundedApplicationPoolExecutor("CallStackExplorer MCP", 4)
            http.start()
            server = http
            boundPort = settings.port
            log.info("Call Stack Explorer MCP server listening on $endpointUrl")
            null
        } catch (e: IOException) {
            log.warn("Could not start MCP server on port ${settings.port}", e)
            "Could not start the MCP server on port ${settings.port}: ${e.message}"
        }
    }

    @Synchronized
    fun stop() {
        server?.stop(0)
        server = null
        boundPort = -1
    }

    override fun dispose() = stop()

    // ---- HTTP ----------------------------------------------------------------

    private fun handle(exchange: HttpExchange) {
        try {
            if (!isLocalRequest(exchange)) {
                respond(exchange, 403, null)
                return
            }
            if (exchange.requestMethod != "POST") {
                // No server-initiated SSE stream: tell clients to use POST only.
                exchange.responseHeaders.add("Allow", "POST")
                respond(exchange, 405, null)
                return
            }
            val body = exchange.requestBody.use { it.readNBytes(MAX_BODY_BYTES).toString(Charsets.UTF_8) }
            val parsed = try {
                JsonParser.parseString(body)
            } catch (e: Exception) {
                respond(exchange, 400, gson.toJson(errorResponse(null, -32700, "Parse error")))
                return
            }

            val response: JsonElement? = if (parsed.isJsonArray) {
                val results = JsonArray()
                parsed.asJsonArray.forEach { msg -> dispatch(msg.asJsonObject)?.let(results::add) }
                if (results.isEmpty) null else results
            } else {
                dispatch(parsed.asJsonObject)
            }

            if (response == null) respond(exchange, 202, null) // only notifications/responses were sent
            else respond(exchange, 200, gson.toJson(response))
        } catch (e: Exception) {
            log.warn("MCP request failed", e)
            try {
                respond(exchange, 500, gson.toJson(errorResponse(null, -32603, "Internal error: ${e.message}")))
            } catch (_: IOException) {
            }
        }
    }

    private fun isLocalRequest(exchange: HttpExchange): Boolean {
        val host = exchange.requestHeaders.getFirst("Host")?.substringBeforeLast(':')?.trim('[', ']')
        if (host != null && host !in LOCAL_HOSTS) return false
        val origin = exchange.requestHeaders.getFirst("Origin") ?: return true
        val originHost = try {
            URI(origin).host
        } catch (e: Exception) {
            null
        }
        return originHost != null && originHost.trim('[', ']') in LOCAL_HOSTS
    }

    private fun respond(exchange: HttpExchange, status: Int, json: String?) {
        if (json == null) {
            exchange.sendResponseHeaders(status, -1)
            exchange.close()
            return
        }
        val bytes = json.toByteArray(Charsets.UTF_8)
        exchange.responseHeaders.add("Content-Type", "application/json")
        exchange.sendResponseHeaders(status, bytes.size.toLong())
        exchange.responseBody.use { it.write(bytes) }
    }

    // ---- JSON-RPC ------------------------------------------------------------

    /** Returns the response for a request, or null for notifications and client responses. */
    private fun dispatch(msg: JsonObject): JsonObject? {
        val id = msg.get("id")
        val method = msg.get("method")?.asString ?: return null
        if (id == null || id.isJsonNull) return null // notification, e.g. notifications/initialized
        val params = msg.getAsJsonObject("params") ?: JsonObject()

        return when (method) {
            "initialize" -> result(id, initializeResult(params))
            "ping" -> result(id, JsonObject())
            "tools/list" -> result(id, JsonObject().apply { add("tools", tools.definitions()) })
            "tools/call" -> {
                val name = params.get("name")?.asString
                    ?: return errorResponse(id, -32602, "Missing tool name")
                val args = params.getAsJsonObject("arguments") ?: JsonObject()
                result(id, tools.call(name, args))
            }
            else -> errorResponse(id, -32601, "Method not found: $method")
        }
    }

    private fun initializeResult(params: JsonObject): JsonObject {
        val requested = params.get("protocolVersion")?.asString
        val version = if (requested in SUPPORTED_PROTOCOL_VERSIONS) requested else SUPPORTED_PROTOCOL_VERSIONS.last()
        return JsonObject().apply {
            addProperty("protocolVersion", version)
            add("capabilities", JsonObject().apply { add("tools", JsonObject()) })
            add("serverInfo", JsonObject().apply {
                addProperty("name", "call-stack-explorer")
                addProperty("version", pluginVersion())
            })
            addProperty(
                "instructions",
                "Reads JVM debugger state from the JetBrains IDE. Use get_live_debug_state while the user is " +
                    "paused at a breakpoint; use list_snapshots/get_snapshot for values saved earlier, and " +
                    "compare_live_with_snapshot or compare_snapshots to see what changed between debug runs."
            )
        }
    }

    private fun result(id: JsonElement, result: JsonObject) = JsonObject().apply {
        addProperty("jsonrpc", "2.0")
        add("id", id)
        add("result", result)
    }

    private fun errorResponse(id: JsonElement?, code: Int, message: String) = JsonObject().apply {
        addProperty("jsonrpc", "2.0")
        add("id", id ?: com.google.gson.JsonNull.INSTANCE)
        add("error", JsonObject().apply {
            addProperty("code", code)
            addProperty("message", message)
        })
    }

    private fun pluginVersion(): String =
        com.intellij.ide.plugins.PluginManagerCore
            .getPlugin(com.intellij.openapi.extensions.PluginId.getId("com.thusitha.callstackexplorer"))
            ?.version ?: "dev"

    companion object {
        private const val MAX_BODY_BYTES = 1 shl 20
        private val LOCAL_HOSTS = setOf("localhost", "127.0.0.1", "::1")
        private val SUPPORTED_PROTOCOL_VERSIONS = listOf("2024-11-05", "2025-03-26", "2025-06-18")

        fun getInstance(): McpServerService =
            ApplicationManager.getApplication().getService(McpServerService::class.java)
    }
}

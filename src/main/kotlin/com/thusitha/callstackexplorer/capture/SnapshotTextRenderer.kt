package com.thusitha.callstackexplorer.capture

import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Turns snapshots into stable, indented text - the common format behind the
 * side-by-side compare view, "Copy for AI", and the MCP tools.
 */
object SnapshotTextRenderer {

    /** Display format for capture times, shared by the UI and exports. */
    val TIMESTAMP: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneId.systemDefault())

    /** `{User@1234}` / `"main"@1` - JVM object ids that differ on every run. */
    private val OBJECT_ID = Regex("""([\w$.\]>"])@\d+\b""")

    /** Strips per-run JVM object ids (`{User@1234}` -> `{User}`). */
    fun normalize(s: String): String = OBJECT_ID.replace(s, "$1")

    /**
     * Body only (no id or timestamp), so two snapshots of the same code path
     * line up in a diff. With [ignoreObjectIds], per-run JVM object ids are
     * stripped so only real value changes show up.
     */
    fun render(snapshot: SnapshotModel, ignoreObjectIds: Boolean = false): String {
        val sb = StringBuilder()
        fun clean(s: String) = if (ignoreObjectIds) normalize(s) else s

        for (thread in snapshot.threads) {
            sb.append("Thread: ").append(clean(thread.threadName)).append('\n')
            thread.frames.forEachIndexed { index, frame ->
                sb.append("  #").append(index).append(' ')
                    .append(frame.className).append('.').append(frame.methodName).append("()")
                    .append(':').append(frame.lineNumber).append('\n')
                frame.variables.forEach { appendVariable(sb, it, 3, ::clean) }
            }
        }
        return sb.toString()
    }

    private fun appendVariable(sb: StringBuilder, v: VariableSnapshot, indent: Int, clean: (String) -> String) {
        repeat(indent) { sb.append("  ") }
        sb.append(v.name)
        if (v.type.isNotEmpty()) sb.append(": ").append(clean(v.type))
        sb.append(" = ").append(clean(v.valuePreview).replace('\n', ' ')).append('\n')
        v.children.forEach { appendVariable(sb, it, indent + 1, clean) }
    }

    fun header(snapshot: SnapshotModel): String =
        "${snapshot.label}  (${TIMESTAMP.format(snapshot.capturedAt)}, ${snapshot.effectiveKind.name.lowercase()} snapshot)"

    /** Self-describing Markdown for pasting into an AI chat (Copilot, Claude, ...). */
    fun toMarkdown(snapshot: SnapshotModel): String = buildString {
        append("## Debugger snapshot: ").append(snapshot.label).append('\n')
        append("- Captured: ").append(TIMESTAMP.format(snapshot.capturedAt)).append('\n')
        append("- Kind: ").append(
            if (snapshot.effectiveKind == SnapshotKind.VALUES) "selected object values" else "full call stack with locals"
        ).append('\n')
        snapshot.threads.firstOrNull()?.frames?.firstOrNull()?.let {
            append("- Paused at: `").append(it.className).append('.').append(it.methodName)
                .append("()` line ").append(it.lineNumber).append('\n')
        }
        append("\nFrames are listed innermost first (`#0` is where execution is paused). ")
        append("Variables are indented under their frame; nested fields are indented under their object. ")
        append("Values are the debugger's string previews and may be truncated.\n\n")
        append("```text\n").append(render(snapshot)).append("```\n")
    }
}

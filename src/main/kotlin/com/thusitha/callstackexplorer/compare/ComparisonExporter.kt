package com.thusitha.callstackexplorer.compare

import com.intellij.openapi.util.text.StringUtil
import com.thusitha.callstackexplorer.capture.SnapshotTextRenderer
import java.time.Instant

/**
 * Turns a comparison into shareable evidence: a self-contained HTML page that
 * looks like the debugger's Variables view, or plain text for tickets/chats.
 */
object ComparisonExporter {

    private val TIMESTAMP = SnapshotTextRenderer.TIMESTAMP

    fun toHtml(result: SnapshotDiffResult, onlyChanges: Boolean): String = buildString {
        val b = result.before
        val a = result.after
        append("<!doctype html>\n<html lang=\"en\"><head><meta charset=\"utf-8\">")
        append("<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">")
        append("<title>Value comparison: ").append(esc(b.label)).append(" vs ").append(esc(a.label)).append("</title>")
        append("<style>").append(CSS).append("</style></head><body>")
        append("<h1>Debugger value comparison</h1>")
        append("<table class=\"meta\">")
        append("<tr><th>Before</th><td>").append(esc(b.label)).append("</td><td class=\"time\">")
            .append(TIMESTAMP.format(b.capturedAt)).append("</td></tr>")
        append("<tr><th>After</th><td>").append(esc(a.label)).append("</td><td class=\"time\">")
            .append(TIMESTAMP.format(a.capturedAt)).append("</td></tr>")
        append("</table>")
        append("<p class=\"summary ").append(if (result.hasChanges) "has" else "none").append("\">")
            .append(esc(result.summary())).append("</p>")
        append("<p class=\"legend\"><span class=\"changed\">changed value</span> ")
            .append("<span class=\"old\">previous value</span> ")
            .append("<span class=\"added\">added</span> <span class=\"removed\">removed</span></p>")
        append("<ul class=\"tree\">")
        result.roots.forEach { appendNode(this, it, onlyChanges) }
        append("</ul>")
        append("<footer>Exported from Call Stack Explorer on ").append(TIMESTAMP.format(Instant.now()))
            .append(". Values are the debugger's string previews at capture time.</footer>")
        append("</body></html>\n")
    }

    private fun appendNode(sb: StringBuilder, node: DiffNode, onlyChanges: Boolean) {
        if (onlyChanges && !node.hasChanges) return
        sb.append("<li class=\"").append(node.status.name.lowercase()).append(' ').append(node.kind.name.lowercase()).append("\">")
        sb.append("<span class=\"row\">")
        when (node.kind) {
            DiffKind.THREAD -> sb.append("<span class=\"label\">").append(esc(node.name)).append("</span>")
            DiffKind.FRAME -> {
                sb.append("<span class=\"label\">").append(esc(node.name)).append("</span> ")
                when (node.status) {
                    DiffStatus.CHANGED -> sb.append("<span class=\"changed\">").append(esc(node.afterValue)).append("</span> ")
                        .append("<span class=\"old\">").append(esc(node.beforeValue)).append("</span>")
                    else -> sb.append("<span class=\"type\">").append(esc(node.displayValue)).append("</span>")
                }
            }
            DiffKind.VARIABLE -> {
                sb.append("<span class=\"name\">").append(esc(node.name)).append("</span> = ")
                when (node.status) {
                    DiffStatus.CHANGED -> {
                        sb.append(typeHtml(node.afterType)).append("<span class=\"changed\">").append(esc(node.afterValue)).append("</span>")
                        sb.append(" <span class=\"was\">was</span> ").append("<span class=\"old\">")
                            .append(esc(listOfNotNull(node.beforeType?.takeIf { it.isNotEmpty() }?.let { "{$it}" }, node.beforeValue).joinToString(" ")))
                            .append("</span>")
                    }
                    else -> sb.append(typeHtml(node.displayType)).append("<span class=\"value\">").append(esc(node.displayValue)).append("</span>")
                }
            }
        }
        when (node.status) {
            DiffStatus.ADDED -> sb.append(" <span class=\"tag\">new</span>")
            DiffStatus.REMOVED -> sb.append(" <span class=\"tag\">removed</span>")
            else -> if (node.status == DiffStatus.UNCHANGED && node.changeCount > 0) {
                sb.append(" <span class=\"inside\">").append(node.changeCount).append(" change(s) inside</span>")
            }
        }
        sb.append("</span>")
        val children = node.children.filter { !onlyChanges || it.hasChanges }
        if (children.isNotEmpty()) {
            sb.append("<ul>")
            children.forEach { appendNode(sb, it, onlyChanges) }
            sb.append("</ul>")
        }
        sb.append("</li>")
    }

    private fun typeHtml(type: String?): String =
        if (type.isNullOrEmpty()) "" else "<span class=\"type\">{${esc(type)}}</span> "

    private fun esc(s: String?): String = StringUtil.escapeXmlEntities(s.orEmpty())

    /** Indented text with +/-/~ markers, for pasting into tickets or chats. */
    fun toText(result: SnapshotDiffResult, onlyChanges: Boolean): String = buildString {
        append("Before: ").append(result.before.label).append("  (").append(TIMESTAMP.format(result.before.capturedAt)).append(")\n")
        append("After:  ").append(result.after.label).append("  (").append(TIMESTAMP.format(result.after.capturedAt)).append(")\n")
        append(result.summary()).append("\n\n")
        fun walk(node: DiffNode, depth: Int) {
            if (onlyChanges && !node.hasChanges) return
            val marker = when (node.status) {
                DiffStatus.CHANGED -> "~ "
                DiffStatus.ADDED -> "+ "
                DiffStatus.REMOVED -> "- "
                DiffStatus.UNCHANGED -> "  "
            }
            append("  ".repeat(depth)).append(marker)
            when (node.kind) {
                DiffKind.VARIABLE -> {
                    append(node.name).append(" = ")
                    node.displayType?.takeIf { it.isNotEmpty() }?.let { append('{').append(it).append("} ") }
                    append(node.displayValue.orEmpty().replace('\n', ' '))
                    if (node.status == DiffStatus.CHANGED) {
                        append("    (was ")
                        node.beforeType?.takeIf { it.isNotEmpty() }?.let { append('{').append(it).append("} ") }
                        append(node.beforeValue.orEmpty().replace('\n', ' ')).append(')')
                    }
                }
                else -> {
                    append(node.name)
                    node.displayValue?.let { append("  ").append(it) }
                    if (node.status == DiffStatus.CHANGED) append("    (was ").append(node.beforeValue).append(')')
                }
            }
            append('\n')
            node.children.forEach { walk(it, depth + 1) }
        }
        result.roots.forEach { walk(it, 0) }
    }

    private val CSS = """
        :root { --bg:#ffffff; --fg:#1f1f1f; --muted:#6f737a; --name:#871094; --type:#6f737a; --string:#067d17;
                --changed:#2a5bd7; --changed-bg:#e8efff; --added:#067d17; --added-bg:#e9f7ec; --removed:#c4372d; --removed-bg:#fdecea; --line:#e6e6e6; }
        @media (prefers-color-scheme: dark) {
          :root { --bg:#1e1f22; --fg:#dfe1e5; --muted:#8c8f96; --name:#c77dbb; --type:#8c8f96; --string:#6aab73;
                  --changed:#5e9cf5; --changed-bg:#22314d; --added:#6aab73; --added-bg:#1f3325; --removed:#f0776c; --removed-bg:#40211f; --line:#393b40; }
        }
        body { background:var(--bg); color:var(--fg); font:14px/1.5 -apple-system, "Segoe UI", Roboto, sans-serif; margin:24px; }
        h1 { font-size:18px; margin:0 0 12px; }
        .meta th { text-align:left; padding-right:12px; color:var(--muted); font-weight:600; }
        .meta td { padding-right:16px; } .time { color:var(--muted); }
        .summary { font-weight:600; } .summary.has { color:var(--changed); } .summary.none { color:var(--added); }
        .legend span { margin-right:12px; font-size:12px; }
        .tree, .tree ul { list-style:none; margin:0; padding-left:18px; font:13px/1.7 "JetBrains Mono", Consolas, monospace; }
        .tree { padding-left:0; border-top:1px solid var(--line); padding-top:8px; }
        .row { padding:1px 4px; border-radius:3px; white-space:pre-wrap; word-break:break-word; }
        .frame > .row .label, .thread > .row .label { font-weight:600; }
        .name { color:var(--name); } .type { color:var(--type); }
        .changed { color:var(--changed); font-weight:600; } li.changed > .row { background:var(--changed-bg); }
        .old { color:var(--muted); text-decoration:line-through; } .was { color:var(--muted); font-size:11px; }
        li.added > .row { background:var(--added-bg); } li.added > .row .value { color:var(--added); }
        li.removed > .row { background:var(--removed-bg); } li.removed > .row .value, li.removed > .row .name { color:var(--removed); text-decoration:line-through; }
        .tag { font-size:11px; color:var(--muted); border:1px solid var(--line); border-radius:3px; padding:0 4px; }
        .inside { font-size:11px; color:var(--muted); font-style:italic; }
        footer { margin-top:20px; color:var(--muted); font-size:12px; }
    """.trimIndent()
}

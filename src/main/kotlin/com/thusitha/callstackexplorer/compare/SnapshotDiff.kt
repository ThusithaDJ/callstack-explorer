package com.thusitha.callstackexplorer.compare

import com.intellij.util.diff.Diff
import com.thusitha.callstackexplorer.capture.FrameSnapshot
import com.thusitha.callstackexplorer.capture.SnapshotKind
import com.thusitha.callstackexplorer.capture.SnapshotModel
import com.thusitha.callstackexplorer.capture.SnapshotTextRenderer
import com.thusitha.callstackexplorer.capture.ThreadSnapshot
import com.thusitha.callstackexplorer.capture.VariableSnapshot

enum class DiffStatus { UNCHANGED, CHANGED, ADDED, REMOVED }

enum class DiffKind { THREAD, FRAME, VARIABLE }

/**
 * One row of a comparison tree: a thread, frame or variable present in the
 * "before" snapshot, the "after" snapshot, or both.
 */
class DiffNode(
    val kind: DiffKind,
    val name: String,
    val beforeType: String?,
    val beforeValue: String?,
    val afterType: String?,
    val afterValue: String?,
    val status: DiffStatus,
    val children: List<DiffNode>,
    /** Source location, for frame rows (after side preferred). */
    val frame: FrameSnapshot? = null
) {
    /** True if this row or anything under it differs. */
    val hasChanges: Boolean = status != DiffStatus.UNCHANGED || children.any { it.hasChanges }

    /** Number of differing rows below this one (a whole added/removed subtree counts once). */
    val changeCount: Int = children.sumOf { child ->
        (if (child.status != DiffStatus.UNCHANGED) 1 else 0) +
            (if (child.status == DiffStatus.ADDED || child.status == DiffStatus.REMOVED) 0 else child.changeCount)
    }

    val displayType: String? get() = afterType ?: beforeType
    val displayValue: String? get() = afterValue ?: beforeValue
}

class SnapshotDiffResult(
    val before: SnapshotModel,
    val after: SnapshotModel,
    /** Top-level rows: frames (or threads, if several) for stack snapshots, variables for value snapshots. */
    val roots: List<DiffNode>
) {
    private val counts: Map<DiffStatus, Int> by lazy {
        roots.asSequence().flatMap { walkTopChanges(it) }.groupingBy { it.status }.eachCount()
    }

    val changed: Int get() = counts[DiffStatus.CHANGED] ?: 0
    val added: Int get() = counts[DiffStatus.ADDED] ?: 0
    val removed: Int get() = counts[DiffStatus.REMOVED] ?: 0
    val hasChanges: Boolean = roots.any { it.hasChanges }

    fun summary(): String =
        if (!hasChanges) "No differences" else "$changed changed · $added added · $removed removed"

    /** Flat "path: before → after" list - compact evidence for chats, tickets, and AI tools. */
    fun changeList(): List<String> {
        val out = mutableListOf<String>()
        fun walk(node: DiffNode, path: String) {
            val here = when (node.kind) {
                DiffKind.VARIABLE -> if (path.isEmpty() || path.endsWith(" ")) path + node.name else "$path.${node.name}"
                else -> "${node.name} "
            }
            when (node.status) {
                DiffStatus.CHANGED -> out += if (node.kind == DiffKind.FRAME) {
                    "CHANGED  ${here.trim()}: ${node.beforeValue} → ${node.afterValue}"
                } else {
                    "CHANGED  ${here.trim()}: ${fmt(node.beforeType, node.beforeValue)} → ${fmt(node.afterType, node.afterValue)}"
                }
                DiffStatus.ADDED -> { out += "ADDED    ${here.trim()} = ${fmt(node.afterType, node.afterValue)}"; return }
                DiffStatus.REMOVED -> { out += "REMOVED  ${here.trim()} (was ${fmt(node.beforeType, node.beforeValue)})"; return }
                DiffStatus.UNCHANGED -> {}
            }
            node.children.forEach { walk(it, here) }
        }
        roots.forEach { walk(it, "") }
        return out
    }

    private fun fmt(type: String?, value: String?): String =
        listOfNotNull(type?.takeIf { it.isNotEmpty() }?.let { "{$it}" }, value).joinToString(" ")

    private fun walkTopChanges(node: DiffNode): Sequence<DiffNode> = sequence {
        yield(node)
        if (node.status != DiffStatus.ADDED && node.status != DiffStatus.REMOVED) {
            node.children.forEach { yieldAll(walkTopChanges(it)) }
        }
    }
}

/** Builds a structural comparison of two snapshots, matching variables by name. */
object SnapshotDiff {

    fun compare(before: SnapshotModel, after: SnapshotModel, ignoreObjectIds: Boolean): SnapshotDiffResult {
        val norm: (String?) -> String = { s ->
            val v = s.orEmpty()
            if (ignoreObjectIds) SnapshotTextRenderer.normalize(v) else v
        }
        val valuesOnly = before.effectiveKind == SnapshotKind.VALUES && after.effectiveKind == SnapshotKind.VALUES
        val roots = if (valuesOnly) {
            diffVariables(
                before.threads.flatMap { t -> t.frames.flatMap { it.variables } },
                after.threads.flatMap { t -> t.frames.flatMap { it.variables } },
                norm
            )
        } else {
            diffThreads(before.threads, after.threads, norm)
        }
        return SnapshotDiffResult(before, after, roots)
    }

    // ---- threads & frames ------------------------------------------------------

    private fun diffThreads(before: List<ThreadSnapshot>, after: List<ThreadSnapshot>, norm: (String?) -> String): List<DiffNode> {
        // The common case - one paused thread on each side - pairs them even if
        // the thread names differ (e.g. http-nio-exec-1 vs exec-4), and skips the thread level.
        if (before.size <= 1 && after.size <= 1) {
            return diffFrames(before.firstOrNull()?.frames.orEmpty(), after.firstOrNull()?.frames.orEmpty(), norm)
        }
        val afterByName = after.associateBy { norm(it.threadName) }
        val beforeNames = before.map { norm(it.threadName) }.toSet()
        val rows = before.map { b ->
            val a = afterByName[norm(b.threadName)]
            if (a == null) threadNode(b.threadName, DiffStatus.REMOVED, diffFrames(b.frames, emptyList(), norm))
            else threadNode(a.threadName, DiffStatus.UNCHANGED, diffFrames(b.frames, a.frames, norm))
        }
        val addedRows = after.filter { norm(it.threadName) !in beforeNames }
            .map { threadNode(it.threadName, DiffStatus.ADDED, diffFrames(emptyList(), it.frames, norm)) }
        return rows + addedRows
    }

    private fun threadNode(name: String, status: DiffStatus, children: List<DiffNode>) =
        DiffNode(DiffKind.THREAD, name, null, null, null, null, status, children)

    /** Aligns the two stacks by `Class.method` so an extra frame doesn't shift every row. */
    private fun diffFrames(before: List<FrameSnapshot>, after: List<FrameSnapshot>, norm: (String?) -> String): List<DiffNode> {
        val keysBefore = before.map { "${it.className}.${it.methodName}" }.toTypedArray()
        val keysAfter = after.map { "${it.className}.${it.methodName}" }.toTypedArray()
        val changes = try {
            Diff.buildChanges(keysBefore, keysAfter)?.toList().orEmpty()
        } catch (e: Exception) {
            null
        }
        val rows = mutableListOf<DiffNode>()
        if (changes == null) { // too big to align: fall back to index pairing
            val n = maxOf(before.size, after.size)
            for (i in 0 until n) rows += frameNode(before.getOrNull(i), after.getOrNull(i), norm)
            return rows
        }
        var i = 0
        var j = 0
        for (c in changes) {
            while (i < c.line0 && j < c.line1) rows += frameNode(before[i++], after[j++], norm)
            repeat(c.deleted) { rows += frameNode(before[i++], null, norm) }
            repeat(c.inserted) { rows += frameNode(null, after[j++], norm) }
        }
        while (i < before.size && j < after.size) rows += frameNode(before[i++], after[j++], norm)
        while (i < before.size) rows += frameNode(before[i++], null, norm)
        while (j < after.size) rows += frameNode(null, after[j++], norm)
        return rows
    }

    private fun frameNode(before: FrameSnapshot?, after: FrameSnapshot?, norm: (String?) -> String): DiffNode {
        val f = after ?: before!!
        val name = "${f.methodName}(), ${f.className.substringAfterLast('.')}"
        val status = when {
            before == null -> DiffStatus.ADDED
            after == null -> DiffStatus.REMOVED
            before.lineNumber != after.lineNumber -> DiffStatus.CHANGED
            else -> DiffStatus.UNCHANGED
        }
        val children = when (status) {
            DiffStatus.ADDED -> diffVariables(emptyList(), after!!.variables, norm)
            DiffStatus.REMOVED -> diffVariables(before!!.variables, emptyList(), norm)
            else -> diffVariables(before!!.variables, after!!.variables, norm)
        }
        return DiffNode(
            DiffKind.FRAME, name,
            null, before?.let { "line ${it.lineNumber}" },
            null, after?.let { "line ${it.lineNumber}" },
            status, children, frame = f
        )
    }

    // ---- variables -------------------------------------------------------------

    private fun diffVariables(before: List<VariableSnapshot>, after: List<VariableSnapshot>, norm: (String?) -> String): List<DiffNode> {
        val beforeKeyed = keyed(before)
        val afterKeyed = keyed(after)
        val afterMap = afterKeyed.toMap()
        val beforeKeys = beforeKeyed.map { it.first }.toSet()

        val rows = beforeKeyed.map { (key, b) -> variableNode(b, afterMap[key], norm) }
        val addedRows = afterKeyed.filter { it.first !in beforeKeys }.map { variableNode(null, it.second, norm) }
        return rows + addedRows
    }

    /** `name#n` keys, so shadowed / repeated names still pair up in order. */
    private fun keyed(vars: List<VariableSnapshot>): List<Pair<String, VariableSnapshot>> {
        val seen = HashMap<String, Int>()
        return vars.map { v ->
            val n = seen.merge(v.name, 1, Int::plus)!!
            "${v.name}#$n" to v
        }
    }

    private fun variableNode(before: VariableSnapshot?, after: VariableSnapshot?, norm: (String?) -> String): DiffNode {
        val status = when {
            before == null -> DiffStatus.ADDED
            after == null -> DiffStatus.REMOVED
            norm(before.valuePreview) != norm(after.valuePreview) || norm(before.type) != norm(after.type) -> DiffStatus.CHANGED
            else -> DiffStatus.UNCHANGED
        }
        val bc = before?.children.orEmpty()
        val ac = after?.children.orEmpty()
        val children = when {
            status == DiffStatus.ADDED || status == DiffStatus.REMOVED -> diffVariables(bc, ac, norm)
            bc.isNotEmpty() && ac.isNotEmpty() -> diffVariables(bc, ac, norm)
            // One side wasn't expanded that deep (capture depth). Only show the other side's
            // fields when the object itself changed (e.g. null -> object); otherwise we'd
            // report fields as added/removed that simply weren't captured.
            status == DiffStatus.CHANGED -> diffVariables(bc, ac, norm)
            else -> emptyList()
        }
        val v = after ?: before!!
        return DiffNode(
            DiffKind.VARIABLE, v.name,
            before?.type, before?.valuePreview,
            after?.type, after?.valuePreview,
            status, children
        )
    }
}

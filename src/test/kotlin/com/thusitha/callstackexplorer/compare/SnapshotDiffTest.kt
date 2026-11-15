package com.thusitha.callstackexplorer.compare

import com.thusitha.callstackexplorer.capture.FrameSnapshot
import com.thusitha.callstackexplorer.capture.SnapshotKind
import com.thusitha.callstackexplorer.capture.SnapshotModel
import com.thusitha.callstackexplorer.capture.ThreadSnapshot
import com.thusitha.callstackexplorer.capture.VariableSnapshot
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Instant

class SnapshotDiffTest {

    private fun v(name: String, value: String, type: String = "", vararg children: VariableSnapshot) =
        VariableSnapshot(name, type, value, children.toList())

    private fun values(vararg vars: VariableSnapshot) = SnapshotModel(
        "id", Instant.EPOCH, "values",
        listOf(ThreadSnapshot("main", listOf(FrameSnapshot("com.x.Service", "handle", null, 10, vars.toList())))),
        SnapshotKind.VALUES
    )

    private fun stack(vararg frames: FrameSnapshot) = SnapshotModel(
        "id", Instant.EPOCH, "stack", listOf(ThreadSnapshot("main", frames.toList())), SnapshotKind.STACK
    )

    private fun frame(method: String, line: Int, vararg vars: VariableSnapshot) =
        FrameSnapshot("com.x.Service", method, null, line, vars.toList())

    @Test
    fun `changed nested field is reported and parent shows changes inside`() {
        val before = values(v("user", "", "User@11", v("name", "\"bob\"", "String"), v("age", "30", "int")))
        val after = values(v("user", "", "User@99", v("name", "\"alice\"", "String"), v("age", "30", "int")))

        val result = SnapshotDiff.compare(before, after, ignoreObjectIds = true)

        val user = result.roots.single()
        assertEquals(DiffStatus.UNCHANGED, user.status) // only the object id differed - ignored
        assertEquals(1, user.changeCount)
        val name = user.children.first { it.name == "name" }
        assertEquals(DiffStatus.CHANGED, name.status)
        assertEquals("\"bob\"", name.beforeValue)
        assertEquals("\"alice\"", name.afterValue)
        assertEquals("1 changed · 0 added · 0 removed", result.summary())
        assertEquals(listOf("CHANGED  user.name: {String} \"bob\" → {String} \"alice\""), result.changeList())
    }

    @Test
    fun `object ids count as changes when not ignored`() {
        val result = SnapshotDiff.compare(values(v("user", "", "User@11")), values(v("user", "", "User@99")), ignoreObjectIds = false)
        assertEquals(DiffStatus.CHANGED, result.roots.single().status)
    }

    @Test
    fun `added and removed variables are counted once per subtree`() {
        val before = values(v("a", "1", "int"), v("old", "", "Obj", v("x", "1", "int"), v("y", "2", "int")))
        val after = values(v("a", "1", "int"), v("fresh", "", "Obj", v("z", "3", "int")))

        val result = SnapshotDiff.compare(before, after, ignoreObjectIds = true)

        assertEquals(1, result.removed)
        assertEquals(1, result.added)
        assertEquals(0, result.changed)
        assertEquals(DiffStatus.REMOVED, result.roots.first { it.name == "old" }.children.first().status)
    }

    @Test
    fun `fields missing only because of capture depth are not reported`() {
        val shallow = values(v("user", "", "User@1"))
        val deep = values(v("user", "", "User@2", v("name", "\"bob\"", "String")))
        val result = SnapshotDiff.compare(shallow, deep, ignoreObjectIds = true)
        assertFalse(result.hasChanges)
        assertEquals("No differences", result.summary())
    }

    @Test
    fun `extra frame does not shift the comparison of the frames below it`() {
        val before = stack(frame("handle", 10, v("i", "1", "int")), frame("main", 5))
        val after = stack(frame("validate", 42), frame("handle", 10, v("i", "2", "int")), frame("main", 5))

        val result = SnapshotDiff.compare(before, after, ignoreObjectIds = true)

        assertEquals(listOf(DiffStatus.ADDED, DiffStatus.UNCHANGED, DiffStatus.UNCHANGED), result.roots.map { it.status })
        val handle = result.roots[1]
        assertTrue(handle.hasChanges)
        assertEquals(DiffStatus.CHANGED, handle.children.single().status)
    }

    @Test
    fun `line change in the same frame is a frame-level change`() {
        val result = SnapshotDiff.compare(stack(frame("handle", 10)), stack(frame("handle", 12)), ignoreObjectIds = true)
        val f = result.roots.single()
        assertEquals(DiffStatus.CHANGED, f.status)
        assertEquals("line 10", f.beforeValue)
        assertEquals("line 12", f.afterValue)
    }

    @Test
    fun `html export escapes values and marks changes`() {
        val result = SnapshotDiff.compare(values(v("s", "\"<a>\"", "String")), values(v("s", "\"<b>\"", "String")), true)
        val html = ComparisonExporter.toHtml(result, onlyChanges = false)
        assertTrue(html.contains("&lt;b&gt;"))
        assertFalse(html.contains("\"<b>\""))
        assertTrue(html.contains("class=\"changed variable\""))
        val text = ComparisonExporter.toText(result, onlyChanges = true)
        assertTrue(text.contains("~ s = {String} \"<b>\"    (was {String} \"<a>\")"), text)
    }
}

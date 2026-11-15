package com.thusitha.callstackexplorer.capture

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Instant

class SnapshotTextRendererTest {

    private fun snapshot(userName: String, objectId: Int) = SnapshotModel(
        id = "id-$objectId",
        capturedAt = Instant.parse("2026-01-01T00:00:00Z"),
        label = "test",
        threads = listOf(
            ThreadSnapshot(
                "\"main\"@1 in group \"main\": RUNNING",
                listOf(
                    FrameSnapshot(
                        "com.example.Service", "handle", null, 42,
                        listOf(
                            VariableSnapshot(
                                "user", "User", "{User@$objectId}",
                                listOf(VariableSnapshot("name", "String", "\"$userName\""))
                            )
                        )
                    )
                )
            )
        ),
        kind = SnapshotKind.VALUES
    )

    @Test
    fun `object ids are stripped only when requested`() {
        val s = snapshot("bob", 1234)
        assertTrue(SnapshotTextRenderer.render(s).contains("{User@1234}"))
        val cleaned = SnapshotTextRenderer.render(s, ignoreObjectIds = true)
        assertTrue(cleaned.contains("{User}"))
        assertFalse(cleaned.contains("@1234"))
        assertFalse(cleaned.contains("\"main\"@1"))
    }

    @Test
    fun `identical runs with different object ids produce no diff`() {
        val a = SnapshotTextRenderer.render(snapshot("bob", 1), ignoreObjectIds = true)
        val b = SnapshotTextRenderer.render(snapshot("bob", 999), ignoreObjectIds = true)
        assertEquals(a, b)
    }

    @Test
    fun `changed nested field changes the rendered text`() {
        val a = SnapshotTextRenderer.render(snapshot("bob", 1), ignoreObjectIds = true)
        val b = SnapshotTextRenderer.render(snapshot("alice", 2), ignoreObjectIds = true)
        assertTrue(a.contains("\n        name: String = \"bob\"\n"), a)
        assertTrue(b.contains("\n        name: String = \"alice\"\n"), b)
    }

    @Test
    fun `old snapshots without kind default to stack`() {
        val legacy = snapshot("bob", 1).copy(kind = null)
        assertEquals(SnapshotKind.STACK, legacy.effectiveKind)
    }
}

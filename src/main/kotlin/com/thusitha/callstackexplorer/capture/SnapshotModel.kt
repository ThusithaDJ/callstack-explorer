package com.thusitha.callstackexplorer.capture

import java.time.Instant

/**
 * Plain data classes with no IntelliJ platform types, so a snapshot can be
 * serialized and later loaded/browsed without any live debugger session,
 * JDI reference, or VirtualFile behind it.
 *
 * Note: Gson bypasses Kotlin default values when a field is missing from the
 * JSON, so any field added after 0.1.0 must be nullable (older snapshot files
 * load with it as null).
 */
data class SnapshotModel(
    val id: String,
    val capturedAt: Instant,
    val label: String,
    val threads: List<ThreadSnapshot>,
    /** null for snapshots written before kinds existed - treat as [SnapshotKind.STACK]. */
    val kind: SnapshotKind? = SnapshotKind.STACK
) {
    val effectiveKind: SnapshotKind get() = kind ?: SnapshotKind.STACK
}

enum class SnapshotKind {
    /** Whole call stack of the paused thread, every frame with its locals. */
    STACK,

    /** Only the values the user picked in the debugger's Variables view. */
    VALUES
}

data class ThreadSnapshot(
    val threadName: String,
    val frames: List<FrameSnapshot>
)

data class FrameSnapshot(
    val className: String,
    val methodName: String,
    val fileUrl: String?,
    /** 1-based line number for display; subtract 1 before handing to OpenFileDescriptor. */
    val lineNumber: Int,
    val variables: List<VariableSnapshot>
)

data class VariableSnapshot(
    val name: String,
    val type: String,
    val valuePreview: String,
    val children: List<VariableSnapshot> = emptyList()
)

/** Cheap summary used to populate the tool window list without deserializing every snapshot. */
data class SnapshotSummary(
    val id: String,
    val label: String,
    val capturedAt: Instant,
    val kind: SnapshotKind? = SnapshotKind.STACK
) {
    val effectiveKind: SnapshotKind get() = kind ?: SnapshotKind.STACK
}

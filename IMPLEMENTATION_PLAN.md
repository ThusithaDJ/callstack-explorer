# CallStackExplorer — IntelliJ Plugin Implementation Plan

## 1. Problem Statement

When remote debugging an application, the debug session can time out or drop
(network blip, server restart, session lease expiry). Today that means:

1. The call stack, locals, and pause context are gone the moment the session dies.
2. You have to restart the remote server, re-attach, reproduce the conditions,
   and re-hit the same breakpoint to see that state again.

**Goal:** capture the call stack (and enough variable state to be useful) the
moment a breakpoint is hit, persist it independently of the debug session's
lifetime, and let it be browsed/navigated later — even after the JVM on the
other end is long gone.

## 2. Scope (MVP)

In scope:
- Manual "Save Call Stack" action, available whenever a debug session is suspended.
- Automatic capture on every breakpoint pause (toggleable), so a snapshot exists
  even if you forget to click save before the connection drops.
- Persisted snapshots: thread(s), frames (class, method, file, line), and
  locals/`this` captured as string representations.
- A dedicated tool window listing saved snapshots in a tree (snapshot → thread →
  frame → variables).
- Click a frame node → open the source file at that line in the editor.
- Snapshots survive IDE restart (stored on disk, not just in memory).

Out of scope for MVP (candidate follow-ups):
- Re-hydrating a saved snapshot into a *live* debugger view (can't — JDI object
  references don't survive the JVM dying).
- Diffing two snapshots.
- Deep object graph capture beyond one level (configurable depth later).
- Non-JVM languages (Python/Node debugger frames) — architecture allows it later
  since capture is behind an abstraction, but MVP targets JVM/Java debugging only.

## 3. High-Level Architecture

```
┌─────────────────────────────────────────────────────────────────┐
│                         IntelliJ Platform                        │
│                                                                   │
│  XDebugSession ──sessionPaused()──▶ CallStackCaptureListener     │
│                                            │                     │
│                                            ▼                     │
│                                    StackSnapshotBuilder           │
│                                    (walks frames via JDI)         │
│                                            │                     │
│                                            ▼                     │
│                                    SnapshotModel (POJO/data class)│
│                                            │                     │
│                              ┌─────────────┴─────────────┐        │
│                              ▼                           ▼        │
│                     SnapshotStorageService      SavedStacksToolWindow
│                     (serialize to disk,          (tree UI, reads   
│                      project-scoped)              from storage svc)
│                                                          │
│                                                          ▼
│                                                  Click frame node
│                                                          │
│                                                          ▼
│                                          OpenFileDescriptor.navigate()
└─────────────────────────────────────────────────────────────────┘
```

## 4. Component Breakdown

### 4.1 `plugin.xml` — extension points to register

```xml
<idea-plugin>
  <id>com.thusitha.callstackexplorer</id>
  <name>Call Stack Explorer</name>
  <vendor email="thusitha.blade@gmail.com">Thusitha</vendor>

  <depends>com.intellij.modules.java</depends>

  <extensions defaultExtensionNs="com.intellij">
    <!-- Listens for every debug session start, attaches our pause listener -->
    <xdebugger.debugProcessListener implementation="com.thusitha.callstackexplorer.listener.DebugProcessAttachListener"/>

    <!-- Project-level persistent storage for snapshots -->
    <projectService serviceImplementation="com.thusitha.callstackexplorer.storage.SnapshotStorageService"/>

    <!-- Tool window showing saved snapshots -->
    <toolWindow id="Call Stack Explorer"
                anchor="bottom"
                factoryClass="com.thusitha.callstackexplorer.ui.CallStackToolWindowFactory"
                icon="/icons/callstack.svg"/>
  </extensions>

  <actions>
    <action id="CallStackExplorer.SaveStack"
            class="com.thusitha.callstackexplorer.action.SaveStackAction"
            text="Save Call Stack"
            description="Save the current debugger call stack for later viewing"
            icon="/icons/saveStack.svg">
      <add-to-group group-id="XDebugger.ToolbarNewUI" anchor="last"/>
      <add-to-group group-id="XDebugger.ToolbarOldUI" anchor="last"/>
    </action>
  </actions>
</idea-plugin>
```

Note: register in both `XDebugger.ToolbarNewUI` and the legacy group so the
button shows up regardless of which debugger toolbar UI the IDE version uses.

### 4.2 Capture pipeline

**`DebugProcessAttachListener`** (implements `XDebugProcessListener` /
registered via `XDebuggerManagerListener.processStarted`)
- Fires when any debug session starts.
- Attaches an `XDebugSessionListener` to the new session.
- If "auto-capture on breakpoint" is enabled (see settings, §4.5), the
  `sessionPaused()` callback triggers capture automatically.

**`StackSnapshotBuilder`**
- Input: `XDebugSession` at the moment it's paused.
- Gets `session.suspendContext.activeExecutionStack`.
- Calls `executionStack.computeStackFrames(0, callback)` to get all `XStackFrame`s
  (this is async — use a `Semaphore`/`CompletableFuture` bridge with a short
  timeout, since we're doing this on pause, not on a background thread by default).
- For each frame, if it's a `JavaStackFrame`:
  - `frame.getDescriptor().getSourcePosition()` → file + line.
  - `frame.stackFrameProxy.location().declaringType().name()` → FQCN.
  - `frame.stackFrameProxy.location().method().name()` → method name.
  - `frame.computeChildren(XCompositeNode)` → variables; for each child, resolve
    with `computePresentation` to get a display string (this is the part that
    needs care — see §5.1 below for the async/threading gotcha).
- Output: a `SnapshotModel` tree (plain data classes, no IntelliJ platform types),
  so it can be serialized independent of any live debugger objects.

```kotlin
data class SnapshotModel(
    val id: String,                // UUID
    val capturedAt: Instant,
    val label: String,             // e.g. breakpoint location or user note
    val threads: List<ThreadSnapshot>
)

data class ThreadSnapshot(
    val threadName: String,
    val frames: List<FrameSnapshot>
)

data class FrameSnapshot(
    val className: String,
    val methodName: String,
    val fileUrl: String?,          // VirtualFile url, resolved lazily on click
    val lineNumber: Int,           // 1-based for display; store 0-based internally to match OpenFileDescriptor
    val variables: List<VariableSnapshot>
)

data class VariableSnapshot(
    val name: String,
    val type: String,
    val valuePreview: String,      // toString() at capture time — this is a snapshot, not a live ref
    val children: List<VariableSnapshot> = emptyList() // shallow, depth-limited
)
```

### 4.3 Storage

**`SnapshotStorageService`** (project service, not application service — stacks
are meaningful per-project since they reference project source files)

- Backing store: JSON files under `<project>/.idea/callStackExplorer/snapshots/`,
  one file per snapshot (`<id>.json`), plus an `index.json` with
  id/label/timestamp for fast listing without deserializing every snapshot.
- Use `kotlinx.serialization` or Gson (Gson is already bundled in the platform,
  simpler dependency story) for (de)serialization of the `SnapshotModel` tree.
- API surface:
  ```kotlin
  interface SnapshotStorageService {
      fun save(snapshot: SnapshotModel)
      fun list(): List<SnapshotSummary>       // cheap, from index
      fun load(id: String): SnapshotModel?    // full tree, lazy
      fun delete(id: String)
      fun rename(id: String, newLabel: String)
  }
  ```
- Add `.idea/callStackExplorer/` to the project's VCS-ignore suggestion (or make
  it configurable — some teams may actually want to commit interesting captured
  stacks for bug reports; don't force-ignore it, just default-suggest).

### 4.4 UI

**`SaveStackAction : AnAction`**
- `update()`: enabled only when
  `XDebuggerManager.getInstance(project).currentSession` is non-null and
  `session.isSuspended`.
- `actionPerformed()`: run `StackSnapshotBuilder`, then optionally prompt for a
  label (simple `Messages.showInputDialog`, default to
  `"<ClassName>.<method>:<line> — <timestamp>"`), then
  `SnapshotStorageService.save(...)`, then refresh the tool window if open.

**`CallStackToolWindowFactory` / `SavedStacksPanel`**
- `Tree` backed by a custom `TreeModel`:
  - Root → `SnapshotSummary` nodes (label, timestamp) → `ThreadSnapshot` nodes →
    `FrameSnapshot` nodes (rendered as `ClassName.method():line`) →
    `VariableSnapshot` leaf nodes (`name: type = valuePreview`).
- `TreeCellRenderer` reusing `AllIcons.Nodes.Class`, `AllIcons.Nodes.Method`,
  `AllIcons.Debugger.Value` for visual parity with the native Frames panel.
- Double-click / Enter on a `FrameSnapshot` node → navigate (see §4.5).
- Toolbar on the tool window itself: refresh, delete snapshot, rename snapshot,
  "clear all".
- Lazy-load: only call `storage.load(id)` when a snapshot node is expanded, not
  when the list is first populated (keeps the tree snappy with many snapshots).

**Navigation on click**
```kotlin
fun navigateToFrame(project: Project, frame: FrameSnapshot) {
    val psiClass = JavaPsiFacade.getInstance(project)
        .findClass(frame.className, GlobalSearchScope.allScope(project))
    val vFile = psiClass?.containingFile?.virtualFile
    if (vFile != null) {
        OpenFileDescriptor(project, vFile, frame.lineNumber - 1, 0).navigate(true)
    } else {
        // fall back: no source found (e.g. library without sources) — show a
        // notification rather than silently failing.
        Notifications.Bus.notify(Notification(..., "Source not found for ${frame.className}", WARNING))
    }
}
```
- Distinguish "found actual source" vs "resolved to decompiled bytecode" (check
  `psiClass.containingFile is ClsFileImpl` or similar) and render that frame
  node with a distinct icon/tooltip so it's clear you're not looking at your
  own code.

### 4.5 Settings

A small `Configurable` (Settings → Tools → Call Stack Explorer):
- ☐ Auto-capture on every breakpoint pause (off by default — could be noisy/slow
  for hot loops; user opts in).
- Max variable capture depth (default 1 — just top-level fields, not full graphs).
- Max snapshots to retain (default 50, oldest pruned first) — prevents unbounded
  disk growth from auto-capture.

## 5. Key Technical Risks / Things to Validate Early

### 5.1 Async variable computation on the pause thread
`XStackFrame.computeChildren()` is asynchronous by design (variable values may
require actual JDI round-trips). Doing this synchronously on `sessionPaused()`
risks deadlocking the debugger's own dispatch thread. Approach:
- Do the frame *walk* (class/method/line — synchronous, cheap) immediately in
  `sessionPaused()`.
- Do variable resolution in a background task (`ProgressManager.run` or a
  coroutine on `Dispatchers.Default`), using `XDebuggerUtil` helpers or a
  bounded `CountDownLatch` per frame with a timeout (e.g. 2s) so a slow/huge
  object graph doesn't hang capture indefinitely.
- **Validate this first** — it's the highest-risk part of the whole plan.
  Everything else (storage, UI, navigation) is boilerplate IntelliJ Platform
  work; this is the one part that touches debugger internals under
  concurrency.

### 5.2 Frame availability window
By the time `sessionPaused()` fires, are all frames for all threads actually
computed, or just the current one? `computeStackFrames` may need to be called
explicitly per execution stack, and multi-threaded apps will have multiple
`XExecutionStack`s. Decide MVP scope: capture only the thread that hit the
breakpoint, or all suspended threads? (Recommend: just the paused thread for
MVP — cheaper, less noisy, matches what you'd actually want 95% of the time.)

### 5.3 Auto-capture performance
If auto-capture-on-every-breakpoint is on and a breakpoint is hit thousands of
times (e.g., inside a loop), naive capture will hammer disk I/O and JDI. Mitigate
with: debounce (skip if last auto-capture was <N ms ago for same location), and
the retention cap from §4.5.

### 5.4 Value `toString()` side effects
Calling `toString()` on captured objects during capture can itself execute
user code in the remote JVM (that's just how JDI works — same as IntelliJ's own
variable view). Nothing to fix here, just don't be surprised if capture is
occasionally slow for objects with expensive `toString()`.

## 6. Milestones

| # | Milestone | Deliverable |
|---|-----------|-------------|
| 1 | Skeleton plugin | `plugin.xml`, Gradle IntelliJ Platform plugin setup, runs in a sandbox IDE, one no-op action registered on the debug toolbar |
| 2 | Frame capture (no variables) | `SaveStackAction` captures class/method/file/line for all frames of the paused thread, prints to log — validates the JDI walk works |
| 3 | Variable capture | Add async variable resolution with depth limit + timeout (de-risks §5.1) |
| 4 | Storage | `SnapshotStorageService` persists to `.idea/callStackExplorer/`, survives IDE restart |
| 5 | Tool window (read-only tree) | Lists snapshots, expandable to frames/variables, no navigation yet |
| 6 | Click-to-navigate | Double-click a frame → opens file at line via `OpenFileDescriptor` |
| 7 | Auto-capture + settings | `Configurable` for auto-capture toggle, depth, retention cap |
| 8 | Polish | Icons, decompiled-source indicator, delete/rename snapshot, notifications for edge cases |

## 7. Project Setup

- Use the [IntelliJ Platform Gradle Plugin (2.x)](https://plugins.jetbrains.com/docs/intellij/tools-gradle-intellij-plugin.html)
  template (`gradle-intellij-plugin` or the newer `org.jetbrains.intellij.platform`).
- Target: IntelliJ IDEA Community or Ultimate (depends on whether you want to
  support the JS/Python debuggers later — Community + `com.intellij.modules.java`
  is enough for the JVM-only MVP).
- Min supported IDE version: pick current LTS (2024.2+) unless you need to
  support older installs.
- Run via `./gradlew runIde` for a sandbox instance to test against.

## 8. Suggested Package Layout

```
com.thusitha.callstackexplorer
├── listener/
│   └── DebugProcessAttachListener.kt
├── capture/
│   ├── StackSnapshotBuilder.kt
│   └── SnapshotModel.kt        (data classes from §4.2)
├── storage/
│   └── SnapshotStorageService.kt
├── action/
│   └── SaveStackAction.kt
├── ui/
│   ├── CallStackToolWindowFactory.kt
│   ├── SavedStacksPanel.kt
│   └── SnapshotTreeModel.kt
├── navigation/
│   └── FrameNavigator.kt
└── settings/
    └── CallStackExplorerConfigurable.kt
```

## 9. Next Step

Start at Milestone 1–2: get the skeleton plugin running in a sandbox IDE and
prove out the frame walk (class/method/file/line, no variables yet) on a real
breakpoint hit. That validates the riskiest assumption — that you can reliably
read `XStackFrame`/JDI state inside `sessionPaused()` — before investing in
storage and UI.

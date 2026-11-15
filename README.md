# Call Stack Explorer

**Version 1.0.0** · IntelliJ IDEA 2024.2+ · [Changelog](#changelog)

An IntelliJ IDEA plugin that captures a debugger's call stack — frames and
variables — the moment a breakpoint is hit, and persists it independently of
the debug session's lifetime. Snapshots stay browsable in a dedicated tool
window even after the debugged JVM is gone (session dropped, remote server
restarted, lease expired, etc.).

## Features

- **Save Call Stack** action, available whenever a debug session is suspended.
- Automatic capture on breakpoint pause (toggleable in settings), so a
  snapshot exists even if you forget to click save before the connection
  drops.
- Persists thread(s), frames (class, method, file, line), and locals/`this`
  as string previews, with a configurable capture depth.
- **Call Stack Explorer** tool window laid out like the Debug tool window,
  with a **Call Stacks** tab (Snapshots | Frames | Variables) and a **Values**
  tab (Snapshots | Variables). Values are shown the way the debugger shows
  them: `name = {Type@123} value`.
- Double-click (or Enter on) a frame to jump to that source line, highlighted
  like the debugger's execution point.
- Snapshots survive IDE restart — stored on disk per-project under
  `.idea/callStackExplorer/`.
- **Save Value to Call Stack Explorer**: right-click variables in the
  debugger's Variables view to save just those objects (captured deeper than a
  full-stack snapshot).
- **Compare**: compare two snapshots, or a snapshot against the live paused
  debugger (e.g. the same variables in your next debug run). The result opens
  in its own closable tab, as a variables tree like the debugger's: changed values in
  the debugger's blue with the previous value struck through, added and
  removed values marked. JVM object ids like `@1234` are ignored by default.
- **Evidence export**: save a comparison as a self-contained HTML page (looks
  like the debugger, light/dark aware) or copy it as text to share.
- **Progress notice**: if a capture takes a while, a notice in the
  bottom-right corner says what's happening; captures can be cancelled.
- **AI assistant access**: *Copy for AI Assistant* copies snapshots as
  Markdown, and an opt-in local MCP server lets Claude Code, GitHub Copilot
  and other MCP clients read live debugger values and saved snapshots.
- **Error reporting**: if the plugin itself fails, the IDE's error dialog
  offers to send the report to the developer (via JetBrains Marketplace).

## Requirements

- IntelliJ IDEA 2024.2+ (Community or Ultimate) with the Java plugin enabled.
  Per JetBrains' June 2026 figures, IntelliJ IDEA 2025.1 and newer account
  for ~76% of users, so 2024.2+ comfortably covers 80%+; the plugin declares
  no upper bound and is verified against 2024.2 and 2026.1.
- JDK 21 to build the plugin itself.

## Building

```bash
./gradlew buildPlugin
```

The packaged plugin zip is produced under `build/distributions/`
(`CallStackExplorer-1.0.0.zip`). The version is set by `pluginVersion` in
`gradle.properties`.

Run the unit tests with `./gradlew test`.

## Running in a sandbox IDE

```bash
./gradlew runIdeLatest   # sandbox on a locally installed newer IDE (default: IntelliJ IDEA 2026.1)
./gradlew runIde         # sandbox on 2024.2, the oldest supported version
```

Each launches a separate, disposable IntelliJ instance (under
`build/idea-sandbox/`) with the plugin installed, so you can test it without
touching your real IDE installation. Point `runIdeLatest` at another install
with `-PrunIdeLocalPath=<IDE folder>`. (The 2024.2 sandbox logs a harmless
error from its bundled Gradle plugin, which can't parse Java 25.) The sandbox
is a second full IDE, so close other memory-heavy apps if it gets shut down.

## Verifying compatibility

`./gradlew verifyPlugin` checks against 2024.2. Also check the newest IDE
before publishing (Gradle plugin 2.9.0 can't do this via `local(...)`):

```bash
java -jar ~/.gradle/caches/modules-2/files-2.1/org.jetbrains.intellij.plugins/verifier-cli/<version>/<hash>/verifier-cli-<version>-all.jar   check-plugin build/distributions/CallStackExplorer-1.0.0.zip "<path to newest IDE>"
```

## Installing

Install the built plugin zip via
**Settings/Preferences → Plugins → ⚙️ → Install Plugin from Disk...**.

## Publishing

Create a Permanent Token at <https://plugins.jetbrains.com/author/me/tokens>, then:

```bash
PUBLISH_TOKEN=<token> ./gradlew publishPlugin
```

Error reports from Marketplace installs appear under the plugin's
**Exceptions** and **Freezes** tabs on JetBrains Marketplace.

## Usage

1. Set a breakpoint and start a debug session as usual.
2. When the breakpoint is hit, click **Save Call Stack** in the debugger
   toolbar's overflow menu (or let auto-capture do it, if enabled).
3. Open the **Call Stack Explorer** tool window (bottom toolbar). In the
   **Call Stacks** tab pick a snapshot, then a frame, to see its variables.
4. Double-click a frame to navigate to that source location. **Remove
   Highlight** clears the highlighted line.

### Managing snapshots

- **Rename** a snapshot, **Delete** it (or press Delete), or **Clear All** in
  the current tab. Clear All only affects that tab, so clearing call stacks
  keeps your saved values.
- Only the newest snapshots are kept (50 by default); older ones are removed
  automatically.

### Saving and comparing values across debug runs

1. While paused, select one or more variables in the debugger's Variables
   view, right-click → **Save Value to Call Stack Explorer**.
2. In a later debug run, pause at the same place, select the saved values in
   the **Values** tab and click **Compare with Current Debug Session**. The
   same named variables are read from the current frame and compared with the
   saved values in a new tab. **Jump to Source** in the Values tab opens the
   line where the values were saved.
3. To compare two saved snapshots, Ctrl/Cmd-click both and click
   **Compare Selected**.
4. In the comparison tab, use **Show Only Changes** to focus on the evidence,
   then **Export as HTML** to share it (or **Copy as Text** for a ticket).
   **Open as Text Diff** shows both snapshots in IntelliJ's diff viewer.
   Close the tab when you're done.
5. Branches that contain differences open automatically, and a collapsed
   object shows how many changes are inside it. Use **Expand All** /
   **Collapse All**, start typing to search the tree, and double-click a frame
   row to jump to its source line.

### Letting AI assistants read debugger values

- **Any AI chat:** select snapshot(s) → **Copy for AI Assistant**, then paste.
- **MCP clients:** enable *Let AI assistants read debugger values* in settings
  (off by default). The server listens on `127.0.0.1` only (default port
  47821) and rejects requests whose Host/Origin header isn't localhost.

  ```bash
  # Claude Code
  claude mcp add --transport http call-stack-explorer http://127.0.0.1:47821/mcp
  ```

  ```json
  // GitHub Copilot in VS Code (.vscode/mcp.json); JetBrains Copilot uses the same shape in its MCP settings
  { "servers": { "call-stack-explorer": { "type": "http", "url": "http://127.0.0.1:47821/mcp" } } }
  ```

  Tools: `get_live_debug_state`, `list_snapshots`, `get_snapshot`,
  `compare_snapshots`, `compare_live_with_snapshot`. Any variable values the
  assistant reads, including secrets or personal data, are sent to that
  assistant.

## Settings

Configurable under **Settings/Preferences → Tools → Call Stack Explorer**:

- Auto-capture on pause (on/off)
- Maximum variable capture depth
- Maximum number of snapshots retained
- Auto-capture debounce interval
- "Save Value" capture depth
- Delay before the slow-capture progress notice appears
- Ignore JVM object ids when comparing
- MCP server for AI assistants (on/off, port; IDE-wide)

## Changelog

### 1.0.0

- Save selected object values from the debugger's Variables view.
- Tool window redesigned like the Debug tool window: Call Stacks and Values
  tabs with frames and variables panes.
- Compare two snapshots, or a snapshot with the current debug session, in a
  debugger-style variables tree; each comparison opens in a closable tab.
- Export comparisons as HTML or text.
- Progress notice for slow captures; captures can be cancelled.
- Copy for AI Assistant, and an opt-in local MCP server for AI assistants.
- Plugin errors can be reported from the IDE's error dialog.
- Faster deleting and pruning of snapshots, plus internal cleanups.
- Supports IntelliJ IDEA 2024.2 and newer (verified on 2024.2 and 2026.1).

### 0.1.0

- Initial release: manual and auto-capture of call stacks on breakpoint pause.
- Call Stack Explorer tool window with snapshot/thread/frame/variable tree
  and source navigation.

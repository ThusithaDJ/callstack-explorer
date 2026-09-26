# Call Stack Explorer

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
- **Call Stack Explorer** tool window listing saved snapshots in a tree:
  snapshot → thread → frame → variables.
- Double-click (or Enter on) a frame node to jump to that source line.
- Snapshots survive IDE restart — stored on disk per-project under
  `.idea/callStackExplorer/`.

## Requirements

- IntelliJ IDEA 2024.2+ (Community or Ultimate) with the Java plugin enabled.
- JDK 21 to build the plugin itself.

## Building

```bash
./gradlew buildPlugin
```

The packaged plugin zip is produced under `build/distributions/`.

## Running in a sandbox IDE

```bash
./gradlew runIde
```

This launches a separate, disposable IntelliJ instance (under
`build/idea-sandbox/`) with the plugin installed, so you can test it without
touching your real IDE installation.

## Installing

Install the built plugin zip via
**Settings/Preferences → Plugins → ⚙️ → Install Plugin from Disk...**.

## Usage

1. Set a breakpoint and start a debug session as usual.
2. When the breakpoint is hit, click **Save Call Stack** in the debugger
   toolbar's overflow menu (or let auto-capture do it, if enabled).
3. Open the **Call Stack Explorer** tool window (bottom toolbar) to browse
   saved snapshots — expand a snapshot to see its thread(s), frames, and
   captured variables.
4. Double-click a frame to navigate to that source location.

## Settings

Configurable under **Settings/Preferences → Tools → Call Stack Explorer**:

- Auto-capture on pause (on/off)
- Maximum variable capture depth
- Maximum number of snapshots retained
- Auto-capture debounce interval

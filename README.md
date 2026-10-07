# Pi Agent Launcher

[中文文档](README_CN.md)

One-click [Pi coding agent](https://pi.dev) launcher for JetBrains IDEs — opens a "Pi" tab inside the Terminal tool window and starts `pi` automatically.

[![JetBrains Plugin](https://img.shields.io/badge/JetBrains-Plugin-orange)](https://plugins.jetbrains.com/plugin/31737-pi-agent-launcher)
[![License: MIT](https://img.shields.io/badge/license-MIT-green.svg)](LICENSE)

## Features

- **One-click launch** — Click the π button in the toolbar to start Pi
- **Terminal integration** — Pi runs as a tab inside the IDE's Terminal window (alongside Local)
- **Send to Pi** — Select code → Right-click → "Send to Pi" inserts `@path/file.go#L10-25` into Pi's input
- **Completion notifications** — Get notified when Pi finishes
- **Model configuration** — Pick model and thinking level from `~/.pi/agent/models.json`

### No diff pop-ups by design

Pi sessions routinely rewrite dozens of files. The plugin deliberately opens **no**
diff editor and shows **no** per-file notification for those changes — surfacing
100 modified files as 100 editor tabs is unusable. Review the work with `git diff`.

If you do want modified files opened as ordinary editor tabs, opt in via
**Settings → Tools → Pi Agent → Auto-open files** (off by default).

## Keyboard Shortcuts

| Shortcut | Action |
|----------|--------|
| `Cmd+Esc` / `Ctrl+Esc` | Launch or focus Pi |
| `Cmd+Shift+P` / `Ctrl+Shift+P` | Send selection to Pi |

## Quick Start

1. Install the plugin from [JetBrains Marketplace](https://plugins.jetbrains.com/plugin/31737-pi-agent-launcher)
2. Ensure `pi` CLI is installed and in your PATH
3. Click the **π** button in the toolbar
4. A "Pi" tab opens in the Terminal window and `pi` starts automatically

## Configuration

**Settings → Tools → Pi Agent**

- **Model** — Select from models defined in `~/.pi/agent/models.json`
- **Custom model id** — Override the dropdown with any model identifier
- **Thinking level** — Default / none / low / medium / high / max
- **Pi command** — Custom path to pi binary
- **Extra arguments** — Additional CLI flags
- **Auto-open files** — Off by default. When enabled, opens modified files as ordinary editor tabs (never diffs), debounced and capped.
- **Notifications** — Toggle completion notifications

## Troubleshooting: IDE freeze

Some GoLand 2026.2 builds freeze because of a platform-level read/write-lock deadlock
(see [GO-20886](https://youtrack.jetbrains.com/issue/GO-20886) and
[IJPL-252277](https://youtrack.jetbrains.com/issue/IJPL-252277)). Earlier versions of
this plugin contributed to it: VFS change notifications were handled on a background
thread, opening an editor or a diff on every single write — including writes made by
`go build` or `gofmt`.

Since 0.1.5 the plugin no longer opens diffs at all, only touches the editor when you
explicitly enable *Auto-open files*, and does that on the EDT with debouncing and
filtering. If the IDE still freezes, update or roll back GoLand.

## Supported IDEs

Works with all JetBrains IDEs: IntelliJ IDEA, GoLand, PyCharm, WebStorm, PhpStorm, CLion, Rider, RubyMine, and more.

## Development

```bash
# Build
./gradlew build

# Run sandbox IDE for testing
./gradlew runIde

# Package
./gradlew buildPlugin
```

## Project Structure

```
src/main/kotlin/com/piagent/launcher/
├── actions/
│   ├── LaunchPiAction.kt          # Toolbar button → launch Pi
│   ├── OpenPiAction.kt            # Cmd+Esc → focus Pi
│   └── SendSelectionAction.kt     # Send @file#L reference
├── services/
│   ├── PiTerminalService.kt       # Terminal lifecycle + send text
│   ├── PiVfsUtils.kt              # VFS change filtering helpers
│   ├── PiChangeDebouncer.kt       # Coalesces write bursts onto the EDT
│   └── PiFileWatcher.kt           # Optional auto-open (off by default)
└── settings/
    ├── PiSettings.kt              # Persistent config
    ├── PiSettingsConfigurable.kt  # Settings UI panel
    └── PiModelLoader.kt           # Load models from models.json
```

## License

MIT

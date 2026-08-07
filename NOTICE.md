# Third-party notices

FoldCode is licensed under the **GNU General Public License v3.0 or later** (see
`LICENSE`). It is GPL because it vendors GPLv3 source from Termux; that obligation is
inherited deliberately and the full corresponding source of this app is published
alongside every release.

## Vendored source

### Termux (`app/src/main/java/com/termux/**`)

22 Java files from [termux/termux-app](https://github.com/termux/termux-app) —
the `terminal-emulator` and `terminal-view` modules (terminal emulator, screen buffer,
renderer, view, and text-selection UI), plus two selection-handle drawables and three
strings.

- Copyright: Fredrik Fornwall and the Termux contributors.
- License: GPLv3.
- Modifications:
  - `TerminalSession.java` was **rewritten** by this project. Upstream forks a local
    subprocess through JNI; an ordinary Android app cannot do that, because Termux's
    binaries live in Termux's private data directory under a different UID (and W^X
    blocks exec from app-writable storage). Ours keeps the identical public API but is
    backed by a TCP connection to a pty bridge hosted inside Termux.
  - `JNI.java` was **deleted** — the app contains no native terminal code.
  - `textselection/*.java` had their `import com.termux.view.R` changed to
    `import dev.foldcode.app.R`, because the modules are compiled into this app rather
    than as separate library modules.

Every other vendored file is unmodified upstream code.

## Runtime dependencies (not distributed with this app)

These are installed by the user into Termux and are **not** bundled in the APK:

| Component | License | Role |
|---|---|---|
| [code-server](https://github.com/coder/code-server) | MIT | Hosts the VS Code workbench that the editor surface renders |
| [Code - OSS](https://github.com/microsoft/vscode) | MIT | The workbench itself, via code-server |
| socat | GPLv2 | pty bridge for the native terminal |
| tmux | ISC | Session persistence |
| git, node, python, clang, ripgrep, … | various | Toolchain, installed on demand |

**Not used, deliberately:** Microsoft's Visual Studio Marketplace, the proprietary VS
Code Server binary, and Microsoft's product-locked extensions (Pylance, C/C++, C# Dev
Kit, Remote-*). Extensions come from [Open VSX](https://open-vsx.org) instead. See
`docs/04-architecture.md` for the full licensing map.

**Trademarks:** "Visual Studio Code" and "VS Code" are Microsoft trademarks. This project
is not affiliated with, endorsed by, or derived from the branded Visual Studio Code
product. "Termux" is the name of the Termux project, likewise unaffiliated.

## Android dependencies

Jetpack (androidx.core, activity-compose, lifecycle, window, compose-bom, material3) and
kotlinx-coroutines — all Apache License 2.0.

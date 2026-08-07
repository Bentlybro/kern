# Kern — a real IDE that runs on the Galaxy Z Fold8

A VS Code-class development environment that runs **entirely on the phone** — real
editor, real terminal, real compilers and language servers, real extensions — wrapped in
a native Android shell built for a foldable instead of a desktop squeezed onto glass.

**Status: working.** You can clone a repo, open it, edit it with full language
intelligence, run commands in a native terminal that survives the app being closed, and
review and commit an agent's work one-handed. Everything below has been verified on
device.

    ./gradlew assembleDebug    →    app/build/outputs/apk/debug/app-debug.apk

## What it does

| | |
|---|---|
| **Editor** | The real VS Code workbench (code-server), with its own chrome hidden — the web layer renders only the editor; tabs, status, and actions are native |
| **Terminal** | Termux's native terminal emulator, no xterm.js and no WebView in the input path. Survives the app being killed (tmux) |
| **Projects** | Native project list and `git clone`, reading the Termux filesystem directly |
| **Agent cockpit** | Monitor an agent, reply one-handed, review a coloured unified diff, commit and push |
| **Fold-native** | Four designed modes: cover, unfolded, tabletop (editor above the crease, terminal below), and DeX |
| **Input** | Coding key row with sticky modifiers, explicit keyboard control, hardware-keyboard chords |
| **Health** | A status screen that tells you what's wrong with the environment instead of failing silently |

## How it works

    ┌─ Kern (Kotlin / Compose) ────────────────────────────┐
    │  native shell: postures · panes · IME insets · key row   │
    │  ├─ editor pane  → WebView → localhost:13337 (workbench) │
    │  ├─ terminal     → native emulator → localhost:13338     │
    │  ├─ cockpit      → tmux capture-pane + git               │
    │  └─ session service (foreground) supervises + restarts   │
    └──────────────────────────┬───────────────────────────────┘
                               │ Termux RUN_COMMAND + localhost
    ┌──────────────────────────▼───────────────────────────────┐
    │  Termux: code-server · socat pty bridge · tmux · git ·   │
    │  clang · python · node · language servers                │
    └──────────────────────────────────────────────────────────┘

The key constraint that shapes everything: an Android app **cannot execute Termux's
binaries** — they live in Termux's private data directory under a different UID, and
Android's W^X rules block exec from app-writable storage. So Termux hosts the toolchain
and Kern drives it over `RUN_COMMAND` and localhost, gated by a per-install token.

Why the editor is still a web view — and why that is not a cop-out — is argued from
evidence in [docs/08-decisions.md](docs/08-decisions.md) (decision **D12**). Short
version: desktop VS Code is *also* a browser, and the three things that actually hurt on
a phone (layout, keyboard, terminal) are host problems, not Monaco problems.

## Setup

1. Install **Termux** (F-Droid or GitHub releases — not the Play build unless you know why).
2. Install Kern's APK.
3. Open Kern and follow the setup screen: copy one command into Termux, grant the
   Termux permission, allow unrestricted battery.
4. Recommended once: Developer options → **Disable child process restrictions** (Android
   kills child processes above 32; this is why long builds die).

The `status` screen re-checks all of this at any time.

## Documentation

| Doc | Contents |
|---|---|
| [01 Goals](docs/01-goals-and-requirements.md) | Vision, parity checklist, non-goals |
| [02 Device](docs/02-device.md) | Verified Z Fold8 specs and what they imply |
| [03 Android constraints](docs/03-android-constraints.md) | Exec rules, process killers, storage, distribution |
| [04 Architecture](docs/04-architecture.md) | Options compared, licensing map |
| [05 UX](docs/05-ux.md) | The four display modes, keyboard strategy |
| [06 Roadmap](docs/06-roadmap.md) | M0–M6 with what landed and what didn't |
| [07 Risks](docs/07-risks.md) | Risk register |
| [08 Decisions](docs/08-decisions.md) | Architecture decision records |
| [09 Building](docs/09-building.md) | Build, install, debug |
| [10 Native UI requirements](docs/10-native-ui-requirements.md) | The observed failures this design fixes |
| [research/](docs/research/), [research2/](docs/research2/), [research3-terminal/](docs/research3-terminal/) | Verified findings with sources |

## Licensing

GPLv3-or-later (see [LICENSE](LICENSE)) because it vendors Termux's terminal emulator.
Attribution and the list of modifications are in [NOTICE.md](NOTICE.md).

Not affiliated with Microsoft or the Termux project. Extensions come from Open VSX;
Microsoft's marketplace and product-locked extensions are deliberately not used.

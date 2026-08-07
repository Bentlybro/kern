# 04 — Architecture

## Options considered

Full analysis in [research/vscode-arch.md](research/vscode-arch.md) and
[research/editor-tech.md](research/editor-tech.md).

| # | Option | Verdict | Why |
|---|---|---|---|
| A | **Native fold shell + local code-server (VS Code workbench in WebView)** | ✅ **Chosen** | Only path with existence proofs (VHEditor, Code FA, VSCodroid, Termux+code-server). Real extension host → terminals, debuggers, Open VSX extensions all work. ~2–4 months to MVP |
| B | vscode-web workbench + custom non-Node backend (monaco-vscode-api) | ❌ | 9–18 months + heavy monthly upstream churn; web-worker extension host loses most extensions |
| C | CodeMirror 6 (or Sora) custom IDE + LSP/DAP clients | ❌ as core / 🔶 as satellite | Best touch/IME quality, but zero VS Code ecosystem; every bespoke mobile IDE stack died. Reserved for the *cover-screen/quick-edit satellite* later (D9, optional) |
| D | Fully native IDE | ❌ | Team-scale, multi-year |
| E | Thin client to cloud (Codespaces/Cosyra model) | ❌ as core / ✅ as optional mode | Recreates Dcoder's fatal cloud dependence; but SSH/remote as an *optional* mode is cheap and valuable |

Editor-core evidence backing A over "just Monaco": Monaco's touch support has been in
Microsoft's Backlog since 2019 with no selection handles as of 2025 — we get the
workbench's power but must supply our own input layer natively (docs/05). For a
touch-first satellite editor later, CodeMirror 6 is the proven choice (Replit) — not in
v1 scope.

## System overview

```
┌────────────────────────── FoldCode.apk (Kotlin / Jetpack Compose) ──────────────────────────┐
│                                                                                             │
│  ┌── Fold-aware Shell ─────────────────────────────────────────────────────────────────┐    │
│  │  posture/window-size engine (Jetpack WindowManager 1.5+, M3 Adaptive 1.2+)          │    │
│  │  4 modes: Cover Companion · Unfolded IDE · Tabletop · DeX/Desktop        (docs/05)  │    │
│  └─────────────────────────────────────────────────────────────────────────────────────┘    │
│                                                                                             │
│  ┌── IDE Surface ───────────────────────────┐   ┌── Native Companion Surfaces ─────────┐    │
│  │  WebView → http://127.0.0.1:PORT         │   │  Cover screen: agent cockpit, git    │    │
│  │  VS Code workbench (code-server)         │   │  ops, diff review, build status      │    │
│  │  JS bridge: keys, clipboard, files,      │   │  Native terminal panel (tabletop)    │    │
│  │  theme, state signals                    │   │  Onboarding wizard, settings         │    │
│  └──────────────────────────────────────────┘   └──────────────────────────────────────┘    │
│                                                                                             │
│  ┌── Input Layer ──────────────────────────────────────────────────────────────────────┐    │
│  │  key-row bar (sticky Ctrl/Alt, Esc/Tab/arrows/symbols) · dispatchKeyEvent bridge    │    │
│  │  IME strategy (split/floating aware) · hardware-kb shortcut fidelity                │    │
│  └─────────────────────────────────────────────────────────────────────────────────────┘    │
│                                                                                             │
│  ┌── Session Service (specialUse FGS + wakelock) ──────────────────────────────────────┐    │
│  │  supervises: node (code-server) · ptys · language servers · agent CLIs              │    │
│  │  tmux-semantics session registry → reattach after any death                         │    │
│  │  push notifications (build done, agent needs approval)                              │    │
│  └─────────────────────────────────────────────────────────────────────────────────────┘    │
│                                                                                             │
│  ┌── Runtime Layer (Termux-model userland, app-private prefix) ────────────────────────┐    │
│  │  bootstrap: termux-packages aarch64 (bash, git, node, python, clang, ripgrep…)      │    │
│  │  exec via system_linker_exec (termux-exec LD_PRELOAD)  ·  pkg manager (apt/pacman)  │    │
│  │  optional: proot-distro (glibc long tail)  ·  optional: SSH remotes                 │    │
│  └─────────────────────────────────────────────────────────────────────────────────────┘    │
│                                                                                             │
│  Storage: $PREFIX + $HOME in getFilesDir() (direct POSIX paths) · DocumentsProvider out     │
└─────────────────────────────────────────────────────────────────────────────────────────────┘
                 Extensions ⇄ Open VSX registry          Optional ⇄ user's own SSH remotes
```

## Component decisions

### Runtime layer — fork strategy (D4)

Embed the Termux runtime *inside* our app (VHEditor/AndroidIDE precedent) rather than
depending on the Termux app:

- Bootstrap installer downloads/unpacks the termux-packages aarch64 bootstrap into our
  own prefix (app-private), so the user installs **one** APK.
- Vendor `termux-exec` (linker64 wrapping) and the pkg tooling; track termux-packages
  as an upstream we *consume*, not fork wholesale.
- Consequence: embedding Termux-derived code (GPLv3) ⇒ **the app is GPLv3, open
  source**. Accepted — it's also the right community position for a sideloaded dev tool.

### VS Code server — code-server over OpenVSCode Server (D3, confirm in M0)

- code-server: proven under Termux (official Coder docs page + TUR package), Open VSX
  default, active weekly-ish releases, has auth (we run `--auth none` bound to
  127.0.0.1).
- OpenVSCode Server is the fallback if code-server's patch set fights Android harder
  than expected. Both are MIT-based; the proprietary Microsoft VS Code Server is
  license-radioactive (no standalone offering / no transfer) and never an option.
- We carry a **minimal patch set**: Android platform-gating spoof, keyboard.dispatch
  default, any bionic build fixes. Every patch documented and upstreamable where
  possible. Pin one version per release; bump monthly, never chase daily.

### Native modules on bionic

node-pty compiles under Termux; @vscode/ripgrep has no android-arm64 prebuilt →
substitute the termux `ripgrep` binary; same review for @parcel/watcher, sqlite,
spdlog, argon2. TUR already solves these (their code-server package works) — start from
their build recipes.

### The WebView bridge (our real IP)

Native Kotlin ⇄ workbench JS bridge carrying:

- **Input**: intercepted hardware-key chords; key-row events injected as synthetic
  keyboard events or workbench commands (`workbench.action.*` via a tiny companion
  extension).
- **State signals**: posture changes, window mode, IME visibility/height → workbench
  layout commands (e.g., toggle panel position for tabletop mode).
- **Clipboard**, **file intents** (open-with, share-in), **theme sync** (match
  dark/light + AMOLED-black theme), **notifications** (forward workbench toasts to
  Android notifications when backgrounded).
- Implemented as: Android `WebMessageChannel`/`addJavascriptInterface` + a small
  bundled VS Code extension ("foldcode-bridge") that exposes workbench commands to the
  native side. This extension is also where fold-aware workbench behaviors live —
  written once, in TypeScript, updateable without app releases.

### AI agent layer

- Agents are ordinary CLIs in the runtime (Claude Code runs under Termux today —
  Node-based). The Session Service tracks agent processes distinctly: their
  waiting-for-input states drive push notifications and the cover-screen cockpit.
- Cockpit v1 = native UI over a simple protocol: watch agent output streams + a
  diff-review pane (git diff of workspace) + approve/deny text injection into the pty.
  No custom agent protocol dependencies; works with any CLI agent.

## Licensing map (hard constraints)

| Component | License | Use |
|---|---|---|
| Code-OSS / code-server / OpenVSCode | MIT (+patches) | ✅ core; must strip MS branding entirely (name, logos, telemetry) |
| MS Marketplace, Pylance, cpptools, C#, Remote-SSH/WSL, Live Share, VS Code Server | proprietary, product-locked & actively enforced | 🚫 never; replacements: Open VSX, Pyright/basedpyright, clangd, Open Remote SSH |
| Open VSX | Eclipse-run registry | ✅ default extension gallery |
| termux-app-derived code (terminal, exec) | GPLv3 | ✅ ⇒ app is GPLv3 |
| termux-packages binaries | various OSS | ✅ redistributable bootstrap (as Termux does) |
| sora-editor (if satellite editor later) | LGPL-2.1 | 🔶 dynamic-link obligations |

Product name must not contain "VS Code"/"Visual Studio" or use its marks. "FoldCode" is
a working codename — trademark-check before any public release (D8).

## What we explicitly do NOT build

- No custom editor core, no custom extension system, no custom package repos
  (termux-packages + Open VSX are the supply chains).
- No cloud backend of ours. Optional remotes are the user's own.
- No Gradle/Android-app-building toolchain (see non-goals).

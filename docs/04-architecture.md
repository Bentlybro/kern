# 04 — Architecture

> **Partly superseded.** The editor architecture below is current: a native shell around the real VS Code workbench (option A) is what shipped. The *runtime* half is not, because this was written when Linux came from a separate Termux install, and Kern now embeds its own Ubuntu and has no Termux dependency at all. Wherever this document says Termux, read [11 — Embedded Linux](11-embedded-linux.md) instead, and see [12 — Setup](12-setup.md) for how that environment is installed.

## Options considered

The full analysis is in [research/vscode-arch.md](research/vscode-arch.md) and [research/editor-tech.md](research/editor-tech.md).

| # | Option | Verdict | Why |
|---|---|---|---|
| A | **Native fold shell + local code-server (VS Code workbench in WebView)** | ✅ **Chosen** | It is the only path with existence proofs behind it (VHEditor, Code FA, VSCodroid, Termux+code-server). It gives us a real extension host, so terminals, debuggers and Open VSX extensions all work. We put it at ~2–4 months to MVP |
| B | vscode-web workbench + custom non-Node backend (monaco-vscode-api) | ❌ | It would take 9–18 months and carry heavy monthly upstream churn, and its web-worker extension host loses most extensions |
| C | CodeMirror 6 (or Sora) custom IDE + LSP/DAP clients | ❌ as core / 🔶 as satellite | It has the best touch and IME quality but zero VS Code ecosystem, and every bespoke mobile IDE stack died. We reserve it for the *cover-screen/quick-edit satellite* later (D9, optional) |
| D | Fully native IDE | ❌ | This is team-scale and multi-year |
| E | Thin client to cloud (Codespaces/Cosyra model) | ❌ as core / ✅ as optional mode | As the core it recreates Dcoder's fatal cloud dependence, but SSH and remote work as an *optional* mode are cheap and valuable |

The editor-core evidence backing A over "just Monaco" is this: Monaco's touch support has sat in Microsoft's Backlog since 2019 and still had no selection handles as of 2025, so we get the workbench's power but must supply our own input layer natively (docs/05). If we build a touch-first satellite editor later, CodeMirror 6 is the proven choice, as Replit shows, but it is not in v1 scope.

## System overview

```
┌────────────────────────── Kern.apk (Kotlin / Jetpack Compose) ──────────────────────────┐
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

We embed the Termux runtime *inside* our app, following the VHEditor and AndroidIDE precedent, rather than depending on the Termux app being installed:

- The bootstrap installer downloads and unpacks the termux-packages aarch64 bootstrap into our own prefix (app-private), so the user installs **one** APK.
- We vendor `termux-exec` (the linker64 wrapping) and the pkg tooling, and we track termux-packages as an upstream we *consume* rather than fork wholesale.
- The consequence is that embedding Termux-derived code (GPLv3) makes **the app GPLv3 and open source**. We accept that, and it is also the right community position for a sideloaded dev tool.

### VS Code server — code-server over OpenVSCode Server (D3, confirm in M0)

- code-server is proven under Termux (an official Coder docs page and a TUR package), it defaults to Open VSX, it releases roughly weekly, and it has auth, though we run `--auth none` bound to 127.0.0.1.
- OpenVSCode Server is the fallback if code-server's patch set fights Android harder than expected. Both are MIT-based, whereas the proprietary Microsoft VS Code Server is radioactive on licence grounds (no standalone offering, no transfer) and is never an option.
- We carry a **minimal patch set**: the Android platform-gating spoof, the keyboard.dispatch default, and any bionic build fixes. We document every patch and keep it upstreamable where possible. We pin one version per release and bump it monthly, never chasing upstream daily.

### Native modules on bionic

node-pty compiles under Termux. @vscode/ripgrep has no android-arm64 prebuilt, so we substitute the termux `ripgrep` binary, and @parcel/watcher, sqlite, spdlog and argon2 all need the same review. TUR already solves these — their code-server package works — so we start from their build recipes.

### The WebView bridge (our real IP)

A bridge between native Kotlin and the workbench's JS carries the following:

- **Input.** It carries intercepted hardware-key chords, and it injects key-row events as synthetic keyboard events or as workbench commands (`workbench.action.*` via a tiny companion extension).
- **State signals.** Posture changes, the window mode and the IME's visibility and height become workbench layout commands (for example, toggling the panel position for tabletop mode).
- It also carries the **clipboard**, **file intents** (open-with, share-in), **theme sync** (matching dark/light plus an AMOLED-black theme) and **notifications**, which forward workbench toasts to Android notifications when the app is backgrounded.
- We implement it with Android `WebMessageChannel`/`addJavascriptInterface` plus a small bundled VS Code extension ("kern-bridge") that exposes workbench commands to the native side. That extension is also where the fold-aware workbench behaviours live, written once, in TypeScript, and updateable without app releases.

### AI agent layer

- Agents are ordinary CLIs in the runtime; Claude Code, which is Node-based, runs under Termux today. The Session Service tracks agent processes distinctly, because their waiting-for-input states drive the push notifications and the cover-screen cockpit.
- Cockpit v1 is a native UI over a simple protocol: it watches the agent output streams, offers a diff-review pane (a git diff of the workspace), and injects approve or deny text into the pty. It depends on no custom agent protocol and works with any CLI agent.

## Licensing map (hard constraints)

| Component | Licence | Use |
|---|---|---|
| Code-OSS / code-server / OpenVSCode | MIT (+patches) | ✅ These are the core, and we must strip the MS branding entirely (name, logos, telemetry) |
| MS Marketplace, Pylance, cpptools, C#, Remote-SSH/WSL, Live Share, VS Code Server | proprietary, product-locked & actively enforced | 🚫 We never use these; the replacements are Open VSX, Pyright/basedpyright, clangd and Open Remote SSH |
| Open VSX | Eclipse-run registry | ✅ This is our default extension gallery |
| termux-app-derived code (terminal, exec) | GPLv3 | ✅ Using it makes the app GPLv3 |
| termux-packages binaries | various OSS | ✅ We redistribute them as a bootstrap, as Termux does |
| sora-editor (if satellite editor later) | LGPL-2.1 | 🔶 It carries dynamic-link obligations |

The product name must not contain "VS Code" or "Visual Studio" and must not use their marks. "Kern" is a working codename, and we run a trademark check before any public release (D8).

## What we explicitly do NOT build

- We build no custom editor core, no custom extension system and no custom package repos, because termux-packages and Open VSX are the supply chains.
- We run no cloud backend of our own; the optional remotes are the user's own.
- We ship no Gradle or Android-app-building toolchain (see non-goals).

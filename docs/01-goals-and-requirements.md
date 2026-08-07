# 01 — Goals and Requirements

## Vision

A development environment you would *choose* to use on the Galaxy Z Fold8 — not a toy,
not a thin client that dies without Wi-Fi, and not a desktop app suffering on a touch
screen. Unfolded with a keyboard or in DeX it should feel like VS Code; folded it should
feel like a purpose-built one-handed dev companion; half-folded it should do something no
desktop IDE can do at all.

## Target user

- Primary: the author — a developer with a Z Fold8 (standard/wide model), comfortable
  sideloading, using AI coding agents (Claude Code), working mostly in Python and
  web/TypeScript, sometimes at a desk (DeX/Bluetooth keyboard), often on the go.
- Secondary (later): the Termux/enthusiast community that currently glues this workflow
  together by hand — the people running code-server in Termux today.

Design implication: optimize for the enthusiast daily-driver experience first; mass-market
polish and Play Store compliance are explicitly **not** v1 constraints.

## What "all the tools" means — feature parity checklist

The bar is "VS Code-class", measured concretely. ✅ = comes with the chosen architecture
nearly free; 🔧 = needs real work on our side; 🚫 = explicitly replaced (licensing).

| Capability | Target | Notes |
|---|---|---|
| File explorer, tabs, split editors | ✅ | VS Code workbench |
| Syntax highlighting (TextMate grammars) | ✅ | workbench, all languages |
| LSP language intelligence (completion, hover, rename, diagnostics) | ✅ + 🔧 | extension-provided; we curate working on-device language servers |
| Extensions | ✅ | Open VSX registry (16k+ extensions; no Microsoft-proprietary ones) |
| Integrated terminal (real shell) | ✅ + 🔧 | node-pty on-device; we add the native key row |
| Git: full SCM UI, diff, blame, history | ✅ | built into workbench + git binary on-device |
| Search across project (ripgrep) | ✅ | needs bionic-built rg binary |
| Debugging (DAP: breakpoints, step, watch) | ✅ + 🔧 | debugpy (Python), node inspector; C/C++ on-device DAP needs a spike |
| Run real toolchains: Python, Node, clang, rust, gradle | ✅ + 🔧 | termux-packages; proot-distro for glibc-only tools |
| Tasks, snippets, themes, settings, keybindings | ✅ | workbench |
| AI agents (Claude Code, aider, etc.) | 🔧 | first-class terminal citizens + native cockpit UI (docs/05) |
| Remote development over SSH | 🔧 | Open Remote SSH (OSS) — MS Remote-SSH is license-blocked |
| Python: Pylance | 🚫 → Pyright/basedpyright | Pylance is Microsoft-proprietary, product-locked |
| C/C++: cpptools | 🚫 → clangd + a DAP | cpptools hard-blocks non-Microsoft builds since Apr 2025 |
| VS Code branding / MS Marketplace | 🚫 | trademark + Marketplace ToS; we are "VS Code-class", never "VS Code" |

## Fold-native requirements (the differentiator)

These are what make this *this project* rather than a VHEditor clone:

1. **Four first-class display modes** — cover-screen companion, unfolded touch IDE,
   tabletop (half-folded) laptop mode, DeX/desktop windowing. Each is designed, not
   just "responsive".
2. **Owned input layer** — coding symbol/nav key row, sticky modifiers, sane IME
   behavior, full hardware-keyboard shortcut fidelity, floating/split keyboard support.
3. **Continuity** — fold/unfold/cover transitions and app restarts restore exact state
   (open files, cursor, terminal scrollback, running session).
4. **Survivability** — builds, servers, and agent runs survive screen-off and app
   switches within Android's real limits; when the OS wins anyway, sessions resume
   instead of being lost (tmux semantics).
5. **AI cockpit** — kick off/steer/approve agent work from the cover screen with push
   notifications; review diffs one-handed.

## Non-goals (v1)

- **Google Play distribution.** Incompatible with a real toolchain story (docs/03);
  sideload/GitHub releases first, F-Droid maybe later.
- **iOS / other devices.** Fold8-first. It should *run* on any Android 13+ arm64 device,
  but only the Fold8 experience is designed and tested.
- **Building Android apps on-device (Gradle/AndroidIDE territory).** Enormous
  maintenance weight; killed AndroidIDE and CodeAssist. Revisit post-v1 at the earliest.
- **A cloud service or accounts.** Local-first forever; remote is the user's own
  SSH/agents. No server costs, no Dcoder-style death.
- **Building our own editor core.** Every bespoke mobile editor stack got abandoned;
  we ride the VS Code workbench + its ecosystem.

## Success criteria

- **v1 (daily drivable):** author can do a full real work session — clone, edit with
  LSP, run tests, commit/push, run Claude Code — entirely on the phone, in every one of
  the four display modes, without opening Termux by hand or losing a session to Android.
- **Performance floors:** workbench interactive < 3 s warm launch; typing latency
  indistinguishable from desktop with hardware keyboard; 5k-line file editing smooth;
  a `pip install` / `npm install` of a medium project completes without the screen
  needing to stay on.
- **Quality tier:** meets Google "Adaptive optimized" (Tier 2) behaviors — no
  letterboxing, continuity, keyboard/mouse support, drag-and-drop in — and posture-aware
  features put it into "Adaptive differentiated" (Tier 1) territory.

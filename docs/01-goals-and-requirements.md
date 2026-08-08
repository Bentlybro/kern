# 01 — Goals and Requirements

## Vision

The goal is a development environment you would *choose* to use on the Galaxy Z Fold8. It is not a toy, it is not a thin client that dies without Wi-Fi, and it is not a desktop app suffering on a touch screen. Unfolded with a keyboard or in DeX it should feel like VS Code; folded it should feel like a purpose-built one-handed dev companion; and half-folded it should do something no desktop IDE can do at all.

## Target user

- The primary user is the author, a developer with a Z Fold8 (the standard wide model) who is comfortable sideloading, uses AI coding agents such as Claude Code, works mostly in Python and web/TypeScript, and is sometimes at a desk with DeX or a Bluetooth keyboard but often on the go.
- The secondary audience, which matters later, is the Termux and enthusiast community that currently glues this workflow together by hand — the people running code-server in Termux today.

The design implication is that we optimise for the enthusiast daily-driver experience first. Mass-market polish and Play Store compliance are explicitly **not** v1 constraints.

## What "all the tools" means — feature parity checklist

The bar is "VS Code-class", and we measure it concretely. A ✅ means the capability comes with the chosen architecture nearly free, a 🔧 means it needs real work on our side, and a 🚫 means we replace it deliberately for licensing reasons.

| Capability | Target | Notes |
|---|---|---|
| File explorer, tabs, split editors | ✅ | The VS Code workbench provides these |
| Syntax highlighting (TextMate grammars) | ✅ | The workbench ships this for all languages |
| LSP language intelligence (completion, hover, rename, diagnostics) | ✅ + 🔧 | Extensions provide this, and we curate the language servers that work on-device |
| Extensions | ✅ | They come from the Open VSX registry, which carries 16k+ extensions but no Microsoft-proprietary ones |
| Integrated terminal (real shell) | ✅ + 🔧 | node-pty runs on-device, and we add the native key row |
| Git: full SCM UI, diff, blame, history | ✅ | This is built into the workbench and backed by the git binary on-device |
| Search across project (ripgrep) | ✅ | This needs a bionic-built rg binary |
| Debugging (DAP: breakpoints, step, watch) | ✅ + 🔧 | debugpy covers Python and the node inspector covers Node, but an on-device C/C++ DAP still needs a spike |
| Run real toolchains: Python, Node, clang, rust, gradle | ✅ + 🔧 | termux-packages supplies these, and proot-distro covers the glibc-only tools |
| Tasks, snippets, themes, settings, keybindings | ✅ | The workbench provides all of these |
| AI agents (Claude Code, aider, etc.) | 🔧 | Agents are first-class terminal citizens, and a native cockpit UI sits over them (docs/05) |
| Remote development over SSH | 🔧 | We use Open Remote SSH (OSS), because Microsoft's Remote-SSH is licence-blocked |
| Python: Pylance | 🚫 → Pyright/basedpyright | Pylance is Microsoft-proprietary and product-locked |
| C/C++: cpptools | 🚫 → clangd + a DAP | cpptools has hard-blocked non-Microsoft builds since Apr 2025 |
| VS Code branding / MS Marketplace | 🚫 | The trademark and the Marketplace ToS forbid it, so we are "VS Code-class" and never "VS Code" |

## Fold-native requirements (the differentiator)

These are the requirements that make this *this project* rather than a VHEditor clone:

1. **Four first-class display modes.** Kern has a cover-screen companion, an unfolded touch IDE, a tabletop (half-folded) laptop mode, and DeX/desktop windowing. We design each one rather than leaving it merely "responsive".
2. **An owned input layer.** We supply the coding symbol and navigation key row, sticky modifiers, sane IME behaviour, full hardware-keyboard shortcut fidelity, and floating/split keyboard support.
3. **Continuity.** Fold, unfold and cover transitions — and app restarts too — restore the exact state: the open files, the cursor, the terminal scrollback and the running session.
4. **Survivability.** Builds, servers and agent runs survive screen-off and app switches within Android's real limits, and when the OS wins anyway the session resumes instead of being lost, with tmux semantics.
5. **An AI cockpit.** The user can start, steer and approve agent work from the cover screen with push notifications, and can review diffs one-handed.

## Non-goals (v1)

- **Google Play distribution.** Play is incompatible with a real toolchain story (docs/03), so we sideload and ship GitHub releases first, and consider F-Droid later.
- **iOS and other devices.** Kern is Fold8-first. It should *run* on any Android 13+ arm64 device, but only the Fold8 experience is designed and tested.
- **Building Android apps on-device, which is Gradle and AndroidIDE territory.** The maintenance weight is enormous, and it killed AndroidIDE and CodeAssist. We revisit this post-v1 at the earliest.
- **A cloud service or accounts.** Kern is local-first forever, and any remote is the user's own SSH and agents. That means no server costs and no Dcoder-style death.
- **Building our own editor core.** Every bespoke mobile editor stack was abandoned, so we ride the VS Code workbench and its ecosystem instead.

## Success criteria

- **v1 is daily drivable.** The author can do a full real work session — clone, edit with LSP, run tests, commit and push, run Claude Code — entirely on the phone, in every one of the four display modes, without opening Termux by hand or losing a session to Android.
- **Performance floors.** The workbench is interactive in under 3 s on a warm launch, typing latency with a hardware keyboard is indistinguishable from desktop, editing a 5k-line file stays smooth, and a `pip install` or `npm install` of a medium project completes without the screen needing to stay on.
- **Quality tier.** Kern meets Google's "Adaptive optimized" (Tier 2) behaviours — no letterboxing, continuity, keyboard and mouse support, and drag-and-drop in — and its posture-aware features put it into "Adaptive differentiated" (Tier 1) territory.

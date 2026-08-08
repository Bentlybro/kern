# 10 — Native UI Requirements

This document was written on 2026-08-06 from **observed failures** using the M1 app, which is the code-server workbench in a WebView, on the Galaxy Z Fold8. These are the acceptance criteria for the native UI work, and each one maps to a root cause that a WebView-hosted desktop workbench cannot fix.

## The three observed failures

| # | Symptom (user-reported) | Root cause | Native fix |
|---|---|---|---|
| F1 | The desktop-sized UI does not fit, even on the Fold8's 7.6" screen. | The workbench is a fixed desktop layout whose title bar, activity bar, side bar, panel and status bar are all sized for a mouse, and scaling it down shrinks the touch targets below a usable size. | Compose every layout for the actual window size and posture, with 48dp targets and bottom-anchored controls. |
| F2 | The UI glitches when the keyboard opens. | The IME resizes the WebView viewport, so the web app re-lays out asynchronously and loses the cursor position and scroll. Web content cannot participate in Android's window-insets animation. | Handle `WindowInsetsCompat.ime()` natively, so that the panes resize with the keyboard, the cursor stays visible, and the key row docks above the IME. |
| F3 | The terminal is hard to use. | code-server's terminal is xterm.js inside a WebView panel, which is three layers away from the OS, so key events, IME composition and selection all pass through the browser first. | Embed Termux's native `terminal-view` widget, which is GPLv3 and comes from the project we already depend on, as a first-class Android surface. |

## Requirements derived from these

**R1 — Layout is composed, not scaled.** Every surface is laid out from `WindowSizeClass` and `FoldingFeature`, never from a scaled-down desktop layout. The inner screen carries two comfortable panes at most, and the file tree is a collapsible rail or overlay rather than a permanent third column.

**R2 — The keyboard never breaks the layout.** Opening and closing the IME animates the panes smoothly, keeps the caret in view, and never reflows the editor's scroll position. Split and floating keyboards are supported. The coding key row is docked to the IME rather than floating over the content.

**R3 — The terminal is native and excellent.** It is a real Android terminal view with correct IME behaviour, hardware-keyboard fidelity and selection that works with touch, and it survives posture changes. It supports multiple sessions, and it must be usable in the bottom half in tabletop mode.

**R4 — Everything code-server does that matters, natively.** This restates the parity target from docs/01 for the native UI: the file tree, tabs and splits, syntax highlighting, LSP intelligence (completion, hover, go-to-def, rename, diagnostics), find and replace plus project search, git SCM with diffs, debugging, tasks and run, the command palette, settings and themes.

**R5 — Termux remains the backend.** This is unchanged from M1: the toolchain, the language servers, git, the compilers and the agent CLIs all run in the Termux userland. The native UI replaces the *presentation* layer only, and the execution architecture in docs/03 and docs/04 stands.

**R6 — No regression in reach.** Anything not yet built natively must remain reachable via the existing workbench surface, so there is never a period where the app can do less than it does today.

## Non-negotiable UX floors

- Touch targets are ≥ 48dp everywhere the user taps.
- Text selection uses native Android handles and a magnifier. Monaco has had no selection handles since the request was filed in 2019, and this is the single clearest reason the editing surface must be native.
- Typing latency is indistinguishable from a native text field when a hardware keyboard is attached.
- Folding, unfolding and other posture changes never lose editor state, terminal scrollback or focus.

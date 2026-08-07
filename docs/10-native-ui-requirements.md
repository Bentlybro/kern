# 10 — Native UI Requirements

Written 2026-08-06 from **observed failures** using the M1 app (code-server workbench in
a WebView) on the Galaxy Z Fold8. These are the acceptance criteria for the native UI
work; each maps to a root cause that a WebView-hosted desktop workbench cannot fix.

## The three observed failures

| # | Symptom (user-reported) | Root cause | Native fix |
|---|---|---|---|
| F1 | Desktop-sized UI doesn't fit, even on the Fold8's 7.6" screen | The workbench is a fixed desktop layout: title bar, activity bar, side bar, panel, status bar, all sized for a mouse. Scaling it down shrinks touch targets below usable size | Layouts composed for the actual window size and posture, 48dp targets, bottom-anchored controls |
| F2 | UI glitches when the keyboard opens | The IME resizes the WebView viewport; the web app re-layouts asynchronously and loses cursor position/scroll. Web content cannot participate in Android's window-insets animation | Native `WindowInsetsCompat.ime()` handling: panes resize with the keyboard, cursor stays visible, key row docks above the IME |
| F3 | Terminal is hard to use | code-server's terminal is xterm.js inside a WebView panel — three layers from the OS. Key events, IME composition, and selection all pass through the browser first | Embed Termux's native `terminal-view` widget (GPLv3, same project we already depend on) as a first-class Android surface |

## Requirements derived from these

**R1 — Layout is composed, not scaled.** Every surface is laid out from
`WindowSizeClass` + `FoldingFeature`, never a scaled-down desktop layout. Two comfortable
panes max on the inner screen; file tree is a collapsible rail/overlay, not a permanent
third column.

**R2 — The keyboard never breaks the layout.** Opening/closing the IME animates panes
smoothly, keeps the caret in view, and never reflows the editor's scroll position.
Split/floating keyboards supported. The coding key row is docked to the IME, not floating
over content.

**R3 — The terminal is native and excellent.** Real Android terminal view, correct IME
behavior, hardware-keyboard fidelity, selection that works with touch, and it survives
posture changes. Multiple sessions, and it must be usable in the bottom half in tabletop
mode.

**R4 — Everything code-server does that matters, natively.** The parity target (see
docs/01) restated for the native UI: file tree, tabs/splits, syntax highlighting, LSP
intelligence (completion, hover, go-to-def, rename, diagnostics), find/replace + project
search, git SCM with diffs, debugging, tasks/run, command palette, settings, themes.

**R5 — Termux remains the backend.** Unchanged from M1: the toolchain, language servers,
git, compilers, and agent CLIs all run in the Termux userland. The native UI replaces the
*presentation* layer only — the execution architecture (docs/03, docs/04) stands.

**R6 — No regression in reach.** Anything not yet built natively must remain reachable
via the existing workbench surface, so there is never a period where the app can do less
than it does today.

## Non-negotiable UX floors

- Touch targets ≥ 48dp everywhere the user taps.
- Text selection uses native Android handles + magnifier (Monaco has had no selection
  handles since the request was filed in 2019 — this is the single clearest reason the
  editing surface must be native).
- Typing latency indistinguishable from a native text field with a hardware keyboard.
- Fold/unfold and posture changes never lose editor state, terminal scrollback, or focus.

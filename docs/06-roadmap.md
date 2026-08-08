# 06 — Roadmap

These estimates assume one developer working part-time with heavy AI assistance. Each milestone ends with something you *use daily*, so motivation compounds instead of dying in a 6-month tunnel, which is the solo-maintainer graveyard lesson. The total to a daily-drivable v1 is **~4–6 months part-time**.

## M0 — Feasibility spikes on the real device (1–2 weeks)

The goal is to retire the existential risks before writing app code, and every spike runs on the actual Fold8.

| Spike | Question | Kill criterion |
|---|---|---|
| S1 | What does `getconf PAGESIZE` report in Termux? | If it reports 16KB and termux-packages is still broken, we pivot to self-built 16KB packages or delay. |
| S2 | Can Termux run `pkg install tur-repo code-server` and then serve the workbench to the browser? | If it is unusably slow on flagship hardware we rethink, though we do not expect that on 12GB/8-Elite-Gen-5. |
| S3 | Does the phantom-killer toggle exist and work on One UI 9, and does a background survival test (30-min build, screen off) pass? | If there is no toggle and the kills are aggressive, we lean harder on split-screen UX and reattach semantics. |
| S4 | Does system_linker_exec still work on One UI 9 / Android 17, tested with the termux-play-store build? | If it is broken, we ship a targetSdk 28 variant instead, which is a decision lever rather than a project-killer. |
| S5 | How does the WebView, rather than Chrome, render the workbench, in perf, service workers, IME behaviour and keyboard? | If there are WebView-specific blockers, we evaluate bundling a WebView alternative or a Chrome Custom Tab fallback. |
| S6 | Measure the inner and cover window dp and density, the split-screen sizes, and the posture API values. | This spike only gathers data, so it has no kill criterion. |
| S7 | Does Claude Code run under Termux on-device against a repo in $HOME? | If it is broken, the agent layer becomes SSH-remote-first. |
| S8 | How do code-server and OpenVSCode Server compare side-by-side on patch friction, extension installs from Open VSX, and the platform-gating workaround? | This spike confirms D3. |
| S9 | Do the Python servers (pyright, debugpy) and the Node/TS servers from Open VSX run on-device? | If the LSP or DAP layers fail, we identify the patches needed per language. |

### Spike results (live)

- **S1 ✅ (2026-08-06):** `getconf PAGESIZE` returned **4096** on the owner's Fold8 (16GB/1TB). The kernel uses 4KB pages, so termux-packages works as-is. **Risk R1 is retired.**
- **S2 ✅ (2026-08-06):** Termux, `tur-repo` and `code-server` installed and ran first try, and the workbench opened and was usable in the browser on-device. That confirms baseline feasibility on real hardware.

The deliverable is decisions D3/D4 confirmed in docs/08, an updated risk register, and a go/no-go call.

## M1 — Walking skeleton (3–5 weeks) — **IN PROGRESS (started 2026-08-06)**

M1 is one APK that opens the workbench on a real project. Per the updated D4, M1 uses **companion mode**, which drives the user's Termux via RUN_COMMAND, and the self-contained bootstrap installer moves to the embedded-runtime stage (M4+).

- ✅ The Android app scaffold is in place: Kotlin, Compose, a single activity, `resizeableActivity`, edge-to-edge and an AMOLED dark theme. (The first cut lives in `app/` and was written 2026-08-06.)
- ✅ The companion runtime driver starts and stops code-server on port 13337 via RUN_COMMAND, using an idempotent start script and a guided Termux-side setup command.
- ✅ The Session Service runs as a `specialUse` FGS with a wakelock, health-polls `/healthz`, auto-restarts the server on death, and posts a status notification with a stop action.
- ✅ The WebView IDE surface loads the workbench on localhost with cleartext scoped to 127.0.0.1, sends external links to the browser, auto-retries on main-frame errors, and shows a reconnect overlay.
- ✅ The first input-layer cut (an M2 preview) injects the key row (Esc/Tab/sticky Ctrl·Alt/arrows/symbols/combo chips) as real platform key events, and maps system back to Esc.
- ✅ Posture tracking is surfaced in the status strip, which is the M3 foundation.
- ✅ **The whole path was validated end-to-end on real hardware (lab Pixel, 2026-08-06).** The evidence runs like this: the server was killed (`LISTEN=0`), the app was cold-started, Start IDE was tapped, the log showed `RUN_COMMAND[start] dispatched OK`, the port came up (`LISTEN=1`), and the full workbench rendered in-app with the status strip and key row. The foreground service was confirmed (`isForeground=true types=SPECIAL_USE`). We watched auto-restart supervision recover a killed server unprompted.
- ☐ Validation on the Fold8 itself is still outstanding, covering postures, the cover screen, split-screen and DeX.
- ☐ The project-open flow still needs polish (a `?folder=` picker), as do settings and the session reattach UX.
- ☐ Dark-theme sync is not done; the workbench currently loads its default light theme.

### Bugs found and fixed during M1 validation

1. **`startService` had to become `startForegroundService`.** Termux's `RunCommandService` calls `startForeground()` on itself, so a plain `startService()` is dropped on Android 12+ when Termux has no visible activity. The symptom was that the intent silently did nothing.
2. **The `pgrep` guard matched itself**, and this was the subtle one. The idempotency check `pgrep -f 'code-server.*:13337'` matched *the bash process running the script*, because that script's own command line contains the server's command line. It therefore always concluded "already running" and never started the server. We replaced it with a pidfile (`~/.kern/server.pid` + `kill -0`). **Never use `pgrep -f` for a pattern that appears in the checking script itself.**
3. **There was no logging**, so the first failure was undiagnosable. We added logging under the `Log`/`Kern` tag throughout the runtime and session layers, and surfaced `TermuxRuntime.lastError` in the UI.
4. **The service now adopts an existing server.** If one is already listening because the user started it by hand in Termux, the service adopts it instead of failing or double-starting.
- Acceptance: from a cold start, the user reaches a cloned repo with syntax highlighting and an integrated terminal in ≤ 3 taps, and the session survives an app switch and screen-off for 10 min.

> **The roadmap was re-sequenced 2026-08-07 per decision D12.** The native *shell* comes before any native *editor*, because the shell delivers 100% of the fold-native goal with 0% of the editor risk, and the app stays fully working the entire way. Native-editor work is gated behind a measurement (M5a) rather than assumed.

## M2 — Fold-native shell around the workbench — **CORE LANDED 2026-08-07**

This was validated on the lab Pixel: the workbench chrome is gone, native chrome drives it, the mode switches Cover↔Unfolded on rotation, and the IME docks the key row and resizes panes instead of glitching. The screenshots are in the session log. The remaining M2 items are listed after the checklist.

Everything the user actually complained about (docs/10) is fixed here, without touching the editing surface.

- The posture/window engine feeds `FoldingFeature` and the size classes into the four-mode state machine.
- **Native chrome replaces workbench chrome.** We supply our own tab bar, file tree/rail, command palette and status strip in Compose, and the WebView is handed *only the editor pane*, because the workbench's own activity bar, panel, status bar and title bar are hidden via bundled settings (`workbench.activityBar.location: hidden`, `statusBar.visible: false`, zen-mode-style layout). This is the fix for "desktop UI doesn't fit" (F1).
- **The IME insets are owned natively.** The editor pane resizes with `WindowInsets.ime()`, the key row docks above the keyboard, and the caret stays visible. This is the fix for F2.
- In tabletop mode the editor pane sits on the upright half and the terminal with the key row sits on the flat half, both aligned to the hinge bounds.
- Cover Companion v1 shows the session status, an output tail and notification deep links.
- Continuity covers config-change handling, exact-state restore, and WebView retention across activity recreation, which also fixes the M1 "reloads on re-entry" limitation.
- The DeX pass covers desktop windowing resize and density, pointer support, and top/bottom snapping.
- Acceptance: the unfolded layout fits with 48dp targets and no horizontal squeeze, opening the keyboard never displaces the caret or breaks layout, folding or unfolding mid-edit loses nothing, and tabletop mode is usable for a real REPL session.

### Landed in M2 (verified on device)

- ✅ The `DisplayMode` state machine derives Cover / Unfolded / Tabletop / Desktop from the window width and `FoldingFeature`, and the native top bar shows the posture. We verified it switching `cover` → `unfolded` on rotation.
- ✅ **The workbench chrome is hidden** by settings pushed to `~/.local/share/code-server/User/settings.json` on every session start (base64-piped to avoid shell quoting). They hide the activity bar, status bar, menu bar, layout controls, minimap and secondary side bar (the AI chat panel), and they also set the dark theme, `keyboard.dispatch: keyCode`, and workspace-trust off. The editor now fills the window edge-to-edge.
- ✅ **Native chrome** gives a top bar with a session dot, a posture label and chips (files / open / cmd / term / chat) that drive the workbench through real platform key events. We verified `files` toggling the explorer via Ctrl+B.
- ✅ **Native IME ownership** means the shell owns `systemBars` and `imePadding()`, so opening the keyboard resizes the editor pane and docks the key row above the IME. **This fixes F3→F2 from docs/10.**
- ✅ **The WebView is retained** (`WorkbenchWebView`, process-scoped behind a `MutableContextWrapper`), so posture and rotation changes re-parent it rather than reload it, which fixes the M1 "reloads on re-entry" limitation.
- ✅ Key row v1.5 carries sticky Ctrl/Alt/**Shift**, arrows, home/end, save/find and the symbol strip.
- ✅ The tabletop layout puts the editor above the crease and the control deck below it, with a hinge-thickness gap that carries nothing interactive (the native terminal fills the bottom half in M3).
- ✅ The layout reset is deterministic: VS Code persists layout in localStorage, which survives a settings change, so a `LAYOUT_EPOCH` counter wipes web storage once when workbench settings change materially.

### Still open in M2

- ☐ The native tab bar is not built, so the app still shows the workbench's own editor tabs.
- ☐ The native file tree is not built, so the explorer still comes from the workbench. It needs a command-with-result channel, and Termux's `RUN_COMMAND` supports a result `PendingIntent` for that.
- ☐ Cover-screen scoping is missing, so compact currently renders the full editor. The one-handed cockpit is M5, and it is not meant to be a fallback IDE.
- ☐ DeX/desktop verification on real hardware is outstanding, as is Fold8 posture verification, because the lab Pixel cannot produce a `FoldingFeature`, so Tabletop is untested on-device.
- ☐ **Known gap (M4):** tapping inside the Monaco editor does not reliably raise the soft keyboard (matches monaco-editor#4946). Tapping workbench inputs (quick open) does raise it. This is exactly the class of problem the M4 native input overlay addresses.

## M3 — Native terminal — **CORE LANDED 2026-08-07**

This fixes F3. It was verified on the lab Pixel, with a real shell prompt, real command execution, a tap that raises the keyboard, and the key row docked above the IME.

### Architecture: why a bridge instead of a local pty

Upstream Termux forks its shell with a JNI `createSubprocess()`. **We cannot do that.** Termux's binaries live in *Termux's* private data directory under a different UID, so our app can neither read nor exec them, and W^X blocks exec from our own writable storage anyway (docs/03). The shell must therefore be hosted by Termux, and we attach to it over a transport.

    Kern (native TerminalView + emulator) │  TCP 127.0.0.1:13338 ▼ socat TCP-LISTEN,fork  →  ~/.kern/shell.sh  →  pty  →  bash -li   [inside Termux]

- We vendored **22 upstream Java files** from termux-app (`terminal-emulator` and `terminal-view`, GPLv3) unmodified, covering the emulator, buffer, renderer, view and text selection.
- **We replaced only `TerminalSession`**, with a socket-backed version that keeps the identical public API, so `TerminalView` needed no changes. `JNI.java` is deleted, so the app now contains no native terminal code at all.
- `SessionService` starts the bridge automatically once the server is healthy, auto-installs `socat` if it is missing, and stays idempotent via `~/.kern/bridge.pid`.
- The size handshake works like this: the client sends `SIZE rows cols` as the first line, and the wrapper applies it with `stty` and turns echo on *before* exec'ing the shell, so the handshake is never visible.

### Landed

- ✅ The native terminal renders and executes real Termux commands; we verified an `echo KERN_NATIVE_TERM_OK` round-trip and `ls`.
- ✅ **Tapping the terminal raises the soft keyboard**, which is the exact thing the WebView terminal (and Monaco) fails at.
- ✅ The key row routes to whichever surface has focus (terminal vs workbench) and shares sticky modifier state with the terminal's client.
- ✅ The layouts are settled: tabletop puts the editor above the crease and the terminal below, unfolded uses a 60/40 editor-over-terminal split, and compact shows one surface at a time via the `term`/`editor` chip.
- ✅ Resizes are debounced (400 ms) so a layout burst produces one `stty`, not six.

### Known limitations (M3 v1)

- ~~Resize echoes a ` stty rows N cols N` line~~ — **FIXED 2026-08-07**, after it was reported in real use. The root cause was that raw TCP has no window-resize channel, so the size was sent as terminal *input* and the shell echoed it, which meant every keyboard show/hide produced a line. **The fix is an out-of-band control port.** The shell wrapper publishes its pty path (`tty > ~/.kern/tty`), a second socat listener on **13339** runs `stty -F <pty> rows R cols C`, and the app opens a short-lived connection per resize. Nothing touches the shell's stdin, so nothing echoes. We verified this against Linux `pty_resize()` (drivers/tty/pty.c): TIOCSWINSZ on the slave updates both ends **and** sends SIGWINCH to the foreground process groups, so vim and htop reflow correctly, and it is also idempotent, so an unchanged size is a no-op. Confirmed on device: repeated keyboard toggles produce zero output, and `stty size` reports the live pane size.
- ~~Single session only; no reattach~~ — **SESSION PERSISTENCE LANDED 2026-08-07.** The bridge wrapper now ends with `exec tmux -f ~/.kern/tmux.conf new-session -A -s kern` instead of `exec bash -li`. Closing the app detaches the client, the tmux server keeps the shell running inside Termux, and the next connection reattaches and redraws. tmux is auto-installed alongside socat. The config turns the status bar off, because rows are scarce and we supply chrome natively, sets `escape-time 10` so ESC in vim is not laggy, keeps a 20k-line history, uses `screen-256color` because the tmux-256color terminfo is not guaranteed present, and sets `aggressive-resize on`. **Verified: we wrote a marker, force-stopped the app and relaunched, and the session came back with its scrollback intact.** Resize still works because tmux reacts to SIGWINCH on the client pty, which the control port triggers.
- Multiple named sessions and tabs are still not exposed in the UI; there is one shared `kern` session.
- The bridge requires `socat`, which is auto-installed on first bridge start.
- The terminal is *not* yet the target of the M5 agent cockpit, which reads the same session.

## M3.5 — Workspace management — **LANDED 2026-08-07** (user-requested)

The enabling piece is a **command-with-result channel**: `TermuxCommand.run()` dispatches a script to Termux with a `com.termux.RUN_COMMAND_PENDING_INTENT` and awaits the result bundle (`result` → `stdout`/`stderr`/`exitCode`). We verified the extra names and bundle keys against termux-app's `TermuxConstants`. The PendingIntent must be **MUTABLE**, because Termux fills the results into it. This gives the native UI a general way to ask the Termux filesystem questions without a terminal or the workbench in the loop, and it is the foundation for the native file tree and git status later.

- ✅ **Opening a folder works.** The native projects screen lists `~/projects` in one shell round-trip returning `name<TAB>isRepo`, marks git repos, offers `~` as a fallback, and remembers recents in SharedPreferences across restarts. Selecting one reloads the workbench at `?folder=<path>`, and we verified switching workspace on device.
- ✅ **Git clone works.** The user pastes a URL and the app runs `git clone --depth 1` into `~/projects/<name>` (git auto-installed if missing), with progress state, duplicate-name detection, and the last line of git's own error surfaced on failure. On success the project opens immediately. We verified this end-to-end by cloning a real GitHub repo and watching the workbench switch to it.
- ✅ The native top bar carries a `proj` chip, and the chip row scrolls so it never squeezes the posture label.

Three things are still open here. Browsing outside `~/projects` is not possible, since only home and projects are listed for now. There is no SSH-key or credential support for private repos. Clone progress does not stream, so the UI shows a spinner until git finishes, because RUN_COMMAND returns only on completion.

## M4 — Native input layer over Monaco — **PART 1 LANDED 2026-08-07**

### Landed (verified on device)

- ✅ **Keyboard control is now explicit.** The `⌨` key in the key row shows and hides the IME via `WindowInsetsControllerCompat`. This is the fix for the M2 blocker, where tapping Monaco never raised the keyboard, matching monaco-editor#4946. **Verified end-to-end: the keyboard raised, we typed into README.md, the text was inserted and the dirty indicator appeared.** Editing on the phone now actually works.
- ✅ Key row v2 adds the keyboard toggle, pgup/pgdn, one-tap `^C`/`^D` and sticky Shift alongside Ctrl/Alt, on top of the existing arrows and symbols, and it routes to the terminal or the workbench by focus.
- ✅ Hardware-chord forwarding is in: `MainActivity.dispatchKeyShortcutEvent()` hands system shortcut events to the focused surface so a Bluetooth keyboard behaves like desktop. It is *implemented but unverified*, because the lab Pixel has no hardware keyboard attached.

### Finding: the tap heuristic does not work, and why it matters

Tap-to-raise, which raises the IME when a tap lands on an editable, is implemented but **inert**. `WebView.onCheckIsTextEditor()` returns false even when Monaco's hidden textarea has focus, so Chromium never advertises an input connection for it. The `⌨` key works because it drives the IME at the window level, bypassing the view's editor status entirely.

The proper fix is an **IME proxy**: a zero-size, transparent native `EditText` overlaid on the WebView that holds IME focus, receives composition and commit events, and forwards characters to the workbench as key events. That also becomes the place to suppress autocorrect on code and to fix composing-region artifacts. It is tracked as the first item of M4 part 2.

### M4 part 2 — **LANDED 2026-08-07**

The IME proxy turned out to be unnecessary. A **JS bridge** (`WorkbenchBridge`) injected into the workbench solves both problems more directly, because the page knows things the WebView cannot tell us:

- ✅ **Tap-to-type works.** The bridge reports whether a tap landed inside `.monaco-editor`, and the host raises the IME when it did. **Verified:** tapping the editor now opens the keyboard by itself. (There is a known papercut: opening a file from the tree also raises it, because Monaco takes focus when a file opens.)
- ✅ **Native selection handles and an action bar are in.** The bridge polls the `.monaco-editor .selected-text` bounding rects, and the host draws Android-style handles and a copy / paste / expand / select-all bar over the WebView. **Verified on device** with a real selection. Autocorrect is already suppressed for code because input arrives as key events rather than composed text.
- ✅ A selection drag replays into Monaco as a **mouse gesture** (`dragSelect`), because Monaco has always handled mouse-drag selection correctly and it is only touch handles it lacks. The code path is in place, but **drag adjustment is unverified**, because it needs a real finger on a handle and adb's synthetic input cannot faithfully reproduce that. It must be tested on the Fold8.
- ✅ We also gained something for free: Monaco's own long-press already does word-select plus a context menu with cut/copy/paste, so the common case was better than assumed.
- ☐ The magnifier and configurable key-row layouts remain unbuilt. With the action bar and working handles in place, they are now polish rather than blockers.

## M4 (original scope, for reference)

This is the cheap fix for the one thing that genuinely *is* Monaco's fault. All of it runs against the public, documented Monaco API, and every line of interaction design here is reusable if an editing surface is ever swapped in (D9), so it is not throwaway under either future.

- Native selection handles and a magnifier are drawn as an Android overlay View above the WebView, driven by `getTargetAtClientPoint()` (px→position), `getScrolledVisiblePosition()` (position→px), `setSelection()` and `revealPositionInCenter()`.
- Key row v2 gains configurable layouts and injects via `trigger()` / `workbench.action.*`.
- `dispatchKeyEvent`/`dispatchKeyShortcutEvent` interception gives full hardware-keyboard chord fidelity, and we ship `"keyboard.dispatch": "keyCode"` in bundled settings, which is the known fix for the Android soft-keyboard backspace/Tab class of bugs.
- A clipboard bridge is added, and autocorrect is suppressed where it mangles code.
- Acceptance: a 30-minute soft-keyboard-only editing session passes without rage, text selection by touch is as good as a native Android text field, and the personal top-20 shortcuts work on a Bluetooth keyboard.

## M5a — Measurement gate (2 weeks of real use, ~0 dev time)

**Do not start a native editing surface before this.** Instrument and log real session time: the minutes spent typing in the editor versus reading diffs, working in the terminal, or driving an agent. If in-editor typing is under ~15% of session time, which is likely given the agent-CLI workflow, then the native editor rewrite has not earned 6–9 months and the project stops here with a fold-native app that works. Revisit D12/D9 with the number in hand.

## M4 — Toolchains & languages (3–4 weeks, parallelizable with M3)

- An in-app package manager UI sits over pkg/apt and offers curated "language packs": **Python** (python, pyright/basedpyright, debugpy, ruff), **Web/TS** (node, tsserver, eslint, prettier), **C/C++** (clang, clangd; DAP per S-spike), **Rust** (rustup path), plus git-extras, ssh, ripgrep and jq.
- Open VSX curation ships a tested starter extension pack and documents the known-broken extensions.
- proot-distro integration is an optional install for glibc-only tools.
- SSH remote mode means vetting the Open Remote SSH extension, or else an ssh+tmux flow in the terminal.
- Backup and export cover git-push-everything guidance and project export to /sdcard/SAF.
- Acceptance: from a fresh install, guided setup reaches productive Python and TS dev (completion, format, debug, test) in < 15 min.

## M5 — AI agent cockpit — **LANDED 2026-08-07**

The cockpit is built on tmux rather than on any agent's API, so it works with `pi`, Claude Code, aider, or a plain build. Decision D7 is what kept it tool-agnostic.

- ✅ **State comes from `tmux capture-pane`**: the cockpit reads the persistent session's visible pane and classifies it working / idle / awaiting-input. The classification is a heuristic by necessity, since agents do not announce state, but it only drives a chip and a notification, so a wrong guess is cheap.
- ✅ **The pocket workflow works**: `SessionService` polls that classification while a session runs and raises a **high-importance notification** ("Agent needs you") on transition into awaiting-input, so the phone can be in a pocket.
- ✅ **Steering** is a reply box plus one-tap `yes` / `no` / `↵`, delivered with `tmux send-keys`, so nothing steals focus and it works while the editor is open.
- ✅ **Review** shows a coloured unified diff of the working tree. It is unified only, because split diffs are unreadable at phone width (docs/05).
- ✅ **Commit and push both work** from the cockpit. **Verified end-to-end:** we made a change, saw it in the diff, committed, and watched status go `1 changed` → `0 changed`.
- ✅ The cockpit is the **default surface when folded**, because the phone is demonstrably good at reviewing and approving, not at typing.

One bug was found and fixed during testing. The cockpit polled tmux *and* git status every 3s while the diff request was also in flight, and the concurrent Termux round-trips starved the diff, which silently rendered "No changes". The pollers are now mutually exclusive.

## M5a — Measurement gate — **INSTRUMENTED 2026-08-07**

`UsageTracker` records the time spent in editor / terminal / cockpit / projects, and the `status` screen renders it as bars plus the one number decision D12 hinges on, which is the editor's share of tracked time. It is local only, and nothing leaves the device. The gate can now be settled with data after a couple of weeks of real use instead of with a hunch.

## M6 — Hardening — **LANDED 2026-08-07**

- ✅ **Risk R16 is closed, and the security hole is fixed.** A per-install token (`SecureRandom`, stored in prefs) now gates every localhost surface: code-server runs with `--auth password` using it, and the WebView logs in silently so no login screen is ever shown, while the terminal bridge and resize port both require an `AUTH <token>` line before doing anything. **Verified adversarially:** a connection from another process without the token receives `Kern: unauthorized` and gets no shell.
- ✅ **Health checks** live on the `status` screen and cover the battery exemption, free storage and RAM, Termux presence and permission, `allow-external-apps`, the kernel page size (read natively via `Os.sysconf`, because a minimal Termux has no `getconf`), the core vs optional toolchain, and a standing note about the phantom-process killer. Each failure states its fix.
- ✅ **Licensing compliance is done**: there is a GPLv3 `LICENSE`, and `NOTICE.md` documents the 22 vendored Termux files, the exact modifications made to them, the runtime dependencies and the trademark position.
- ✅ The **README** has been rewritten to describe the working product.
- ☐ An actual public release still needs an in-app updater, screenshots and a demo video, and a final trademark check on the name (decision D8).
- ✗ **F-Droid is out**, and this is settled rather than deferred. F-Droid is not a submission form, it is a build-recipe project that compiles from source on its own infrastructure. Kern ships five prebuilt Termux binaries in `jniLibs/`, one of them hand-patched, so a recipe would mean owning Termux's Android patches to PRoot forever. Distribution is through GitHub Releases, and Obtainium consumes that shape directly. IzzyOnDroid, which accepts the maintainer's own APK, is the only sideload index worth revisiting.

## M5 (original scope, for reference)

- The Session Service gains an agent session type (Claude Code first), which parses the output stream, detects waiting-for-input, and notifies on approval-needed and on completion.
- Cover cockpit v2 adds question cards (approve/deny/reply), live diff review (unified, per-hunk), and one-thumb commit of agent work.
- The pocket workflow runs like this: kick off the agent unfolded → pocket the phone → get a notification → approve from the cover screen → unfold to review. This loop is the demo.
- Acceptance: the user completes a real agent-driven change end-to-end without ever opening the inner screen except to start it.

## M6 — Hardening & release (2–3 weeks + ongoing)

- The onboarding wizard is finalised against the docs/05 list, and first-run health checks (pagesize, phantom toggle state, battery exemption) come with in-app fixes and deep links.
- Session reattach gets polished, thermal and battery state is surfaced, and a storage manager reports the bootstrap size and prunes caches.
- There are three update channels — the app APK (GitHub Releases plus in-app updater), the bootstrap packages (pkg), and pinned code-server bumps — and the cadence of each must be documented.
- A GPLv3 compliance pass covers source publication and licence screens, a trademark check settles the final name (D8), and the README, screenshots and demo video are produced.
- Acceptance: a stranger with a Fold8 can go from the GitHub release to productive without reading source code.

## Post-v1 candidate tracks (unordered)

- A CodeMirror-6-based satellite quick-editor could serve the cover screen and instant-open (D9).
- A targetSdk-28 fallback variant becomes worthwhile if the linker64 path degrades.
- The layouts could be tuned for the Fold8 Ultra, the Flip8 and tablets, and for the trifold when it matters.
- Voice-driven agent input and local model integration (Gemini Nano 4 via ML Kit) are both candidates.
- Community features could include plugin and config sharing and a key-row layout gallery.

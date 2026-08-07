# 06 — Roadmap

Estimates assume one developer working part-time with heavy AI assistance. Each milestone
ends with something you *use daily*, so motivation compounds instead of dying in a
6-month tunnel (the solo-maintainer graveyard lesson). Total to a daily-drivable v1:
**~4–6 months part-time**.

## M0 — Feasibility spikes on the real device (1–2 weeks)

Goal: retire the existential risks before writing app code. All on the actual Fold8.

| Spike | Question | Kill criterion |
|---|---|---|
| S1 | `getconf PAGESIZE` in Termux | If 16KB and termux-packages still broken → pivot to self-built 16KB packages or delay |
| S2 | Termux + `pkg install tur-repo code-server`, run workbench in browser | Unusably slow on flagship hardware → rethink (not expected; 12GB/8-Elite-Gen-5) |
| S3 | Phantom-killer toggle exists & works on One UI 9? Background survival test (30-min build, screen off) | No toggle + aggressive kills → lean harder on split-screen UX + reattach semantics |
| S4 | system_linker_exec on One UI 9 / Android 17 (test termux-play-store build) | Broken → ship targetSdk 28 variant instead (decision lever, not project-killer) |
| S5 | WebView (not Chrome) rendering workbench: perf, service workers, IME behavior, keyboard | WebView-specific blockers → evaluate bundling a WebView alternative or Chrome Custom Tab fallback |
| S6 | Measure: inner/cover window dp + density, split-screen sizes, posture API values | (data gathering, no kill) |
| S7 | Claude Code under Termux on-device against a repo in $HOME | Broken → agent layer becomes SSH-remote-first |
| S8 | code-server vs OpenVSCode Server side-by-side (patch friction, extension installs from Open VSX, platform-gating workaround) | Confirms D3 |
| S9 | Python (pyright, debugpy) + Node/TS servers from Open VSX on-device | LSP/DAP failures → identify patches needed per language |

### Spike results (live)

- **S1 ✅ (2026-08-06):** `getconf PAGESIZE` → **4096** on the owner's Fold8 (16GB/1TB).
  4KB pages — termux-packages works as-is. **Risk R1 retired.**
- **S2 ✅ (2026-08-06):** Termux + `tur-repo` + `code-server` installed and ran first
  try; workbench opened and was usable in the browser on-device. Baseline feasibility
  confirmed on real hardware.

Deliverable: decisions D3/D4 confirmed in docs/08; risk register updated; go/no-go.

## M1 — Walking skeleton (3–5 weeks) — **IN PROGRESS (started 2026-08-06)**

One APK that opens the workbench on a real project. Per updated D4: M1 uses **companion
mode** (drives the user's Termux via RUN_COMMAND); the self-contained bootstrap
installer moves to the embedded-runtime stage (M4+).

- ✅ Android app scaffold: Kotlin, Compose, single activity, `resizeableActivity`,
  edge-to-edge, AMOLED dark theme. (`app/` — first cut written 2026-08-06)
- ✅ Companion runtime driver: RUN_COMMAND start/stop of code-server on port 13337,
  idempotent start script, guided Termux-side setup command.
- ✅ Session Service: `specialUse` FGS + wakelock; health-polls `/healthz`; auto-restart
  on death; status notification with stop action.
- ✅ WebView IDE surface: workbench on localhost, cleartext scoped to 127.0.0.1,
  external links to browser, auto-retry on main-frame errors, reconnect overlay.
- ✅ First input-layer cut (M2 preview): key row (Esc/Tab/sticky Ctrl·Alt/arrows/
  symbols/combo chips) injected as real platform key events; system back = Esc.
- ✅ Posture tracking surfaced in status strip (M3 foundation).
- ✅ **End-to-end validated on real hardware (lab Pixel, 2026-08-06).** Evidence:
  server killed (`LISTEN=0`) → cold app start → tap Start IDE →
  `RUN_COMMAND[start] dispatched OK` → `LISTEN=1` → full workbench rendered in-app with
  status strip and key row. Foreground service confirmed
  (`isForeground=true types=SPECIAL_USE`). Auto-restart supervision observed recovering
  a killed server unprompted.
- ☐ Validation on the Fold8 itself (postures, cover screen, split-screen, DeX).
- ☐ Project-open flow polish (`?folder=` picker), settings, session reattach UX.
- ☐ Dark-theme sync (workbench currently loads its default light theme).

### Bugs found and fixed during M1 validation

1. **`startService` → `startForegroundService`**: Termux's `RunCommandService` calls
   `startForeground()` on itself, so a plain `startService()` is dropped on Android 12+
   when Termux has no visible activity. Symptom: intent silently did nothing.
2. **Self-matching `pgrep` guard** (the subtle one): the idempotency check
   `pgrep -f 'code-server.*:13337'` matched *the bash process running the script*, because
   that script's own command line contains the server's command line. It therefore always
   concluded "already running" and never started the server. Replaced with a pidfile
   (`~/.foldcode/server.pid` + `kill -0`). **Never use `pgrep -f` for a pattern that
   appears in the checking script itself.**
3. **No logging**: the first failure was undiagnosable. Added `Log`/`FoldCode` tag
   throughout the runtime and session layers, plus `TermuxRuntime.lastError` surfaced in
   the UI.
4. **Adopt-existing-server**: if a server is already listening (started by hand in
   Termux), the service now adopts it instead of failing or double-starting.
- Acceptance: cold start → editing a cloned repo with syntax highlighting + integrated
  terminal in ≤ 3 taps; survives app switch + screen-off for 10 min.

> **Re-sequenced 2026-08-07 per decision D12.** The native *shell* comes before any
> native *editor*, because the shell delivers 100% of the fold-native goal with 0% of the
> editor risk — and the app stays fully working the entire way. Native-editor work is
> gated behind a measurement (M5a), not assumed.

## M2 — Fold-native shell around the workbench — **CORE LANDED 2026-08-07**

Validated on the lab Pixel: workbench chrome gone, native chrome driving it, mode
switching Cover↔Unfolded on rotation, IME docking the key row and resizing panes
instead of glitching. Screenshots in the session log. Remaining M2 items are listed
after the checklist.

Everything the user actually complained about (docs/10) is fixed here, without touching
the editing surface.

- Posture/window engine: `FoldingFeature` + size classes → the four-mode state machine.
- **Native chrome replaces workbench chrome**: our own tab bar, file tree/rail, command
  palette, and status strip in Compose — the WebView is handed *only the editor pane*,
  with the workbench's own activity bar / panel / status bar / title bar hidden via
  bundled settings (`workbench.activityBar.location: hidden`, `statusBar.visible: false`,
  zen-mode-style layout). This is the fix for "desktop UI doesn't fit" (F1).
- **IME insets owned natively**: the editor pane resizes with `WindowInsets.ime()`, the
  key row docks above the keyboard, caret stays visible. Fix for F2.
- Tabletop mode: editor pane on the upright half, terminal + key row on the flat half,
  aligned to hinge bounds.
- Cover Companion v1: session status + output tail + notification deep links.
- Continuity: config-change handling, exact-state restore, WebView retention across
  activity recreation (also fixes the M1 "reloads on re-entry" limitation).
- DeX pass: desktop windowing resize/density, pointer support, top/bottom snapping.
- Acceptance: unfolded layout fits with 48dp targets and no horizontal squeeze; opening
  the keyboard never displaces the caret or breaks layout; fold/unfold mid-edit loses
  nothing; tabletop mode usable for a real REPL session.

### Landed in M2 (verified on device)

- ✅ `DisplayMode` state machine (Cover / Unfolded / Tabletop / Desktop) from window
  width + `FoldingFeature`; posture shown in the native top bar. Verified switching
  `cover` → `unfolded` on rotation.
- ✅ **Workbench chrome hidden** via settings pushed to
  `~/.local/share/code-server/User/settings.json` on every session start (base64-piped to
  avoid shell quoting): activity bar, status bar, menu bar, layout controls, minimap,
  secondary side bar (AI chat panel), plus dark theme, `keyboard.dispatch: keyCode`, and
  workspace-trust off. The editor now fills the window edge-to-edge.
- ✅ **Native chrome**: top bar with session dot, posture label, and chips
  (files / open / cmd / term / chat) that drive the workbench through real platform key
  events. Verified `files` toggling the explorer via Ctrl+B.
- ✅ **Native IME ownership**: shell owns `systemBars` + `imePadding()`; opening the
  keyboard resizes the editor pane and docks the key row above the IME. **Fixes F3→F2
  from docs/10.**
- ✅ **WebView retention** (`WorkbenchWebView`, process-scoped behind a
  `MutableContextWrapper`) so posture/rotation changes re-parent rather than reload —
  fixes the M1 "reloads on re-entry" limitation.
- ✅ Key row v1.5: sticky Ctrl/Alt/**Shift**, arrows, home/end, save/find, symbol strip.
- ✅ Tabletop layout: editor above the crease, control deck below, hinge-thickness gap
  with nothing interactive on it (native terminal fills the bottom half in M3).
- ✅ Deterministic layout reset: VS Code persists layout in localStorage, which survives
  a settings change, so a `LAYOUT_EPOCH` counter wipes web storage once when workbench
  settings change materially.

### Still open in M2

- ☐ Native tab bar (currently the workbench's own editor tabs).
- ☐ Native file tree (explorer still comes from the workbench; needs a command-with-result
  channel — Termux `RUN_COMMAND` supports a result `PendingIntent`).
- ☐ Cover-screen scoping — compact currently renders the full editor; the one-handed
  cockpit is M5, not a fallback IDE.
- ☐ DeX/desktop verification on real hardware; Fold8 posture verification (the lab Pixel
  cannot produce a `FoldingFeature`, so Tabletop is untested on-device).
- ☐ **Known gap (M4):** tapping inside the Monaco editor does not reliably raise the soft
  keyboard (matches monaco-editor#4946). Tapping workbench inputs (quick open) does. This
  is exactly the class of problem the M4 native input overlay addresses.

## M3 — Native terminal — **CORE LANDED 2026-08-07**

Fixes F3. Verified on the lab Pixel: real shell prompt, real command execution, tap
raises the keyboard, key row docked above the IME.

### Architecture: why a bridge instead of a local pty

Upstream Termux forks its shell with a JNI `createSubprocess()`. **We cannot do that**:
Termux's binaries live in *Termux's* private data directory under a different UID, so our
app can neither read nor exec them (and W^X blocks exec from our own writable storage
anyway — docs/03). The shell must therefore be hosted by Termux and attached to over a
transport.

    FoldCode (native TerminalView + emulator)
        │  TCP 127.0.0.1:13338
        ▼
    socat TCP-LISTEN,fork  →  ~/.foldcode/shell.sh  →  pty  →  bash -li   [inside Termux]

- Vendored **22 upstream Java files** from termux-app (`terminal-emulator` +
  `terminal-view`, GPLv3) unmodified — emulator, buffer, renderer, view, text selection.
- **Replaced only `TerminalSession`** with a socket-backed version keeping the identical
  public API, so `TerminalView` needed no changes. `JNI.java` deleted — the app now
  contains no native terminal code at all.
- Bridge started automatically by `SessionService` once the server is healthy; `socat` is
  auto-installed if missing; idempotent via `~/.foldcode/bridge.pid`.
- Size handshake: the client sends `SIZE rows cols` as the first line; the wrapper applies
  it with `stty` and turns echo on *before* exec'ing the shell, so the handshake is never
  visible.

### Landed

- ✅ Native terminal renders and executes real Termux commands (verified
  `echo FOLDCODE_NATIVE_TERM_OK` round-trip and `ls`).
- ✅ **Tapping the terminal raises the soft keyboard** — the exact thing the WebView
  terminal (and Monaco) fails at.
- ✅ Key row routes to whichever surface has focus (terminal vs workbench) and shares
  sticky modifier state with the terminal's client.
- ✅ Layouts: tabletop = editor above the crease / terminal below; unfolded = 60/40
  editor-over-terminal split; compact = one surface at a time via the `term`/`editor`
  chip.
- ✅ Resize debounced (400 ms) so a layout burst produces one `stty`, not six.

### Known limitations (M3 v1)

- ~~Resize echoes a ` stty rows N cols N` line~~ — **FIXED 2026-08-07** (reported in real
  use). Root cause: raw TCP has no window-resize channel, so the size was sent as terminal
  *input* and the shell echoed it; every keyboard show/hide produced a line.
  **Fix: an out-of-band control port.** The shell wrapper publishes its pty path
  (`tty > ~/.foldcode/tty`); a second socat listener on **13339** runs
  `stty -F <pty> rows R cols C`; the app opens a short-lived connection per resize.
  Nothing touches the shell's stdin, so nothing echoes. Verified against Linux
  `pty_resize()` (drivers/tty/pty.c): TIOCSWINSZ on the slave updates both ends **and**
  sends SIGWINCH to the foreground process groups, so vim/htop reflow correctly; it is
  also idempotent, so an unchanged size is a no-op. Confirmed on device: repeated keyboard
  toggles produce zero output, and `stty size` reports the live pane size.
- ~~Single session only; no reattach~~ — **SESSION PERSISTENCE LANDED 2026-08-07.** The
  bridge wrapper now ends with `exec tmux -f ~/.foldcode/tmux.conf new-session -A -s
  foldcode` instead of `exec bash -li`. Closing the app detaches the client; the tmux
  server keeps the shell running inside Termux, and the next connection reattaches and
  redraws. tmux is auto-installed alongside socat. Config: status bar off (rows are
  scarce and we supply chrome natively), `escape-time 10` so ESC in vim is not laggy,
  20k-line history, `screen-256color` (tmux-256color terminfo is not guaranteed present),
  `aggressive-resize on`. **Verified: wrote a marker, force-stopped the app, relaunched —
  the session came back with its scrollback intact.** Resize still works because tmux
  reacts to SIGWINCH on the client pty, which the control port triggers.
- Multiple named sessions/tabs are still not exposed in the UI (one shared `foldcode`
  session).
- Requires `socat` (auto-installed on first bridge start).
- Terminal is *not* yet the target of the M5 agent cockpit — that reads the same session.

## M3.5 — Workspace management — **LANDED 2026-08-07** (user-requested)

The enabling piece is a **command-with-result channel**: `TermuxCommand.run()` dispatches
a script to Termux with a `com.termux.RUN_COMMAND_PENDING_INTENT` and awaits the result
bundle (`result` → `stdout`/`stderr`/`exitCode`). Extra names and bundle keys verified
against termux-app's `TermuxConstants`. The PendingIntent must be **MUTABLE** — Termux
fills the results into it. This gives the native UI a general way to ask the Termux
filesystem questions without a terminal or the workbench in the loop, and it is the
foundation for the native file tree and git status later.

- ✅ **Open a folder**: native projects screen lists `~/projects` (one shell round-trip
  returning `name<TAB>isRepo`), marks git repos, offers `~` as a fallback, and remembers
  recents in SharedPreferences across restarts. Selecting one reloads the workbench at
  `?folder=<path>` — verified switching workspace on device.
- ✅ **Git clone**: paste a URL → `git clone --depth 1` into `~/projects/<name>` (git
  auto-installed if missing), with progress state, duplicate-name detection, and the last
  line of git's own error surfaced on failure. On success the project opens immediately.
  Verified end-to-end: cloned a real GitHub repo and the workbench switched to it.
- ✅ `proj` chip in the native top bar; chip row scrolls so it never squeezes the posture
  label.

Still open here: browsing outside `~/projects` (only home + projects for now), SSH-key /
credential support for private repos, and clone progress streaming (currently a spinner
until git finishes, because RUN_COMMAND returns only on completion).

## M4 — Native input layer over Monaco — **PART 1 LANDED 2026-08-07**

### Landed (verified on device)

- ✅ **Explicit keyboard control** — the `⌨` key in the key row shows/hides the IME via
  `WindowInsetsControllerCompat`. This is the fix for the M2 blocker (tapping Monaco never
  raised the keyboard, matching monaco-editor#4946). **Verified end-to-end: keyboard
  raised, typed into README.md, text inserted, dirty indicator appeared.** Editing on the
  phone now actually works.
- ✅ Key row v2: keyboard toggle, pgup/pgdn, one-tap `^C`/`^D`, sticky Shift alongside
  Ctrl/Alt, plus the existing arrows/symbols; routes to terminal or workbench by focus.
- ✅ Hardware-chord forwarding: `MainActivity.dispatchKeyShortcutEvent()` hands system
  shortcut events to the focused surface so a Bluetooth keyboard behaves like desktop.
  *Implemented but unverified* — the lab Pixel has no hardware keyboard attached.

### Finding: the tap heuristic does not work, and why it matters

Tap-to-raise (raise the IME when a tap lands on an editable) is implemented but **inert**:
`WebView.onCheckIsTextEditor()` returns false even when Monaco's hidden textarea has
focus, so Chromium never advertises an input connection for it. The `⌨` key works because
it drives the IME at the window level, bypassing the view's editor status entirely.

The proper fix is an **IME proxy**: a zero-size, transparent native `EditText` overlaid on
the WebView that holds IME focus, receives composition/commit events, and forwards
characters to the workbench as key events. That also becomes the place to suppress
autocorrect on code and to fix composing-region artifacts. Tracked as the first item of
M4 part 2.

### M4 part 2 — **LANDED 2026-08-07**

The IME proxy turned out to be unnecessary. A **JS bridge** (`WorkbenchBridge`) injected
into the workbench solves both problems more directly, because the page knows things the
WebView cannot tell us:

- ✅ **Tap-to-type works.** The bridge reports whether a tap landed inside
  `.monaco-editor`; the host raises the IME when it did. **Verified:** tapping the editor
  now opens the keyboard by itself. (Known papercut: opening a file from the tree also
  raises it, because Monaco takes focus when a file opens.)
- ✅ **Native selection handles + action bar.** The bridge polls
  `.monaco-editor .selected-text` bounding rects; the host draws Android-style handles and
  a copy / paste / expand / select-all bar over the WebView. **Verified on device** with a
  real selection. Autocorrect is already suppressed for code because input arrives as key
  events rather than composed text.
- ✅ Selection drag replays as a **mouse gesture** into Monaco (`dragSelect`) — Monaco has
  always handled mouse-drag selection correctly; it is only touch handles it lacks. The
  code path is in place but **drag adjustment is unverified** — it needs a real finger on
  a handle, which adb's synthetic input cannot faithfully reproduce. Test on the Fold8.
- ✅ Also gained for free: Monaco's own long-press already does word-select plus a
  context menu with cut/copy/paste, so the common case was better than assumed.
- ☐ Magnifier and configurable key-row layouts remain unbuilt; with the action bar and
  working handles they are now polish rather than blockers.

## M4 (original scope, for reference)

The cheap fix for the one thing that genuinely *is* Monaco's fault. All against public,
documented Monaco API — and every line of interaction design here is reusable if an
editing surface is ever swapped in (D9), so it is not throwaway under either future.

- Native selection handles + magnifier drawn as an Android overlay View above the
  WebView, driven by `getTargetAtClientPoint()` (px→position),
  `getScrolledVisiblePosition()` (position→px), `setSelection()`,
  `revealPositionInCenter()`.
- Key row v2: configurable layouts, injection via `trigger()` / `workbench.action.*`.
- `dispatchKeyEvent`/`dispatchKeyShortcutEvent` interception for full hardware-keyboard
  chord fidelity; ship `"keyboard.dispatch": "keyCode"` in bundled settings (the known
  fix for the Android soft-keyboard backspace/Tab class of bugs).
- Clipboard bridge; suppress autocorrect where it mangles code.
- Acceptance: 30-minute soft-keyboard-only editing session without rage; text selection
  by touch is as good as a native Android text field; personal top-20 shortcuts work on
  a Bluetooth keyboard.

## M5a — Measurement gate (2 weeks of real use, ~0 dev time)

**Do not start a native editing surface before this.** Instrument and log real session
time: minutes typing in the editor vs reading diffs vs in the terminal vs driving an
agent. If in-editor typing is under ~15% of session time — likely, given the agent-CLI
workflow — the native editor rewrite has not earned 6–9 months and the project stops
here with a fold-native app that works. Revisit D12/D9 with the number in hand.

## M4 — Toolchains & languages (3–4 weeks, parallelizable with M3)

- In-app package manager UI over pkg/apt: curated "language packs" —
  **Python** (python, pyright/basedpyright, debugpy, ruff), **Web/TS** (node, tsserver,
  eslint, prettier), **C/C++** (clang, clangd; DAP per S-spike), **Rust** (rustup path),
  plus git-extras, ssh, ripgrep, jq.
- Open VSX curation: tested starter extension pack; document known-broken extensions.
- proot-distro integration (optional install) for glibc-only tools.
- SSH remote mode: Open Remote SSH extension vetted, or ssh+tmux flow in terminal.
- Backup/export: git-push-everything guidance + project export to /sdcard/SAF.
- Acceptance: fresh install → productive Python + TS dev (completion, format, debug,
  test) in < 15 min guided setup.

## M5 — AI agent cockpit — **LANDED 2026-08-07**

Built on tmux rather than on any agent's API, so it works with `pi`, Claude Code, aider,
or a plain build (decision D7 kept it tool-agnostic).

- ✅ **State from `tmux capture-pane`**: the cockpit reads the persistent session's
  visible pane and classifies it working / idle / awaiting-input. Heuristic by necessity
  — agents do not announce state — but it only drives a chip and a notification, so a
  wrong guess is cheap.
- ✅ **Pocket workflow**: `SessionService` polls that classification while a session runs
  and raises a **high-importance notification** ("Agent needs you") on transition into
  awaiting-input, so the phone can be in a pocket.
- ✅ **Steering**: reply box plus one-tap `yes` / `no` / `↵`, delivered with
  `tmux send-keys` — no focus stealing, works while the editor is open.
- ✅ **Review**: coloured unified diff of the working tree (unified only; split diffs are
  unreadable at phone width, docs/05).
- ✅ **Commit and push** from the cockpit. **Verified end-to-end:** made a change, saw it
  in the diff, committed, watched status go `1 changed` → `0 changed`.
- ✅ Cockpit is the **default surface when folded** — the phone is demonstrably good at
  reviewing and approving, not at typing.

Bug found and fixed during testing: the cockpit polled tmux *and* git status every 3s
while the diff request was also in flight, and the concurrent Termux round-trips starved
the diff, which silently rendered "No changes". Pollers are now mutually exclusive.

## M5a — Measurement gate — **INSTRUMENTED 2026-08-07**

`UsageTracker` records time spent in editor / terminal / cockpit / projects, and the
`status` screen renders it as bars plus the one number decision D12 hinges on: the
editor's share of tracked time. Local only, nothing leaves the device. The gate can now
be settled with data after a couple of weeks of real use instead of a hunch.

## M6 — Hardening — **LANDED 2026-08-07**

- ✅ **Risk R16 closed — the security hole is fixed.** A per-install token
  (`SecureRandom`, stored in prefs) now gates every localhost surface: code-server runs
  with `--auth password` using it (the WebView logs in silently, so no login screen is
  ever shown), and the terminal bridge and resize port require an `AUTH <token>` line
  before doing anything. **Verified adversarially:** a connection from another process
  without the token receives `FoldCode: unauthorized` and gets no shell.
- ✅ **Health checks** (`status` screen): battery exemption, free storage and RAM, Termux
  presence and permission, `allow-external-apps`, kernel page size (read natively via
  `Os.sysconf` — a minimal Termux has no `getconf`), core vs optional toolchain, and a
  standing note about the phantom-process killer. Each failure states the fix.
- ✅ **Licensing compliance**: GPLv3 `LICENSE`, and `NOTICE.md` documenting the 22
  vendored Termux files, the exact modifications made to them, runtime dependencies, and
  the trademark position.
- ✅ **README** rewritten to describe the working product.
- ☐ Remaining for an actual public release: in-app updater, F-Droid metadata,
  screenshots/demo video, and a final trademark check on the name (decision D8).

## M5 (original scope, for reference)

- Agent session type in Session Service (Claude Code first): output stream parsing,
  waiting-for-input detection, notification on approval-needed/completion.
- Cover cockpit v2: question cards (approve/deny/reply), live diff review
  (unified, per-hunk), one-thumb commit of agent work.
- Pocket workflow: kick off agent unfolded → pocket phone → notification → approve from
  cover screen → unfold to review. This loop is the demo.
- Acceptance: complete a real agent-driven change end-to-end without ever opening the
  inner screen except to start.

## M6 — Hardening & release (2–3 weeks + ongoing)

- Onboarding wizard final (docs/05 list); first-run health checks (pagesize, phantom
  toggle state, battery exemption) with in-app fixes/deep links.
- Session reattach polish; thermal/battery surfacing; storage manager (bootstrap size,
  cache pruning).
- Update channels: app APK (GitHub Releases + in-app updater) vs bootstrap packages
  (pkg) vs pinned code-server bumps — document the cadence.
- GPLv3 compliance pass (source publication, license screens); trademark check on final
  name (D8); README/screenshots/demo video.
- Acceptance: a stranger with a Fold8 can go from GitHub release → productive without
  reading source code.

## Post-v1 candidate tracks (unordered)

- CodeMirror-6-based satellite quick-editor for cover screen / instant-open (D9).
- F-Droid submission; targetSdk-28 fallback variant if linker64 path degrades.
- Fold8 Ultra + Flip8 + tablet layout tuning; trifold when it matters.
- Voice-driven agent input; local model integration (Gemini Nano 4 via ML Kit).
- Community: plugin/config sharing, key-row layout gallery.

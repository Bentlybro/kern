# 08 — Decision Records

Format: one entry per load-bearing decision. Status: **Accepted** / **Proposed (confirm
at milestone)** / **Open**. Revisit triggers listed where they exist.

---

## D1 — Target device: Galaxy Z Fold8 (standard/wide) first — **Accepted**

Design for the 4:3 landscape-first inner display + squat 10:16 cover. Responsive support
for other Android 13+ arm64 devices, but only the Fold8 experience is *designed*.
Consequence: posture/mode engine is core architecture, not an adaptation layer.

## D2 — Execution model: Termux-style userland + system_linker_exec — **Accepted**

Bootstrap termux-packages aarch64 binaries into an app-private prefix; exec via
`/system/bin/linker64` wrapping (termux-exec) with modern targetSdk. Fallback lever:
targetSdk-28 build variant. Rejected: cloud-only (Dcoder death), WASM-only (can't run
real toolchains), nativeLibraryDir-only (frozen at build time), root-required anything.
Revisit if: Google closes linker64 (→ targetSdk 28) or 16KB pages break packages (R1).

## D3 — VS Code layer: code-server — **Proposed (confirm M0-S8)**

Over OpenVSCode Server because: proven under Termux (official docs + TUR package), Open
VSX default, active cadence. Both MIT; Microsoft's VS Code Server is proprietary and
banned from consideration. Carry minimal documented patch set; pin + monthly bumps.

## D4 — Runtime packaging: **staged** — companion mode now, embedded later — **Accepted (updated 2026-08-06)**

Reality check from M0: termux-packages binaries hardcode Termux's install prefix
(`/data/data/com.termux/...`), so a different applicationId cannot just unpack them —
embedding requires building a custom-prefix bootstrap via termux-packages' fork support
(Linux CI job). Staged plan:

- **Stage 1 (M1, shipped):** *companion mode* — the app drives the user's existing
  Termux install via the official `RUN_COMMAND` intent API (permission
  `com.termux.permission.RUN_COMMAND` + `allow-external-apps=true`). Full native shell
  value now, zero package infrastructure, full toolchain performance.
- **Stage 2 (M4+):** *embedded runtime* — CI-built custom-prefix bootstrap (GitHub
  Actions + termux-packages build system), vendored termux-exec (linker64), one
  self-contained APK. Implements the same `Runtime` interface; no UI changes.

Consequence: GPLv3 planning unchanged (stage 2 embeds Termux-derived code). Companion
mode stays supported even after stage 2 (cheap, and useful for Termux power users).

## D5 — Distribution: sideload-first (GitHub Releases), no Play for v1 — **Accepted**

Play's API-level + policy posture is structurally hostile to real toolchains. F-Droid
possible post-v1. Register Google developer verification when the 2027 global rollout
approaches (R8).

## D6 — Language priority: Python, then Web/TS, then C/C++, Rust — **Accepted**

Matches the author's actual work. Each language ships as a curated, tested "pack"
(runtime + LSP + DAP + formatter) rather than advertising 60 half-working languages
(landscape lesson #3).

## D7 — AI agents are first-class, not a feature flag — **Accepted**

Terminal-resident CLI agents (Claude Code first) + native cockpit (notifications,
cover-screen approve/deny, diff review). Rationale: the 2025-26 evidence says agent
supervision is the mobile-native input method; it also halves the importance of the
keyboard problem. Cockpit reads generic pty/git state — no hard dependency on any one
agent vendor (R15).

## D8 — Name: "Kern" is a codename only — **Open**

Must not contain/evoke "VS Code"/"Visual Studio". Trademark + Play/Store/collision check
before first public release (M6). Candidates welcome; pick late, it's cheap to rename
before release and expensive after.

## D12 — Native fold-native SHELL first; keep the workbench as the editing surface — **Accepted (2026-08-07)**

The question asked: "can we use code-server's APIs but build a native Android IDE UI?"
Research (docs/research2/, 7 agents incl. adversarial + verification passes) produced a
conclusion neither the user nor the assistant expected, which both adversarial agents
reached independently:

**The three complaints that motivate a native UI do not require replacing the editor.**

| Complaint | Requires native editor? | Actual fix |
|---|---|---|
| Desktop UI doesn't fit the Fold8 | **No** | Native panes/chrome; give the workbench only the editor pane and hide its redundant bars |
| Keyboard opening glitches the layout | **No** | Native `WindowInsets.ime()` on the host pane |
| Terminal is unusable | **No** | Termux `terminal-view` as its own native pane — bypasses xterm.js entirely |

`WebView` is a plain `android.view.View`. It drops into a fold-aware Compose scaffold
pane *exactly* as sora's `CodeEditor` would. Tabletop mode, cover-screen layout,
hinge-aligned panes, native tabs, native file tree, native diff, native terminal — all of
these are host-layout concerns and are achievable **with the workbench still in the editor
pane**. "It can't be fold-native" was a false premise and is struck from the rationale.

The only thing that genuinely requires replacing Monaco is **in-editor touch text
manipulation** (draggable selection handles, magnifier, IME fidelity). That gap is real
and permanent — monaco-editor#1504 has sat in Microsoft's Backlog since 2019-07-09,
unassigned; the one community PR (#4623) was closed unmerged with zero maintainer review.
But it is **compensable from outside the WebView**: Monaco's public, documented API
exposes `getTargetAtClientPoint()` (px→position), `getScrolledVisiblePosition()`
(position→px), `setSelection()`, `revealPositionInCenter()` and `trigger()` — precisely
what is needed to draw *native* Android selection handles and a magnifier as an overlay
View. Estimated 3–4 weeks, versus 6–9 months for the LSP half of a native editor alone.

Calibration that decided it: **Klyx** (native Android IDE, sora + LSP) = one developer,
854 commits, 14 months, still self-described alpha. **Code on the Go** (AndroidIDE's
successor) = 12+ contributors above 20 commits, funded nonprofit, ~18 months to R1.
Meanwhile **Acode** — the most-used third-party Android code editor (6.4k stars, ~3.6M
downloads, shipping since 2019) — is a *WebView* editor. Survivorship discriminates on
**scope**, not on rendering technology.

**Decision:** build the native shell around the existing workbench, in this order —
(1) fold-native chrome, (2) native terminal, (3) native input overlay over Monaco,
(4) *measure real usage*, and only then consider swapping the editing surface. The
interaction design built in step 3 is reusable if we later swap in sora, so it is not
throwaway work under either future. See revised roadmap in docs/06.

Also settled: **code-server is not a decaying base** — v4.131.0 (2026-07-30) tracks Code
1.131.0, monthly cadence, version parity with upstream. There is no burning platform.

## D13 — If we ever go native: a self-designed extension bridge, NOT the remote protocol — **Accepted in principle, deferred**

Two rejected and one preferred approach for a future native editing surface:

- ❌ **Speak VS Code's remote protocol from Kotlin.** Verified real and technically
  reachable (the OSS signing handshake is a no-op — `AbstractSignService.validate()`
  returns true for empty-id messages — and the commit guard is skippable by omitting the
  field). Still wrong: two hand-rolled serialization layers (13-byte binary framing +
  a bespoke VSBuffer type-tagged serializer), zero schema, zero documentation, monthly
  upstream churn — to obtain files/pty/ripgrep, which we already have on-device. Language
  intelligence isn't even on that connection; it lives behind the extension-host RPC.
  No third-party client exists in any language.
- ❌ Microsoft's official VS Code Server — license forbids reverse engineering and
  standalone/combined redistribution. code-server (MIT) is the only legally safe target.
- ✅ **A small custom VS Code extension inside code-server**, exposing a JSON-RPC/WebSocket
  API *we design*, backed by the public, documented, semver-stable extension API (all 24
  `vscode.execute*Provider` commands are documented with no stability caveat and no
  proposed-API gate). Our Kotlin client then speaks a protocol we own and that cannot be
  broken out from under us, while the extension host does the language work.

Correction to note: this does **not** "preserve the VS Code extension ecosystem" —
code-server already uses Open VSX only (Microsoft's Marketplace ToS restricts it to
Microsoft products; Pylance, MS C/C++ and Remote-* are already unavailable). Justify the
bridge on **protocol ownership and zero backend work**, never on marketplace breadth.

## D9 — Touch-first satellite editor (CodeMirror 6) — **Superseded by D12**

Kept alive only as a fallback behind the editor seam. If an editing surface is ever
swapped in, sora-editor 0.24.6 is the primary candidate — **vendored into our tree**, not
consumed as a remote dependency (its head tracks AGP 9.2.1/Kotlin 2.3.10 with a single
maintainer at 73% of all commits; Code on the Go deliberately pins the frozen 0.23.6
rather than following head, and had to patch into sora's package namespace for
package-private APIs). LGPL-2.1-or-later is clean against GPLv3, and vendoring makes the
relinking obligation trivial. Known gaps: code folding (issue #85, milestone 0.25.0) and
multi-cursor (issue #798, no milestone, no comments — treat as permanently absent).

If M2's input work can't make the workbench pleasant for quick edits, or cover-screen
editing demand emerges: CM6-based instant-open editor sharing the same FS + git (the
Replit-proven mobile editor). Explicitly out of v1 to protect scope (R12).

## D10 — Project storage: app-private POSIX home; DocumentsProvider out — **Accepted**

`$HOME/projects` under `getFilesDir()`; never SAF for repos (2× I/O + IPC latency);
share our tree to other apps via DocumentsProvider; optional MANAGE_EXTERNAL_STORAGE
power-user mode. Backup = git remotes + export flows (M4); uninstall-loses-data is a
documented, onboarded fact.

## D11 — UI stack: Kotlin + Jetpack Compose + Material 3 Adaptive — **Accepted**

WindowManager 1.5.1+ (`FoldingFeature`, BREAKPOINTS_V2 size classes),
material3-adaptive 1.2+ scaffolds, single-activity architecture. Views only where
Compose lacks parity (possibly the WebView container and terminal surface).

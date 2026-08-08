# 08 — Decision Records

The format is one entry per load-bearing decision. The status of each is **Accepted**, **Proposed (confirm at milestone)** or **Open**. Revisit triggers are listed where they exist.

---

## D1 — Target device: Galaxy Z Fold8 (standard/wide) first — **Accepted**

We design for the 4:3 landscape-first inner display and the squat 10:16 cover screen. Other Android 13+ arm64 devices get responsive support, but only the Fold8 experience is *designed*. The consequence is that the posture/mode engine is core architecture rather than an adaptation layer.

## D2 — Execution model: Termux-style userland + system_linker_exec — **Accepted**

We bootstrap the termux-packages aarch64 binaries into an app-private prefix and exec them by wrapping `/system/bin/linker64` (termux-exec) with a modern targetSdk. The fallback lever is a targetSdk-28 build variant. We rejected cloud-only, which is what killed Dcoder; WASM-only, which can't run real toolchains; nativeLibraryDir-only, which is frozen at build time; and anything that requires root. Revisit this if Google closes linker64 (→ targetSdk 28) or if 16KB pages break packages (R1).

## D3 — VS Code layer: code-server — **Proposed (confirm M0-S8)**

We chose it over OpenVSCode Server because it is proven under Termux (official docs and a TUR package), because it defaults to Open VSX, and because its cadence is active. Both are MIT; Microsoft's VS Code Server is proprietary and banned from consideration. We carry a minimal documented patch set, and we pin versions and bump monthly.

## D4 — Runtime packaging: **staged** — companion mode now, embedded later — **Accepted (updated 2026-08-06)**

The reality check from M0 is that termux-packages binaries hardcode Termux's install prefix (`/data/data/com.termux/...`), so an app with a different applicationId cannot just unpack them. Embedding therefore requires building a custom-prefix bootstrap via termux-packages' fork support, which is a Linux CI job. The plan is staged:

- **Stage 1 (M1, shipped)** is *companion mode*, in which the app drives the user's existing Termux install via the official `RUN_COMMAND` intent API (the permission `com.termux.permission.RUN_COMMAND` plus `allow-external-apps=true`). It delivers the full native shell value now, with zero package infrastructure and full toolchain performance.
- **Stage 2 (M4+)** is the *embedded runtime*: a CI-built custom-prefix bootstrap (GitHub Actions plus the termux-packages build system), a vendored termux-exec (linker64), and one self-contained APK. It implements the same `Runtime` interface, so no UI changes are needed.

The consequence is that GPLv3 planning is unchanged, since stage 2 embeds Termux-derived code. Companion mode stays supported even after stage 2, because it is cheap and useful for Termux power users.

## D5 — Distribution: sideload-first (GitHub Releases), no Play for v1 — **Accepted**

Play's API-level and policy posture is structurally hostile to real toolchains. F-Droid is possible post-v1. We register Google developer verification when the 2027 global rollout approaches (R8).

## D6 — Language priority: Python, then Web/TS, then C/C++, Rust — **Accepted**

This ordering matches the author's actual work. Each language ships as a curated, tested "pack" (runtime, LSP, DAP and formatter) rather than advertising 60 half-working languages, which is landscape lesson #3.

## D7 — AI agents are first-class, not a feature flag — **Accepted**

Kern combines terminal-resident CLI agents (Claude Code first) with a native cockpit that handles notifications, cover-screen approve/deny and diff review. The rationale is that the 2025-26 evidence says agent supervision is the mobile-native input method, and it also halves the importance of the keyboard problem. The cockpit reads generic pty and git state, so there is no hard dependency on any one agent vendor (R15).

## D8 — Name: "Kern" is a codename only — **Open**

The name must not contain or evoke "VS Code" or "Visual Studio". A trademark, Play Store and collision check has to happen before the first public release (M6). Candidates are welcome, and we should pick late, because it's cheap to rename before release and expensive after.

## D12 — Native fold-native SHELL first; keep the workbench as the editing surface — **Accepted (2026-08-07)**

The question asked was "can we use code-server's APIs but build a native Android IDE UI?" The research (docs/research2/, 7 agents incl. adversarial and verification passes) produced a conclusion that neither the user nor the assistant expected, and that both adversarial agents reached independently:

**The three complaints that motivate a native UI do not require replacing the editor.**

| Complaint | Requires native editor? | Actual fix |
|---|---|---|
| The desktop UI doesn't fit the Fold8. | **No** | Use native panes and chrome, give the workbench only the editor pane, and hide its redundant bars. |
| Opening the keyboard glitches the layout. | **No** | Handle `WindowInsets.ime()` natively on the host pane. |
| The terminal is unusable. | **No** | Use Termux's `terminal-view` as its own native pane, which bypasses xterm.js entirely. |

`WebView` is a plain `android.view.View`. It drops into a fold-aware Compose scaffold pane *exactly* as sora's `CodeEditor` would. Tabletop mode, cover-screen layout, hinge-aligned panes, native tabs, a native file tree, a native diff and a native terminal are all host-layout concerns, and every one of them is achievable **with the workbench still in the editor pane**. "It can't be fold-native" was a false premise and is struck from the rationale.

The only thing that genuinely requires replacing Monaco is **in-editor touch text manipulation**, meaning draggable selection handles, a magnifier and IME fidelity. That gap is real and permanent: monaco-editor#1504 has sat unassigned in Microsoft's Backlog since 2019-07-09, and the one community PR (#4623) was closed unmerged with zero maintainer review. But it is **compensable from outside the WebView**, because Monaco's public, documented API exposes `getTargetAtClientPoint()` (px→position), `getScrolledVisiblePosition()` (position→px), `setSelection()`, `revealPositionInCenter()` and `trigger()`, which is precisely what is needed to draw *native* Android selection handles and a magnifier as an overlay View. That is estimated at 3–4 weeks, versus 6–9 months for the LSP half of a native editor alone.

The calibration that decided it runs as follows. **Klyx** (a native Android IDE built on sora plus LSP) is one developer, 854 commits and 14 months in, and still self-describes as alpha. **Code on the Go** (AndroidIDE's successor) has 12+ contributors above 20 commits and funded nonprofit backing, and took ~18 months to R1. Meanwhile **Acode**, the most-used third-party Android code editor (6.4k stars, ~3.6M downloads, shipping since 2019), is a *WebView* editor. Survivorship discriminates on **scope**, not on rendering technology.

**Decision:** we build the native shell around the existing workbench, in this order — (1) fold-native chrome, (2) native terminal, (3) native input overlay over Monaco, (4) *measure real usage* — and only then do we consider swapping the editing surface. The interaction design built in step 3 is reusable if we later swap in sora, so it is not throwaway work under either future. The revised roadmap is in docs/06.

One more thing is settled: **code-server is not a decaying base.** v4.131.0 (2026-07-30) tracks Code 1.131.0 on a monthly cadence, at version parity with upstream. There is no burning platform.

## D13 — If we ever go native: a self-designed extension bridge, NOT the remote protocol — **Accepted in principle, deferred**

There are two rejected approaches and one preferred approach for a future native editing surface:

- ❌ **Speaking VS Code's remote protocol from Kotlin.** We verified that this is real and technically reachable: the OSS signing handshake is a no-op, because `AbstractSignService.validate()` returns true for empty-id messages, and the commit guard is skippable by omitting the field. It is still wrong, because it means two hand-rolled serialisation layers (13-byte binary framing plus a bespoke VSBuffer type-tagged serialiser), zero schema, zero documentation and monthly upstream churn, all to obtain files/pty/ripgrep, which we already have on-device. Language intelligence isn't even on that connection; it lives behind the extension-host RPC. No third-party client exists in any language.
- ❌ **Microsoft's official VS Code Server.** Its licence forbids reverse engineering and standalone or combined redistribution. code-server (MIT) is the only legally safe target.
- ✅ **A small custom VS Code extension inside code-server.** It exposes a JSON-RPC/WebSocket API *we design*, backed by the public, documented, semver-stable extension API; all 24 `vscode.execute*Provider` commands are documented with no stability caveat and no proposed-API gate. Our Kotlin client then speaks a protocol we own and that cannot be broken out from under us, while the extension host does the language work.

One correction to note: this does **not** "preserve the VS Code extension ecosystem". code-server already uses Open VSX only, because Microsoft's Marketplace ToS restricts the Marketplace to Microsoft products, so Pylance, MS C/C++ and Remote-* are already unavailable. Justify the bridge on **protocol ownership and zero backend work**, never on marketplace breadth.

## D9 — Touch-first satellite editor (CodeMirror 6) — **Superseded by D12**

This decision is kept alive only as a fallback behind the editor seam. If an editing surface is ever swapped in, sora-editor 0.24.6 is the primary candidate, **vendored into our tree** rather than consumed as a remote dependency: its head tracks AGP 9.2.1/Kotlin 2.3.10 with a single maintainer at 73% of all commits, and Code on the Go deliberately pins the frozen 0.23.6 rather than following head, having had to patch into sora's package namespace to reach package-private APIs. LGPL-2.1-or-later is clean against GPLv3, and vendoring makes the relinking obligation trivial. The known gaps are code folding (issue #85, milestone 0.25.0) and multi-cursor (issue #798, no milestone, no comments, so treat it as permanently absent).

If M2's input work can't make the workbench pleasant for quick edits, or if cover-screen editing demand emerges, the answer is a CM6-based instant-open editor sharing the same FS and git, which is the mobile editor Replit has proven. It is explicitly out of v1 to protect scope (R12).

## D10 — Project storage: app-private POSIX home; DocumentsProvider out — **Accepted**

Projects live in `$HOME/projects` under `getFilesDir()`. We never use SAF for repos, because it costs 2× the I/O plus IPC latency. We share our tree to other apps via a DocumentsProvider, and offer an optional MANAGE_EXTERNAL_STORAGE power-user mode. Backup is git remotes plus the export flows (M4), and the fact that uninstalling loses the data is documented and covered in onboarding.

## D11 — UI stack: Kotlin + Jetpack Compose + Material 3 Adaptive — **Accepted**

We use WindowManager 1.5.1+ for `FoldingFeature` and the BREAKPOINTS_V2 size classes, material3-adaptive 1.2+ for the scaffolds, and a single-activity architecture. We fall back to Views only where Compose lacks parity, which possibly means the WebView container and the terminal surface.

# 07 — Risk Register

Severity × likelihood, with mitigations and the milestone where each gets retired or
re-checked. The graveyard research (docs/research/ide-landscape.md) is the origin of
half of these.

| # | Risk | Sev | Like | Mitigation | Checkpoint |
|---|---|---|---|---|---|
| R1 | ~~**16KB page kernel on Fold8** breaks termux-packages binaries~~ **RETIRED 2026-08-06**: owner's Fold8 reports `PAGESIZE=4096` — termux-packages works as-is. Residual: re-check after major One UI/kernel OTAs | ~~Critical~~ | ~~Med~~ | M0-S1 done; code-server already confirmed running on-device (S2) | ✅ M0 |
| R2 | **Phantom process killer / One UI process death** kills sessions | High | High (unmitigated) | Dev-options toggle in onboarding; ≤ ~15 child processes by design; FGS + wakelock + battery exemption; tmux-semantics reattach so death ≠ loss | M0-S3, M1, M6 |
| R3 | **One UI little-core pinning** of backgrounded heavy work | Med | High | Keep-visible UX (split-screen norm, keep-screen-on option), honest progress UX, remote offload for long builds | M0-S3 |
| R4 | **Google closes system_linker_exec** | High | Low-Med | Fallback: targetSdk-28 build variant (works while installable floor stays ≤ 28 — true through Android 16/17); monitor termux-play-store | ongoing |
| R5 | **Workbench touch/IME jank in WebView** below daily-usability bar | High | Med | M2 is a dedicated milestone; key row + native interception; hardware-kb-first honesty; escape hatch = CM6 satellite editor (D9) | M0-S5, M2 |
| R6 | **code-server upstream churn** breaks our patch set | Med | High (over time) | Pin versions; monthly bump ritual; minimal documented patches; OpenVSCode as plan B; never track daily | ongoing |
| R7 | **Solo-maintainer burnout** (killed AndroidIDE, CodeAssist, VHEditor…) | High | Med | Ship usable increments every milestone; ruthless not-building list (no editor core, no package repos, no cloud); open source early for bus-factor | every M |
| R8 | **Developer verification** (global 2027) complicates sideloading | Med | Med | Register free dev tier when needed; ADB escape hatch documented; F-Droid contested — don't depend | M6, 2027 |
| R9 | **Battery/thermal reality** disappoints (3× slower builds, 15-min throttle) | Med | High | Set expectations in UX; thermal surfacing; SSH offload path; agents-do-the-typing reduces sustained load | M4 |
| R10 | **Licensing misstep** (MS marks, marketplace, proprietary extensions) | High | Low | Hard rules in docs/04; Open VSX only; no "VS Code" in name; GPLv3 compliance pass in M6 | M6 |
| R11 | **Extension gaps on Open VSX** (no Pylance/cpptools/Remote-SSH) | Med | Certain | Curated replacements (pyright/basedpyright, clangd, Open Remote SSH); document honestly; most OSS extensions present (16k+) | M4 |
| R12 | **Scope creep** toward two editors / Android-app-building / cloud services | Med | Med | Non-goals list in docs/01; D9 explicitly post-v1; ADR discipline | every M |
| R13 | **WebView platform quirks** (service workers, localhost, key events) vary by WebView version | Med | Med | Pin minimum WebView version; first-run health check; test on WebView not Chrome from day 1 | M0-S5 |
| R14 | **Fold8-specific unknowns** (density buckets, Seamless Screen behavior, app screen zoom interactions) | Low | Med | M0-S6 measurement; One UI 9 behaviors tested on-device each milestone | M0, M3 |
| R15 | **Claude Code / agent CLIs change** their TTY behavior, breaking cockpit parsing | Med | Med | Cockpit reads generic pty streams + git state, not tool-specific APIs; approval detection heuristic per-tool and configurable | M5 |

| R16 | ✅ **CLOSED 2026-08-07.** Per-install token gates all three localhost ports; code-server uses `--auth password` with silent WebView login, the bridge requires `AUTH <token>`. Verified: unauthenticated connection is refused. Original description follows. ~~**Loopback shell/IDE is unauthenticated.**~~ code-server (13337, `--auth none`), the terminal bridge (13338) and the resize control port (13339) are all reachable by *any app on the device holding INTERNET permission* — 13338 in particular hands out a writable shell running as Termux's UID. Android does not isolate localhost between apps | High | Med | Personal sideloaded build today, so exposure is bounded — but must be closed before any public release. Options: a per-session token in the ttyd/`-c` style, an `Authorization` check, or moving the transport to SSH (which authenticates by construction) | before release |

## Standing assumptions to re-verify occasionally

- targetSdk-28 installs remain allowed (floor = 24 as of Android 16).
- Termux F-Droid + termux-play-store both alive (two proofs the exec paths still work).
- code-server's Termux docs page still maintained (canary for upstream caring).
- One UI 9.x point releases not tightening background policy further.

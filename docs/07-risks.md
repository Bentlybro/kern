# 07 — Risk Register

Each risk is scored as severity × likelihood, and carries a mitigation and the milestone where it gets retired or re-checked. The graveyard research in docs/research/ide-landscape.md is the origin of half of these.

| # | Risk | Sev | Like | Mitigation | Checkpoint |
|---|---|---|---|---|---|
| R1 | ~~A **16KB page kernel on the Fold8** breaks the termux-packages binaries.~~ **RETIRED 2026-08-06**: the owner's Fold8 reports `PAGESIZE=4096`, so termux-packages works as-is. A residual risk remains, so re-check after major One UI/kernel OTAs. | ~~Critical~~ | ~~Med~~ | M0-S1 is done, and S2 already confirmed code-server running on-device. | ✅ M0 |
| R2 | The **phantom process killer and One UI process death** kill running sessions. | High | High (unmitigated) | Onboarding walks the user through the dev-options toggle, the design keeps to ≤ ~15 child processes, the app runs an FGS with a wakelock and a battery exemption, and tmux-semantics reattach means death ≠ loss. | M0-S3, M1, M6 |
| R3 | **One UI pins backgrounded heavy work to the little cores.** | Med | High | The UX keeps work visible by making split-screen the norm and offering a keep-screen-on option, progress is reported honestly, and long builds can be offloaded remotely. | M0-S3 |
| R4 | **Google closes system_linker_exec.** | High | Low-Med | The fallback is a targetSdk-28 build variant, which works while the installable floor stays ≤ 28, and that holds true through Android 16/17. We also monitor termux-play-store. | ongoing |
| R5 | **Touch and IME jank in the WebView workbench** falls below the daily-usability bar. | High | Med | M2 is a dedicated milestone for it, we add the key row and native interception, we are honest that a hardware keyboard comes first, and the escape hatch is the CM6 satellite editor (D9). | M0-S5, M2 |
| R6 | **Upstream churn in code-server** breaks our patch set. | Med | High (over time) | We pin versions, keep a monthly bump ritual, hold the patches minimal and documented, keep OpenVSCode as plan B, and never track daily. | ongoing |
| R7 | **Solo-maintainer burnout** ends the project, as it killed AndroidIDE, CodeAssist, VHEditor and others. | High | Med | We ship usable increments every milestone, keep a ruthless not-building list (no editor core, no package repos, no cloud), and open the source early for the bus factor. | every M |
| R8 | **Developer verification**, going global in 2027, complicates sideloading. | Med | Med | We register the free dev tier when it is needed and document the ADB escape hatch. F-Droid is contested, so we do not depend on it. | M6, 2027 |
| R9 | **The battery and thermal reality** disappoints, with builds 3× slower and a 15-min throttle. | Med | High | We set expectations in the UX, surface the thermal state, offer an SSH offload path, and let agents do the typing, which reduces sustained load. | M4 |
| R10 | **A licensing misstep** over Microsoft's marks, the marketplace, or proprietary extensions. | High | Low | docs/04 sets hard rules, we use Open VSX only, the name contains no "VS Code", and M6 carries a GPLv3 compliance pass. | M6 |
| R11 | **Open VSX has extension gaps**, with no Pylance, cpptools or Remote-SSH. | Med | Certain | We curate replacements (pyright/basedpyright, clangd, Open Remote SSH) and document the gaps honestly. Most OSS extensions are present (16k+). | M4 |
| R12 | **Scope creeps** towards two editors, Android app building, or cloud services. | Med | Med | docs/01 carries the non-goals list, D9 is explicitly post-v1, and we keep ADR discipline. | every M |
| R13 | **WebView platform quirks** in service workers, localhost and key events vary by WebView version. | Med | Med | We pin a minimum WebView version, run a first-run health check, and test on WebView rather than Chrome from day 1. | M0-S5 |
| R14 | **Fold8-specific unknowns** remain around density buckets, Seamless Screen behaviour and app screen zoom interactions. | Low | Med | M0-S6 measures them, and we test One UI 9 behaviours on-device each milestone. | M0, M3 |
| R15 | **Claude Code and other agent CLIs change** their TTY behaviour and break the cockpit's parsing. | Med | Med | The cockpit reads generic pty streams and git state rather than tool-specific APIs, and the approval-detection heuristic is per-tool and configurable. | M5 |

| R16 | ✅ **CLOSED 2026-08-07.** A per-install token now gates all three localhost ports: code-server uses `--auth password` with a silent WebView login, and the bridge requires an `AUTH <token>` line. We verified that an unauthenticated connection is refused. The original description follows. ~~**The loopback shell and IDE are unauthenticated.**~~ code-server (13337, `--auth none`), the terminal bridge (13338) and the resize control port (13339) are all reachable by *any app on the device holding INTERNET permission*, and 13338 in particular hands out a writable shell running as Termux's UID. Android does not isolate localhost between apps. | High | Med | This is a personal sideloaded build today, so the exposure is bounded, but it must be closed before any public release. The options are a per-session token in the ttyd/`-c` style, an `Authorization` check, or moving the transport to SSH, which authenticates by construction. | before release |

## Standing assumptions to re-verify occasionally

- targetSdk-28 installs remain allowed, since the floor is 24 as of Android 16.
- Termux on F-Droid and termux-play-store are both still alive, which are two proofs that the exec paths still work.
- code-server's Termux docs page is still maintained, which is our canary for whether upstream still cares.
- One UI 9.x point releases have not tightened the background policy any further.

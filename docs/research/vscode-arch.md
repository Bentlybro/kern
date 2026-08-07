# vscode-arch

## Summary
VS Code's core (Code-OSS) is MIT-licensed and genuinely reusable, but everything that makes the branded product "VS Code" — the name/logo, the Visual Studio Marketplace, the proprietary VS Code Server binary, and Microsoft's own extensions (Pylance, C/C++, C#, Remote Development) — is proprietary and contractually restricted to official Microsoft builds. The proven path to a VS Code-class IDE running fully on-device on Android is an APK that wraps a locally running code-server/OpenVSCode Server (Node on bionic via Termux-style bootstrap) in a WebView; VHEditor-Android and Code FA both ship this today and were still releasing in Dec 2025. The web workbench runs a full Node extension host server-side, so terminals, debuggers, and most extensions work — unlike vscode.dev/github.dev, whose web-worker extension host has no terminal or debugger. LSP and DAP are editor-independent JSON-RPC protocols, so a Monaco/CodeMirror custom IDE can get language intelligence without any VS Code code, but it forfeits the entire extension ecosystem. Extension supply must come from Open VSX (Eclipse Foundation) since Microsoft's marketplace ToS bars unofficial builds; Open VSX covers most popular OSS extensions but can never carry Microsoft's proprietary ones.

## Key facts
- Code-OSS (github.com/microsoft/vscode) is MIT-licensed; the branded 'Visual Studio Code' download is a Microsoft-licensed distribution adding trademarked branding, telemetry keys, update service config, Marketplace integration, and proprietary Remote Development connection code (per microsoft/vscode wiki 'Differences' page).
- Visual Studio Marketplace access is 'governed by the Marketplace Terms of Use' and restricted to official Visual Studio family products — forks (VSCodium, code-server, OpenVSCode Server, any Android build) legally cannot use it and default to Open VSX instead.
- Open VSX is run by the Eclipse Foundation (vendor-neutral, ~600M monthly downloads claimed, 10k+ extensions, Open VSX 1.0.0 released ~June 2026); it lacks Microsoft's proprietary extensions: Pylance, C/C++ (cpptools), C#/C# Dev Kit, Remote-SSH/WSL/Containers, Live Share.
- In April 2025 Microsoft added runtime environment checks to the C/C++ extension that hard-blocked it in VSCodium and Cursor; the license says it 'may be used only with Microsoft Visual Studio, Visual Studio for Mac, Visual Studio Code...' (The Register, 2025-04-24). Expect the same for any Android build.
- The VS Code Server binary (used by Remote-SSH, tunnels, and downloaded by 'code serve-web') is proprietary: license (code.visualstudio.com/license/server) forbids providing it 'as a stand-alone offering', hosting/sharing/renting it, and transfer to third parties — it cannot legally power a shipped Android product; use OpenVSCode Server or code-server (both MIT-based) instead.
- vscode.dev/github.dev run only a web-worker extension host: no terminal, no debugger, no Node extensions ('the terminal and debugger are not available... you can't compile, run, and debug a Rust or Go application within the browser sandbox'); github.dev has zero compute (edits via GitHub API).
- code-server (Coder) applies patches on top of a VS Code submodule and adds self-hosting features (auth, its own marketplace default = Open VSX); OpenVSCode Server (Gitpod) is a minimal direct fork tracking upstream ('scoped at only making VS Code available as-is in the web browser'). Both were actively releasing into late 2025/early 2026 (OpenVSCode v1.109.5; code-server tracking Code 1.103+).
- VS Code process model: Electron main + renderer (workbench/Monaco UI) + extension host (separate Node utility process on desktop, web worker on web, remote Node process in server mode) + pty host process (node-pty) + ripgrep child processes for search; renderer<->ext-host use a typed RPC (MainThread*/ExtHost* proxies).
- Native/binary dependencies: node-pty, @vscode/ripgrep (downloads a prebuilt rg binary — no android-arm64 prebuilt, must supply Termux's ripgrep or patch, cf. microsoft/vscode-ripgrep PR #18 'fix android platform'), @parcel/watcher file watcher, @vscode/sqlite3, @vscode/spdlog, native-keymap; all must be compiled against bionic libc with Termux's clang/node-gyp.
- code-server runs on Android under Termux today (official docs page coder.com/docs/code-server/termux): install via 'pkg install tur-repo && pkg install code-server'; known issues: process.platform='android' is treated as unsupported so only Web Extensions install by default (workaround: platform-spoof via node --require, or manual .vsix), Tab key needs 'keyboard.dispatch':'keyCode', git fails on /sdcard, soft-keyboard/IME quirks close menus, users report sluggish UI even on a Galaxy S22 Ultra.
- Shipping Android wrappers: VHEditor-Android (vhqtvn, ~1,000 stars, embeds Termux + code-server, latest release v2.21.0/v2.22.0-pre Dec 11-13 2025 adding 'mobile display mode') and Code FA (nightmare-space/code_lfa, ~1.2k stars, Flutter + WebView + code-server 4.103.1 running in a bundled Ubuntu rootfs, not Termux; large APK, maintainer responds slowly). Both prove technical feasibility of fully-local operation.
- code-server minimum server footprint per Coder docs is 1 GB RAM / 2 CPU cores (2 GB recommended) plus the browser/WebView rendering the workbench — realistic on-device budget is roughly 1.5-2.5 GB RAM total, fitting the Termux guide's 3 GB minimum / 6 GB recommended device spec.
- Monaco standalone is only the text editor core with 'simplified standalone services': no extension system, no TextMate grammars (Monarch highlighting instead), no file explorer/SCM/terminal/settings UI. CodinGame's monaco-vscode-api (MIT, actively maintained, 2,618 commits) re-plugs the real VS Code workbench + service overrides (web-worker extension host, virtual FS, search, debug, .vsix loading) onto custom backends — the practical basis for 'vscode-web with a non-Node backend'.
- LSP and DAP are editor-independent JSON-RPC protocols with public specs (microsoft.github.io/debug-adapter-protocol); servers like Pyright(OSS)/clangd/gopls and standalone DAP adapters (lldb-dap, debugpy, delve) work from any client (Neovim, Emacs, Sublime), so a custom Android IDE can have real language intelligence and debugging with zero VS Code code.

## Details
## Licensing map (the traps)
| Component | License | Usable in an Android product? |
|---|---|---|
| Code-OSS source | MIT | Yes (rebrand required — logos/name are trademarked) |
| Branded VS Code, product.json service keys | MS proprietary | No |
| Visual Studio Marketplace | ToS: official MS products only | No — use Open VSX |
| VS Code Server / Remote protocol binary | Proprietary (no standalone offering, no hosting, no transfer) | No |
| MS extensions: Pylance, C/C++, C#/DevKit, Remote-SSH/WSL/Containers, Live Share | Proprietary, product-locked; C/C++ enforces via env checks since Apr 2025 | No |
| OpenVSCode Server (Gitpod), code-server (Coder) | MIT (+patches) | Yes |
| Monaco, monaco-vscode-api, LSP/DAP SDKs | MIT | Yes |
| Open VSX registry | Eclipse-run, open | Yes (default for all forks) |

Replacement stack for the missing MS extensions: Python extension is OSS but Pylance is not (use Pyright/basedpyright), clangd replaces cpptools, no real Remote-Dev replacement (Open Remote SSH forks exist).

## Architecture relevant to porting
Multi-process: main (Electron) / renderer workbench / extension host (Node utility process desktop; **web worker** on web; **remote Node process** in server mode) / pty host / file watcher (@parcel/watcher) / ripgrep spawned per search. In the server products the browser gets the workbench and a *full Node extension host runs server-side* — that is why local code-server on Android gets terminals, debuggers, and non-web extensions, unlike vscode.dev.

Native modules to port to bionic/ARM64: node-pty (compiles fine under Termux), @vscode/ripgrep (prebuilt download lacks android-arm64 — substitute Termux `ripgrep` binary), @parcel/watcher, @vscode/sqlite3, @vscode/spdlog, argon2 (code-server auth; needs source build on android-arm64). Termux ships nodejs-lts built for bionic; TUR repo now packages code-server directly (`pkg install tur-repo && pkg install code-server`). Biggest recurring pain: `process.platform === 'android'` is treated as unsupported → web-extensions-only gating (spoofable), plus soft-keyboard/IME defects (Tab key dispatch, autocorrect, menus closing) and touch targets designed for mouse.

## Web/server story
- **vscode.dev/github.dev**: web extension host only; no terminal/debugger; github.dev = no compute at all.
- **`code serve-web`**: official CLI serves full workbench locally, but it downloads the **proprietary** VS Code Server → unlicensable for redistribution.
- **OpenVSCode Server**: minimal tracking fork, releases v1.106.3 (Dec 2025) → v1.109.5 (Feb 2026); Open VSX default.
- **code-server**: patch-based, adds password auth/self-host features; ~weekly releases tracking upstream (4.103.1 confirmed in Code FA; later versions exist). Footprint: 1 GB RAM/2 cores min server-side, 2 GB recommended.

## Prior art on Android
VHEditor-Android (embedded Termux + code-server, native keyboard-input bridging, Dec 2025 releases) and Code FA (Flutter + WebView + code-server in bundled Ubuntu rootfs) both run fully offline. User reports: workable on 6-8 GB RAM devices, sluggish workbench rendering in WebView/browser even on flagship phones, keyboard/IME the #1 complaint.

## Engineering cost (approx, to a shippable v1)
| Path | Effort | Notes |
|---|---|---|
| (a) WebView + local OpenVSCode/code-server | ~2-4 eng-months MVP; ongoing patch maintenance | Proven (VHEditor/Code FA). Get full ext host, terminal, DAP. Costs: bionic builds, keyboard/IME bridge, Android platform-gating patch, 200-500 MB APK, 1.5-2.5 GB RAM |
| (b) vscode-web workbench + custom non-Node backend (monaco-vscode-api or fork of remote agent) | ~9-18 eng-months + heavy monthly upstream churn | Must reimplement remote agent protocol/services; web-worker ext host limits extensions unless you also build a native ext host |
| (c) Monaco or CodeMirror + LSP/DAP client, custom UI | ~6-24 eng-months for credible IDE | Full control, small footprint; zero VS Code extension ecosystem; you build explorer/SCM/terminal/debug UI yourself |
| (d) Fully native IDE | multi-year, team-scale | Only justified if WebView performance/UX is disqualifying |

Path (a) is the only one with existence proofs of "VS Code-class on Android" and preserves Open VSX extension compatibility.

## Confidence notes
GitHub release dates read via WebFetch were unreliable on year: OpenVSCode Server v1.109.5 was reported as 'Feb 20, 2025' but VS Code 1.109 corresponds to early 2026 — almost certainly Feb 2026; treat as 'actively maintained into 2026'. The code-server releases fetch returned internally inconsistent data ('v4.131.0, July 30 2024, tracks Code v1.131.0' — no such VS Code version exists), so exact latest code-server version is unverified; the confirmed floor is 4.103.1 (via Code FA) with an active release cadence. Open VSX size figures conflict across sources ('10,000+ extensions' Wikipedia-era vs '600M monthly downloads' Eclipse PR; Microsoft marketplace is several times larger) — only the download claim and 1.0.0 release (~June 2026) came from 2026-dated sources. On-device RAM/performance numbers are anecdotal (Termux guide: 3 GB min/6 GB recommended; Coder docs: 1 GB/2-core server minimum; 'sluggish on S22 Ultra' is a single user report). Gitpod's corporate rebrand (to 'Ona') and its effect on openvscode-server stewardship was not verified this session. Exact VS Code Server license text was summarized by an intermediary model from code.visualstudio.com/license/server; key clauses ('stand-alone offering', no host/share/publish/rent/lease) are consistent with prior knowledge but re-read the license verbatim before relying on it. Cost estimates in the details are my synthesis from the prior-art projects' scope, not sourced figures.

## Sources
- Differences between the repository and Visual Studio Code (microsoft/vscode wiki): https://github.com/microsoft/vscode/wiki/Differences-between-the-repository-and-Visual-Studio-Code
- Visual Studio Code Server License: https://code.visualstudio.com/license/server
- VS Code for the Web docs (vscode.dev limitations): https://code.visualstudio.com/docs/setup/vscode-web
- VS Code Server / remote docs: https://code.visualstudio.com/docs/remote/vscode-server
- code-server on Termux (official Coder docs): https://coder.com/docs/code-server/termux
- code-server requirements (1 GB RAM / 2 cores): https://coder.com/docs/code-server/requirements
- VHEditor-Android (code-server + Termux APK): https://github.com/vhqtvn/VHEditor-Android
- Code FA / code_lfa (Flutter + WebView + code-server on Android): https://github.com/nightmare-space/code_lfa
- monaco-vscode-api (VS Code workbench services on Monaco): https://github.com/CodinGame/monaco-vscode-api
- The Register: Microsoft blocks C/C++ extension in VS Code forks (Apr 2025): https://www.theregister.com/2025/04/24/microsoft_vs_code_subtracts_cc_extension/


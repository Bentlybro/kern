# kotlin-lsp-dap

## VERDICT
A native Kotlin LSP client is a well-trodden path — LSP4J runs on Android today in at least one shipping library (sora-editor's `editor-lsp`), its only runtime dependency is Gson, and the three gotchas (minSdk 26 from `java.time`/`Method.getParameters()`, mandatory R8 keep rules for its dynamic proxies, and no process-launch transport) are all known and already solved in public code you can copy. A native DAP client is a port rather than a research project: `org.eclipse.lsp4j.debug` genuinely ships `IDebugProtocolClient`/`IDebugProtocolServer` and `DSPLauncher.createClientLauncher` taking the same InputStream/OutputStream pair as LSP, so one transport layer serves both — but I found no existing Android DAP client, so you are the first, with lsp4ij (JVM, LSP4J-based, LSP+DAP) as your template. The premise worth correcting is the exec question: your app should never exec a language server. The userland that already runs code-server should run the servers, and the app connects over a socket — sora-editor ships no process-launching transport at all, precisely because socket-only is the right shape. Use TCP on loopback if the servers live inside the separate Termux app (abstract-namespace `LocalSocket` is MLS-constrained across app sandboxes and may be blocked by SELinux); switch to `LocalSocket` if you bundle your own PRoot userland the way Klyx does. Since debugpy and vscode-js-debug are both TCP-native, Python and TypeScript are the two easiest possible starting targets. Budget 6–9 months solo for production quality, or 4–5 if you vendor `editor-lsp` for the LSP half — and note that the protocol plumbing is the cheap part (~20 KB in every editor ever written); the real cost is UTF-16-correct incremental sync and the dozen Compose UI surfaces each LSP and DAP feature demands.

## Summary
A native Kotlin LSP client on Android is a well-trodden path with two shipping reference implementations; a native DAP client is a port, not a research project, but has no Android precedent I could find. Eclipse LSP4J works on Android — verified by sora-editor's `editor-lsp` module, which ships LSP4J 1.0.0 in production — but it imposes a hard **minSdk 26** floor. I traced the exact cause to source: `MessageTracer`/`TracingMessageConsumer` import `java.time.Clock`, and `AnnotationUtil` calls `Method.getParameters()`, both API 26. LSP4J's runtime dependency tree is just Gson (no Guava, no Xtend), which makes it far more Android-friendly than its Eclipse origins suggest. Crucially, sora-editor ships **no process-launching transport at all** — only TCP sockets, Android `LocalSocket` on the abstract namespace, and custom streams — which is the correct architecture, because your app should never exec anything; the userland that already launches code-server should launch the language servers too. The most important correction to your premise: `LocalSocket`/abstract unix sockets are only reliable when the server runs under *your app's* UID, because SELinux MLS categories isolate `untrusted_app` domains from each other; if servers live inside the separate Termux app, use TCP on loopback. DAP is genuinely available: `org.eclipse.lsp4j.debug` ships `IDebugProtocolClient`/`IDebugProtocolServer` and `DSPLauncher.createClientLauncher(client, InputStream, OutputStream)` — the identical stream API as LSP, so one transport layer serves both. Budget roughly 6–9 months solo for production-quality Python + TypeScript LSP + DAP, or 4–5 months if you reuse sora-editor's `editor-lsp` for the LSP half.

## Key facts
- LSP4J 1.0.0 (released 2026-02-10) has exactly ONE runtime dependency: Gson. `org.eclipse.lsp4j` -> api `org.eclipse.lsp4j.jsonrpc` -> api gson [2.9.1,3.0). Guava and Xtend appear only as build/test dependencies, not in the shipped artifacts. This is the single strongest argument for LSP4J on Android.
- LSP4J imposes a hard Android minSdk 26 floor. VERIFIED root cause in source: `org.eclipse.lsp4j.jsonrpc.MessageTracer` and `TracingMessageConsumer` import `java.time.Clock`/`Instant`/`DateTimeFormatter` (API 26), and `services/AnnotationUtil.java:116` calls `Method.getParameters()` (`java.lang.reflect.Parameter`, API 26). `CompletableFuture` alone would only need API 24.
- sora-editor CONFIRMS this empirically: its root `build.gradle.kts` has `val highApiProjects = arrayOf("editor-lsp")` — editor-lsp is the ONLY module raised to minSdk 26; every other module is minSdk 23. Core library desugaring is used nowhere in the repo (grep count = 0); they simply raised minSdk.
- lsp4j issue #496 'Error when creating LanguageClient on Android' is the smoking gun: `NoClassDefFoundError` in `ServiceEndpoints.getSupportedMethods`, with logcat showing 'Rejecting re-init on previously-failed class ... MessageTracer' — the java.time failure cascading through class init. Only reproduced on API 21; newer versions worked.
- LSP4J relies on `Proxy.newProxyInstance` (ServiceEndpoints.java lines 41 and 54) plus annotation reflection over interface methods. R8/ProGuard WILL break it silently without keep rules. sora-editor ships `consumer-rules.pro` with `-keep class org.eclipse.lsp4j.* { *; }`, `-keep class org.eclipse.lsp4j.services.* { *; }`, `-keep class org.eclipse.lsp4j.jsonrpc.messages.* { *; }`.
- DAP in LSP4J is real and confirmed: `org.eclipse.lsp4j.debug` module ships `IDebugProtocolClient` + `IDebugProtocolServer` (43 `@JsonRequest`/`@JsonNotification` methods) and `DSPLauncher.createClientLauncher(IDebugProtocolClient, InputStream, OutputStream)`. Same InputStream/OutputStream signature as LSP, so ONE transport layer serves both protocols.
- sora-editor's editor-lsp deliberately has NO process-launching transport. Its only `StreamConnectionProvider` implementations are `SocketStreamConnectionProvider` (TCP), `LocalSocketStreamConnectionProvider` (`android.net.LocalSocket` + `LocalSocketAddress.Namespace.ABSTRACT`), and `CustomConnectProvider`. This is the architecturally correct answer to the exec problem: don't exec, connect.
- TRANSPORT WARNING (partly INFERRED): Android assigns per-app SELinux MLS categories since Android 9. Two different `untrusted_app` processes have different categories, so abstract-namespace unix socket `connectto` across an app-sandbox boundary is MLS-constrained. `LocalSocket` is reliable only when the server runs under YOUR app's UID (bundled userland). For servers inside the separate Termux app, TCP on 127.0.0.1 is the robust choice. Verify on-device before committing.
- Klyx (github.com/klyx-dev/klyx, Kotlin/Compose Android editor, updated 2026-08-06) REJECTED LSP4J and hand-rolled the entire LSP type system: 259 files, ~582 KB of Kotlin, using kotlinx-serialization-json + kotlinx-io, minSdk 28. Its JSON-RPC transport is only ~22 KB of that (`JsonRpcConnection.kt` 18.4 KB + reader/writer/readHeaders). The types are the cost, not the transport.
- Klyx also solves the exec restriction cleanly at targetSdk 37: it bundles PRoot as a CMake git submodule (`app/src/main/cpp/proot`), sets `PROOT_LOADER`, and uses `useLegacyPackaging = true` so native binaries land in `/data/app/*/lib/<arch>` (`apk_data_file` context) where exec is permitted. This is the Play-Store-compatible alternative to Termux's targetSdk-28 freeze.
- Android exec restriction precisely: untrusted apps with targetSdkVersion >= 29 cannot `exec()` files in their home directory (SELinux 'Enforce execve() restrictions for API > 28'); `dlopen()` still works. Termux avoids it by pinning targetSdkVersion 28, which is why it cannot ship on Google Play.
- Termux aarch64 language server availability VERIFIED from termux-packages build.sh files: rust-analyzer 20260803, gopls 0.23.0, lua-language-server 3.18.2, nodejs 26.4.0, python 3.14.6, openjdk-17/21/25. clangd has NO standalone package — it ships inside `clang` via the `bin/clang*` subpackage glob (libllvm 21.1.8, built with `LLVM_ENABLE_PROJECTS='clang;clang-tools-extra;lldb;mlir'`). pyright/basedpyright/typescript-language-server are npm/PyPI installs, not Termux packages.
- Debug adapters on Termux: codelldb 1.12.2 IS packaged (in `x11-packages/codelldb`, depends on lldb, flagged as broken on 32-bit Android so aarch64-only). vscode-js-debug runs standalone and is TCP-native: `node .../js-debug/src/dapDebugServer.js ${port} 127.0.0.1`, with readiness signalled by the stdout line 'Debug server listening at 127.0.0.1:PORT'. lldb-dap ships via the `bin/lldb*` glob (INFERRED — LLVM 21 builds it with the lldb project).
- Effort calibration from four real clients: the JSON-RPC transport layer costs ~20 KB in EVERY implementation (Neovim `rpc.lua` 23 KB, Helix `transport.rs` 19 KB, Klyx 22 KB). The expensive parts are the protocol types (Neovim 258 KB, Helix `helix-lsp-types` ~300 KB, Klyx 582 KB) and the editor integration (Neovim `client.lua` 55 KB, `sync.lua` + `_changetracking.lua` 30 KB just for incremental sync). LSP4J gives you the types for free.

## Details
## 1. LSP4J on Android — verdict: yes, with three hard constraints

**Artifact:** `org.eclipse.lsp4j:org.eclipse.lsp4j:1.0.0` (+ `org.eclipse.lsp4j:org.eclipse.lsp4j.debug:1.0.0` for DAP). Transitively pulls only `org.eclipse.lsp4j.jsonrpc` and Gson. No Guava, no Xtend at runtime — those are build/test-only. This is small and dexes fine.

| Constraint | Detail | Fix |
|---|---|---|
| minSdk 26 | `MessageTracer`/`TracingMessageConsumer` use `java.time.Clock`; `AnnotationUtil:116` uses `Method.getParameters()` | Set minSdk 26 (sora-editor's choice), or `coreLibraryDesugaring` desugar_jdk_libs 2.x to reach lower |
| R8 breaks it | `Proxy.newProxyInstance` + `@JsonRequest`/`@JsonNotification` reflection | Keep rules — copy sora-editor's `consumer-rules.pro` verbatim |
| Java 11 bytecode | class file v55 | Fine; AGP handles it. sora-editor compiles at Java 17 |

**Real Android usage:** sora-editor's `editor-lsp` module (`io.github.Rosemoe.sora-editor:editor-lsp`, latest 0.24.6) is a complete, production LSP client on LSP4J 1.0.0 + kotlinx-coroutines. Its `LanguageServerWrapper` gives you the whole boilerplate inventory: `LSPLauncher.createClientLauncher`, `Executors.newCachedThreadPool()`, `initialize()` with timeout, `initialized()` notification, capability merging against a declared fallback (`mergeCapabilities(res.capabilities, fallbackCapabilities)` — a workaround for servers that under-report), and crash supervision (`crashCount <= 3` → `restartAndReconnect()`, then give up). Document sync supports both modes, selected from the server's `TextDocumentSyncKind`.

**The alternative, priced:** Klyx hand-rolled everything in kotlinx.serialization — 259 files / 582 KB. That buys you coroutine-native APIs, no reflection, no R8 hazard, and minSdk freedom. It costs roughly 3–5 months. Unless you need minSdk < 26, take LSP4J.

## 2. Transport — your premise needs one correction

You framed this as "launching a child process is constrained." True, but **you should not launch anything**. The app is a client; the userland is the server host. Whatever already starts code-server starts the language servers.

| Transport | Cross-app (Termux) | Same-UID (bundled PRoot) | Notes |
|---|---|---|---|
| TCP 127.0.0.1 | ✅ Robust | ✅ | Needs INTERNET permission; visible to any app on device |
| `LocalSocket` ABSTRACT | ⚠️ SELinux MLS risk | ✅ Best | sora-editor's `LocalSocketStreamConnectionProvider` |
| Filesystem unix socket | ❌ | ✅ | Termux `$PREFIX` is 0700 under a different UID — unreachable |
| stdio via pty | ❌ | ✅ | Only if you own the process |

**Recommendation:** write against sora-editor's `StreamConnectionProvider` interface (4 methods) so transport is swappable, ship TCP first, and A/B `LocalSocket` on-device. If you ever bundle your own PRoot userland (Klyx's model — `useLegacyPackaging = true` puts execs in the lib dir, legal at targetSdk 37), `LocalSocket` becomes the better default.

Robustness against Android process-killing is a *supervision* problem, not a transport one. Both TCP and LocalSocket die when either side dies. You need: a foreground service holding the userland, connection-loss detection, backoff reconnect, and full document re-sync on reconnect.

## 3. Servers and adapters on Termux aarch64

| Server | Availability | Launch |
|---|---|---|
| pyright / basedpyright | npm / PyPI (not a Termux pkg) | `--stdio`; wrap with socat for TCP |
| typescript-language-server | npm | `--stdio`; needs wrapping |
| clangd | ✅ `pkg install clang` (LLVM 21.1.8) | `--stdio` |
| rust-analyzer | ✅ `pkg` (20260803) | stdio |
| gopls | ✅ `pkg` (0.23.0) | stdio; `-listen` gives TCP natively |
| lua-language-server | ✅ `pkg` (3.18.2) | stdio |
| jdtls | ⚠️ openjdk 17/21/25 exist; jdtls itself untested, heavy (OSGi) | — |

**Gotcha:** most servers only speak stdio. Since you want sockets, put a tiny supervisor in the userland (`socat TCP-LISTEN:PORT,reuseaddr,fork EXEC:"pyright-langserver --stdio"`, or a 50-line Python/Node shim). That shim is also your restart-on-crash mechanism — and it lives on the Linux side where process management actually works.

| Debug adapter | Status | Transport |
|---|---|---|
| debugpy | pip; pure-Python fallback (INFERRED, unverified on-device) | `python -m debugpy --listen 127.0.0.1:P --wait-for-client` → TCP ✅ |
| vscode-js-debug | Node 26.4 present; build `dapDebugServer.js` | `node dapDebugServer.js ${port} 127.0.0.1` → TCP ✅ |
| codelldb | ✅ Termux `x11-packages`, 1.12.2, aarch64-only | TCP |
| lldb-dap | INFERRED via `bin/lldb*` glob | stdio/TCP |

Both of your priority targets (Python, TypeScript) have TCP-native adapters. That is a genuinely lucky alignment — no pty plumbing needed. Caveat: *attach-to-PID* needs ptrace, which is restricted; **launch mode works, attach mode likely won't.**

## 4. What other editors teach

The reusable lesson is where the cost sits. Transport is ~20 KB everywhere (Neovim `rpc.lua` 23 KB, Helix 19 KB, Klyx 22 KB). Protocol types are 250–580 KB — LSP4J gives you those free. Editor integration is the real work: Neovim spends 30 KB on incremental sync alone (`sync.lua` + `_changetracking.lua`), because LSP positions are **UTF-16 code units**, and getting that wrong corrupts every edit in a file containing an emoji.

Best code to read: **lsp4ij** (redhat-developer, actively maintained) — an LSP4J-based LSP *and* DAP client whose `DAPClient.java` imports `IDebugProtocolServer`. It is the closest existing template for exactly what you're building, minus the IntelliJ UI. Helix's `helix-dap` (client 19 KB + transport 10 KB) shows how small a DAP core can be.

## 5. Effort

| Workstream | LSP4J path | Notes |
|---|---|---|
| Transport + lifecycle + supervision | 2–3 wks | Or ~0 if you vendor `editor-lsp` |
| Incremental sync, UTF-16 positions | 2–3 wks | Highest bug density |
| LSP feature UI in Compose | 8–12 wks | Completion, diagnostics, hover, defs, code actions, rename, sig help, inlay hints |
| Userland provisioning + shims | 3–4 wks | |
| DAP protocol client | ~2 wks | lsp4j.debug does the heavy lifting |
| Debugger UI | 8–10 wks | Breakpoints, stepping, vars, stack, watch, console |

**~6–9 months solo for production-quality Python + TypeScript LSP + DAP.** Reusing `editor-lsp` for the LSP half cuts it to **4–5 months**. Fold-native layout work is on top of all of this.

## Confidence
VERIFIED by reading primary source (GitHub API / shallow clone / official docs): LSP4J 1.0.0 release date and full dependency tree from each module's build.gradle; the java.time and Method.getParameters() imports in lsp4j.jsonrpc source; Proxy.newProxyInstance at ServiceEndpoints.java:41,54; lsp4j issue #496 body and comments; sora-editor's highApiProjects=["editor-lsp"] and minSdk 23/26 split; zero desugaring in sora-editor; the exact contents of its consumer-rules.pro, LocalSocketStreamConnectionProvider, ConnectionDsl, LanguageServerWrapper, and DocumentChangeEvent; the absence of any ProcessStreamConnectionProvider; DSPLauncher.createClientLauncher signature and the 43-method IDebugProtocolServer; Klyx's targetSdk 37 / useLegacyPackaging / PRoot submodule / 259-file hand-rolled LSP module; Termux build.sh versions for rust-analyzer, gopls, lua-language-server, nodejs, python, openjdk, libllvm 21.1.8 and its LLVM_ENABLE_PROJECTS line; codelldb 1.12.2 in x11-packages; the clang and lldb subpackage globs; file sizes for Neovim/Helix/Zed/lsp4ij LSP+DAP code; the js-debug dapDebugServer.js command line from lsp4ij docs.

INFERRED, flagged in the output and worth verifying on-device before you commit architecture: (1) the SELinux MLS claim that cross-app abstract unix sockets are blocked — the mechanism is documented by AOSP and corroborated by search, but I did not find a test case specifically for Android app A -> Android app B abstract LocalSocket, and this is load-bearing for your transport choice, so test it early with a 20-line spike; (2) lldb-dap shipping in Termux — the bin/lldb* glob would capture it and LLVM 21 builds it by default, but I did not confirm it in a package file listing; (3) debugpy working on Termux aarch64 — plausible via pydevd's pure-Python fallback but I found no confirmation, and no primary source on pyright/basedpyright/typescript-language-server actually running under Termux either (they are Node-based and Node 26.4.0 is packaged, so this is low-risk but untested).

Effort estimates are my judgment calibrated against the measured code sizes of four real clients, not data from anyone who has built this on Android — treat the 6–9 month figure as an order-of-magnitude planning number, not a bid. One thing I could not check: whether your existing setup runs code-server inside the Termux app or inside your own bundled userland. That single fact decides your transport and is worth stating explicitly before the architect proceeds.

## Sources
- sora-editor editor-lsp — production Android LSP client on LSP4J (LocalSocket transport): https://github.com/Rosemoe/sora-editor/blob/main/editor-lsp/src/main/java/io/github/rosemoe/sora/lsp/client/connection/LocalSocketStreamConnectionProvider.kt
- lsp4j issue #496 — Error when creating LanguageClient on Android (MessageTracer class-init failure): https://github.com/eclipse-lsp4j/lsp4j/issues/496
- Eclipse LSP4J — source, module layout, org.eclipse.lsp4j.debug (DSPLauncher, IDebugProtocolServer): https://github.com/eclipse-lsp4j/lsp4j
- Android app data file execute restrictions (targetSdk >= 29, W^X, native-lib workaround): https://github.com/agnostic-apollo/Android-Docs/blob/master/site/pages/en/projects/docs/apps/processes/app-data-file-execute-restrictions.md
- Klyx — Kotlin/Compose Android editor: hand-rolled kotlinx.serialization LSP, bundled PRoot, targetSdk 37: https://github.com/klyx-dev/klyx
- lsp4ij — LSP4J-based LSP *and* DAP client (closest existing template; DAPClient.java): https://github.com/redhat-developer/lsp4ij/blob/main/docs/dap/user-defined-dap/vscode-js-debug.md
- termux-packages — codelldb 1.12.2 build.sh (aarch64-only debug adapter): https://github.com/termux/termux-packages/blob/master/x11-packages/codelldb/build.sh
- termux-packages — libllvm 21.1.8 build.sh (clangd via clang-tools-extra, lldb): https://github.com/termux/termux-packages/blob/master/packages/libllvm/build.sh
- Neovim vim.lsp.rpc — reference JSON-RPC transport (~23 KB) for effort calibration: https://github.com/neovim/neovim/blob/master/runtime/lua/vim/lsp/rpc.lua
- AOSP SELinux concepts — MLS categories isolating untrusted_app domains: https://source.android.com/docs/security/features/selinux/concepts

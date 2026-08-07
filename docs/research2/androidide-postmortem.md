# androidide-postmortem

## VERDICT
The graveyard argues against over-scoping, and very specifically against two things the questioner is not proposing: (1) hand-rolling language intelligence instead of consuming LSP, and (2) building Android APKs on-device via Gradle/AGP. It does not argue against native UI — native UI is what all the survivors have. AndroidIDE's 59-module tree contained a vendored javac, a vendored AAPT2 compiler, a full in-tree Termux fork, a Gradle Tooling API client with an injected Gradle plugin, and a drag-and-drop UI designer, all maintained by one person, and it still never shipped Kotlin completion. Its funded nonprofit successor needed ~18 months and a team to reach R1, and got there by *adding* the JetBrains Kotlin Analysis API, a JVM symbol index, a vendored libjdwp debugger, and ten Shizuku modules. That is a team-scale product, and it is not the shape of the questioner's project. The questioner's position is strictly better than AndroidIDE's ever was, because code-server already running locally means real, off-the-shelf language servers are already installed and running — the exact asset AndroidIDE lacked and died trying to substitute for. The survivable scope is: native Compose UI + a real LSP client (LSP4J, which sora-editor's `editor-lsp` already uses) + language servers as out-of-process children of the existing Termux-style backend + `./gradlew` shelled out to the terminal rather than reimplemented. Copy Zed's `LspStore` seam — `LocalLspStore` owns processes, `RemoteLspStore` owns nothing and routes over RPC — because it is precisely the UI/backend split this app needs, and it is already proven to survive a network boundary. Do not build a UI designer, a debugger, a build system, or your own language analysis.

## Summary
The premise contains two errors worth correcting up front. First, AndroidIDE did **not** build "a native Android IDE UI over language servers" — it never spoke LSP at all. Its `ILanguageServer` is an in-process Kotlin interface with custom result types and no JSON-RPC, meaning every language's intelligence had to be hand-written (a vendored javac for Java, a port of AGP's aaptcompiler for XML, and **no Kotlin support ever shipped**). Second, CodeAssist did not stall — the main repo cut release 3.9.2 on 2026-08-05, one day before this research. What actually stalled was the separate `CodeAssist-v3` rewrite repo (last push 2024-06-17). AndroidIDE's archival note (by creator Akash Yadav) blames neither the UI nor the architecture; it says "you kept this project alive far longer than one developer ever could have alone" and credits a co-founder who personally carried running costs. Yadav then **joined the successor team** and built its debugger — he re-did the same native-UI approach with funding rather than abandoning it. The successor, Code on the Go, shipped R1 on 2026-04-29 with a module list that is strictly *larger* than AndroidIDE's. The graveyard therefore indicts scope — specifically hand-rolled compilers and on-device Gradle/AGP — not native UI. Every survivor in the space has a native UI.

## Key facts
- VERIFIED: AndroidIDE never used real LSP. `core/lsp-api/.../ILanguageServer.kt` is an in-process Kotlin interface (suspend fns, custom `CompletionResult`/`DiagnosticResult` types, `connectClient(ILanguageClient)`, `setupWorkspace(IWorkspace)`) — no JSON-RPC, no LSP4J, no subprocess. The name 'LSP' in that repo is aspirational.
- VERIFIED: AndroidIDE's `settings.gradle.kts` has ~59 modules and contains NO Kotlin language module. Kotlin intelligence never shipped. Confirmed independently by the maintainer's own post on discuss.kotlinlang.org asking for help 'integrating a Kotlin Language Server Protocol (LSP) into our AndroidIDE project'.
- VERIFIED: AndroidIDE hand-rolled its compilers — `:java:javac-services` (vendored javac) + `:java:lsp`, and `:xml:aaptcompiler` (port of AGP's Kotlin aaptcompiler) + `:xml:dom` + `:xml:lsp`. This is the single largest source of its maintenance burden.
- VERIFIED: AndroidIDE vendored a full Termux fork in-tree as four modules (`:termux:application`, `:termux:emulator`, `:termux:shared`, `:termux:view`) — it did not depend on the Termux app or RUN_COMMAND.
- VERIFIED: Termux's RUN_COMMAND intent cannot carry a language server. stdin is a single String extra; the result bundle is truncated to 100KB combined; command/arg length caps near 128KB; results arrive via PendingIntent only at process exit. It is fire-and-forget, not bidirectional stdio. This is why both AndroidIDE and Code on the Go vendor Termux in-process.
- VERIFIED: AndroidIDE archived 2024-10-18 (repo `archived: true`, `pushed_at` 2024-10-18); org archived 2024-12-20; 3,039 stars. The farewell states no technical reason. Quote: "You kept this project alive far longer than one developer ever could have alone." Co-founder Marvin "took on its running costs himself." INFERRED: solo-maintainer capacity plus hosting cost, not architecture.
- VERIFIED: AndroidIDE's creator joined App Dev for All and is "primarily responsible for the Code on the Go debugger." He repeated the native-UI approach with funding — strong evidence he did not consider the approach the failure.
- VERIFIED: Code on the Go is alive — R1 shipped 2026-04-29, 5,900+ commits on `stage`, GPL-3.0, US nonprofit. Its module list EXPANDS on AndroidIDE: adds `:lsp:kotlin`, `:subprojects:kotlin-analysis-api`, `:lsp:indexing`, `:lsp:jvm-symbol-index`, `:subprojects:libjdwp`, ten Shizuku modules, `:compose-preview`, `:plugin-api`/`:plugin-manager`, `:git-core`, `:idetooltips`. It still uses the same in-process `:lsp:api`/`:lsp:models` design — it did NOT adopt real LSP.
- VERIFIED (premise correction): CodeAssist is NOT stalled. `tyron12233/CodeAssist` pushed 2026-08-05, release 3.9.2, 1,637 stars, real feature commits by the maintainer. The stalled repo is the separate `CodeAssist-v3` (last push 2024-06-17, 129 stars); its ideas landed in main instead.
- VERIFIED: CodeAssist's surviving scope is exactly the retreat that matters — 'runs entirely on device (Android/ART) and edits and builds Android/Java projects **without hosting Gradle**'; its own declarative model compiled to an incremental task DAG driving aapt2/D8/R8/apksigner directly; Java intelligence via **Eclipse JDT** (reuse, not hand-rolled); UI in **Jetpack Compose Multiplatform**. Native UI + reused analyzer + no Gradle = the one solo project still shipping.
- VERIFIED: sora-editor ships an `editor-lsp` module that depends on **Eclipse LSP4J** (`implementation(libs.lsp4j)` in `editor-lsp/build.gradle.kts`), requiring API 26+. A native Android app can speak real LSP off the shelf today. AndroidIDE simply chose not to.
- VERIFIED: Zed's LSP seam is three layers and is the most directly copyable design — the `lsp` crate owns the child process and JSON-RPC framing; `LspStore` (held by `Project`) dispatches to `LocalLspStore` (owns the HashMap of server processes, diagnostics, buffer snapshots, file watchers, dynamic registrations) or `RemoteLspStore` (owns nothing, routes every request over `AnyProtoClient` RPC to the host); `LspCommand` bridges LSP payloads to Zed's Anchor-based coordinates. Plus `LanguageRegistry`/`LspAdapter` per language and a worktree trust gate before any server starts.
- VERIFIED: Helix uses the same shape at smaller cost — `helix-lsp` with `Transport` (JSON-RPC framing) and `Client` (process lifecycle + capability negotiation), declarative TOML language config, grammars dynamically loaded via `helix-loader`, and multiple servers attachable per document (e.g. ruff + pyright). This is the minimum viable LSP client architecture and it is ~2 files of concept.
- VERIFIED: Kotlin on-device is the hard constraint, not the UI. JetBrains' official kotlin-lsp needs JVM 17+, ~500MB at startup and 1GB+ working. The Rust/tree-sitter alternative `Hessesian/kmp-lsp` runs in <200MB with no JVM and supports completion/hover/goto/refs/rename/inlay hints/semantic tokens — but explicitly CANNOT do real type inference or type checking beyond syntax validation. Android/aarch64 builds are not documented.

## Details
## 1. AndroidIDE: what it actually was

Module tree (`settings.gradle.kts`, `dev` branch, ~59 modules) groups into:

| Group | Modules | What it means |
|---|---|---|
| Editor | `:editor:api`, `:impl`, `:lexers`, `:treesitter` | sora-editor + tree-sitter wrapper |
| "LSP" | `:core:lsp-api`, `:core:lsp-models` | **in-process interfaces, not LSP** |
| Java | `:java:javac-services`, `:java:lsp` | vendored javac running inside ART |
| XML | `:xml:aaptcompiler`, `:xml:dom`, `:xml:lsp`, `:xml:resources-api` | port of AGP's aaptcompiler |
| Terminal | `:termux:application/emulator/shared/view` | full Termux fork in-tree |
| Build | `:tooling:api/impl/model/events/plugin` | Gradle Tooling API + injected plugin |
| Extras | `:utilities:uidesigner`, `:xml-inflater`, `:framework-stubs`, `:core:indexing-*`, templates, treeview | a drag-drop UI designer |

Kotlin: **absent**. One person maintained all of this.

The decisive artifact is `ILanguageServer.kt`: `complete()`, `findReferences()`, `findDefinition()`, `signatureHelp()`, `analyze(Path)`, `formatCode()` — all direct in-process calls returning bespoke types. There is no protocol boundary anywhere. Consequence: **zero reuse**. Every language costs a compiler port. That is the actual failure mechanism, and it is the opposite of what the questioner is proposing.

## 2. Cause of death

The farewell names no technical cause. Signals: solo maintainer ("far longer than one developer ever could have alone"), co-founder personally absorbing running costs, and — decisively — the creator immediately continuing the *same native-UI product* inside a funded nonprofit. Nothing supports "native UI was the mistake." Everything supports "the surface area was unmaintainable by one person."

Corroborating: Code on the Go needed a team ~18 months to reach R1, and reached it by *growing* scope — adding `:subprojects:kotlin-analysis-api` (vendoring JetBrains' Kotlin Analysis API is the only way to get Kotlin without LSP), a JVM symbol index, `libjdwp`, Shizuku privileged services, Compose preview. Reported resource footprint: ~7,500 non-build files, **1.7 GB downloaded assets**, ~46,000 tooltips. That is the price of the AndroidIDE architecture done properly.

## 3. Survivors and their retreats

- **CodeAssist** (alive, shipping 2026-08-05): dropped Gradle for its own task DAG over aapt2/D8/R8/apksigner; adopted **Eclipse JDT** rather than writing a Java analyzer; Compose Multiplatform UI. A recent commit — "harden the on-device editor against 32-bit ARM ART SIGSEGV" — is a real warning about running heavy JVM tooling in-process on ART. It also monetizes (interstitials over long builds, lessons).
- **Squircle CE**: native, editor-only, no LSP. Its completion is content/word-based over the open file. Issue #104 (Termux/execution integration, Nov 2021) is still unresolved. Staying editor-only is why it is alive; it is also why it isn't an IDE.
- **Acode**: webview (Ace) + LSP via plugins (`acode-lsp`, ace_linters), transport over **websocket to an out-of-process server**, with a websocket proxy for servers that only speak stdio. Bundles its own Alpine userland rather than depending on Termux. Note the lesson: even the webview app puts language servers behind a socket. Nobody who succeeds runs analysis in-process.
- **`MozarellaMan/Mobile-LSP-Client`**: Compose UI prototype talking JSON to a Rust language-server proxy. Proved the exact seam the questioner wants; inactive; semantic tokens unimplemented.

## 4. Architecture worth copying

Zed and Helix converge on the same three layers, and Zed's split is the one that maps onto this problem:

```
lsp crate        -> owns child process, JSON-RPC framing, capability negotiation
LspStore         -> LocalLspStore  (owns processes, diagnostics, file watchers)
                    RemoteLspStore (owns nothing, routes over RPC to host)
LspCommand       -> maps LSP positions <-> editor's stable anchors
LanguageRegistry -> per-language LspAdapter: binary discovery, launch cmd, config
```

`RemoteLspStore` is the questioner's Compose UI. `LocalLspStore` is the Termux-side backend. Zed already proved this boundary survives latency, reconnection, and multiple concurrent servers. Two details worth stealing verbatim: **anchor-based coordinate mapping** (LSP line/char goes stale the instant the user types — Zed translates through stable anchors, and getting this wrong is the #1 source of "completion inserted in the wrong place" bugs), and the **trust gate** before launching server binaries.

Helix shows the cheap version: `Transport` + `Client` + declarative TOML per language, multiple servers per document.

## 5. Risks specific to this plan

- **RUN_COMMAND is a dead end** for LSP (100KB result cap, no streaming, exit-only callback). You need to spawn servers from your own process with a real PTY/pipe — which is exactly why AndroidIDE and Code on the Go both vendor Termux instead of talking to it.
- **code-server is not an LSP multiplexer.** VS Code language servers live inside the extension host and are not individually reachable as sockets. INFERRED: you will get a cleaner result spawning language server binaries directly from the backend than trying to proxy through code-server's extension host. Verify before committing.
- **Kotlin/JVM memory** is the real ceiling on a phone, not rendering. Budget for kmp-lsp-class servers (<200MB, no type inference) or accept 1GB+ for JetBrains kotlin-lsp.
- **Fold-native UI is free upside**, but it is the one thing no prior project has done, so there is no code to copy — only Zed's seam.

## Confidence
HIGH CONFIDENCE (read primary source directly): AndroidIDE's module list and its ILanguageServer.kt interface — I read the raw files, so "AndroidIDE never spoke real LSP" and "no Kotlin module existed" are verified facts, not inference. Same for Code on the Go's module list, CodeAssist's 2026-08-05 activity and README scope (fetched via GitHub API/raw), sora-editor's LSP4J dependency, and the Termux RUN_COMMAND limits (official wiki).

MEDIUM CONFIDENCE: The *reason* AndroidIDE was archived. The farewell note gives no technical cause. My reading — solo-maintainer capacity plus running costs, not the native-UI approach — is INFERRED from three converging signals (the "one developer" quote, the co-founder absorbing costs, and the creator continuing the same approach at a funded nonprofit). I could not retrieve the maintainer's Discord/Reddit statements; androidide.com returned 403 to direct fetch and I read it via a text-proxy, so quotes from it are second-hand transcription and should be spot-checked if load-bearing. If the architect wants certainty here, the gap is worth closing directly.

MEDIUM CONFIDENCE: Zed's and Helix's internals came from DeepWiki, which is AI-generated documentation over the repos, not the repos themselves. The overall shape (lsp crate / LspStore split / LspCommand; helix-lsp Transport + Client) is consistent across multiple independent sources and matches what I know of these codebases, but exact type names should be confirmed against source before anyone writes code against them.

LOW CONFIDENCE / FLAGGED AS UNVERIFIED: Acode's LSP plugin internals — I could not load the plugin page or its source, so the websocket-proxy description comes from search snippets only. Also unverified: whether kmp-lsp builds for Android/aarch64 (not documented), and my claim that code-server's extension host cannot be proxied as an LSP multiplexer — that is reasoning from how VS Code works, not something I confirmed, and it is important enough to the plan that it should be tested before the architecture is fixed.

PREMISE CORRECTIONS I am confident about and did not soften: AndroidIDE was not a native-UI-over-LSP project, and CodeAssist is not stalled. Both were stated as given in the task and both are wrong on the evidence.

## Sources
- AndroidIDE (archived Oct 18 2024) — repo, README, archival banner: https://github.com/AndroidIDEOfficial/AndroidIDE
- AndroidIDE settings.gradle.kts — full ~59-module list (primary source for scope): https://raw.githubusercontent.com/AndroidIDEOfficial/AndroidIDE/dev/settings.gradle.kts
- AndroidIDE ILanguageServer.kt — proof the 'LSP' was in-process, not JSON-RPC: https://raw.githubusercontent.com/AndroidIDEOfficial/AndroidIDE/dev/core/lsp-api/src/main/java/com/itsaky/androidide/lsp/api/ILanguageServer.kt
- Code on the Go (successor) — repo, R1, module list showing scope expansion: https://github.com/appdevforall/CodeOnTheGo
- Behind the scenes and under the hood of Code on the Go — states AndroidIDE's creator joined the team: https://www.appdevforall.org/under-the-hood-code-on-the-go/
- CodeAssist — alive, release 3.9.2 on 2026-08-05, JDT + no-Gradle + Compose: https://github.com/tyron12233/CodeAssist
- CodeAssist-v3 — the repo that actually stalled (last push 2024-06-17): https://github.com/tyron12233/CodeAssist-v3
- Zed language server integration — LspStore / LocalLspStore vs RemoteLspStore / LspCommand: https://deepwiki.com/zed-industries/zed/5.2-language-server-integration
- Termux RUN_COMMAND Intent — 100KB result cap, no bidirectional stdio streaming: https://github.com/termux/termux-app/wiki/RUN_COMMAND-Intent
- sora-editor docs — editor-lsp module (uses Eclipse LSP4J, API 26+): https://project-sora.github.io/sora-editor-docs/guide/getting-started

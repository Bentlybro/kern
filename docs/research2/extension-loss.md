# extension-loss

## VERDICT
Losing the extension ecosystem is NOT a dealbreaker for a personal mobile IDE — but only because the parts that matter were never really "extensions." Verified: VS Code's built-in language extensions are MIT-licensed grammar + config JSON with no executable code (Python's grammar is upstream MagicPython/MIT, Rust's is dustypomerleau/rust-syntax/MIT), and they are already consumed on Android today by sora-editor via TM4E, which additionally loads VS Code color-theme JSON verbatim. Verified: the git extension is a `child_process.spawn` shim over the git CLI. Verified: pyright/basedpyright, rust-analyzer, gopls, clangd, and the extracted html/css/json/eslint servers are all plain LSP; debugpy, vscode-js-debug's `dapDebugServer.js`, lldb-dap, and `dlv dap` are all plain DAP; prettier/eslint/ruff/black/rustfmt/clippy are all CLIs with JSON output. For Python + TypeScript + light Rust, roughly 85–90% of the realistic on-phone daily loop is reachable with zero extension host. The genuine, non-replaceable holes are narrow and mostly things you would not do on a 7-inch screen anyway: Jupyter notebook rendering and widgets, per-framework test discovery (no protocol exists — you shell out to `pytest --collect-only` and friends), Remote-SSH's REH (proprietary and Microsoft-build-locked; you must build SSH + LSP proxying yourself), Live Share (gone), Pylance-exclusive polish (recovered by basedpyright), the client-side TypeScript niceties in `typescript-language-features` (which TypeScript 7's native Go LSP is about to make moot), and ms-python's Python environment/venv discovery logic, which you will have to reimplement by hand. The framing to reject is that the extension host is what you are fighting: what you actually lose is the private, unstable `vscode.*` API contract, not the compute — your device already runs Node. The things that can genuinely kill this project are Android-platform facts, not Microsoft ones: verified W^X/SELinux blocking execve from app-private storage at targetSdk ≥ 29 (Termux's linker64 workaround fails on static binaries and scripts), `/sdcard` being mounted noexec, the OS killing backgrounded language servers unless they run under a foreground service, and rust-analyzer's 1–3 GB working set. Architect against those; treat the extension ecosystem as a solved-by-protocols problem, and lift TM4E/sora-editor rather than writing a tokenizer.

## Summary
The premise is partly wrong in a way that matters: dropping the VS Code workbench does not mean dropping the compute that produces language intelligence — it means dropping the *VS Code extension API contract*, which is a private, unstable, workbench-only surface. Almost everything a Python/TypeScript/Rust developer actually uses on a phone is already delivered by out-of-process, protocol-speaking programs (LSP servers, DAP adapters, CLI linters, git) that a native Kotlin client can drive directly. Verified: VS Code's built-in language extensions (python, rust, go, java, cpp, typescript-basics, etc.) are grammar + language-configuration JSON with no `main` entry point, MIT-licensed, sourced from upstream MIT grammars — they are independently consumable, and Android already has a shipping consumer (sora-editor via TM4E, which also loads VS Code theme JSON directly). The real losses are narrow: Jupyter notebooks, the Test Explorer's per-framework discovery logic, Remote-SSH (proprietary REH), Live Share, and the long tail of webview-UI marketplace extensions. The honest blockers are not the ecosystem at all — they are Android's W^X exec restriction on targetSdk ≥ 29, the foreground-process lifecycle killing long-lived language servers, and RAM (rust-analyzer). For Python + TS + a bit of Rust on a phone, roughly 85–90% of the realistic daily loop is reachable natively; the extension ecosystem is not a dealbreaker, the Android process model is the thing that can kill this.

## Key facts
- VERIFIED: extensions/python/package.json contributes only `languages` + `grammars` + `language-configuration.json` and has NO `main` field — it is pure data, no extension-host JS. Same shape for rust, go, java, cpp, typescript-basics, markdown-basics.
- VERIFIED: extensions/python/cgmanifest.json sources MagicPython (MagicStack/MagicPython) under MIT; extensions/rust/cgmanifest.json sources dustypomerleau/rust-syntax under MIT. Grammars are independently redistributable.
- VERIFIED: the vscode/extensions dir has ~95 entries. The naming split is diagnostic: `python`/`rust`/`go`/`typescript-basics` = grammar-only data; `*-language-features` (typescript, html, css, json, php, markdown) = the ones with real JS.
- VERIFIED: extensions/git/src/git.ts imports `child_process` and calls `cp.spawn(this.path, args, options)` — VS Code's entire SCM data layer shells out to the git CLI. Nothing is reimplemented in-process.
- VERIFIED: sora-editor (Android, LGPL-2.1) consumes VS Code `.tmLanguage.json` grammars via GrammarRegistry AND VS Code theme JSON via ThemeRegistry/ThemeModel, and ships tree-sitter and editor-lsp modules. It is an Android View (needs AndroidView interop in Compose).
- VERIFIED: TM4E (eclipse-tm4e/tm4e, EPL-2.0) is a pure-Java port of microsoft/vscode-textmate including VS Code language-configuration semantics (brackets, auto-close, on-enter) — embeddable in any JVM/Android app.
- VERIFIED: VS Code's TypeScript IntelliSense does NOT use LSP — typescript-language-features speaks tsserver's proprietary protocol. typescript-language-server (community, not Microsoft) is the LSP bridge. Its README flags that TypeScript 7 (Go rewrite) will ship native LSP and supersede it.
- VERIFIED: Pylance is license-restricted to official Microsoft builds of VS Code and Codespaces; it is a superset of pyright adding semantic highlighting, extract-variable/method refactors, stdlib docstrings, bundled stubs. basedpyright (fork) re-adds Pylance features and ships via PyPI/npm.
- VERIFIED: vscode-js-debug ships a standalone DAP server (`js-debug/src/dapDebugServer.js`, run as `node dapDebugServer.js <port> 127.0.0.1`), released as a `js-debug-dap-*.tar.gz` asset — usable with zero VS Code.
- VERIFIED: vscode-langservers-extracted republishes VS Code's html/css/json/eslint language servers as standalone LSP binaries (MIT wrapper over MS-licensed upstreams) — the built-in web-language features ARE reusable.
- VERIFIED (critical Android constraint): Android 10+ blocks execve() of files in an app's private data dir for targetSdkVersion >= 29 (W^X/SELinux). Termux's workaround is to exec /system/bin/linker64 with the binary as argv — which breaks statically-linked binaries and scripts, and makes /proc/self/exe report the linker.
- VERIFIED prior art: zed-android-port ships a Termux-derived userland in-process from app private data; rust-analyzer pre-baked, gopls/ts-server/pyright/jdtls installable. Documented gotcha: /sdcard is mounted noexec, so projects must live under the app's exec-enabled home dir.
- VERIFIED: Copilot Chat was open-sourced under MIT (microsoft/vscode-copilot-chat, June 30 2025) — but it targets the VS Code chat API, and the backend models remain closed. Remote-SSH remains proprietary and MS-build-locked; Live Share likewise.
- VERIFIED: microsoft/vscode-anycode (tree-sitter, for vscode.dev where servers can't run) covers Python/TS/Rust/Go/Java/C++/C#/Kotlin/PHP but only outline, workspace symbols, and document highlights — Microsoft's own words: 'inaccurately implements popular features'. It is the honest ceiling for a no-server fallback.

## Details
## Premise correction (read first)

"Without the extension host" conflates two things. The extension host is a **Node process running JS**. Your device already runs Node — code-server proves it. What you actually give up is the **`vscode.*` API contract**: a private, versioned, workbench-coupled RPC surface. You cannot implement a native client against it (it is not a spec, has no stability guarantee, and the REH handshake is what Microsoft license-locks). So: the compute survives, the API does not. Design around protocols (LSP/DAP/CLI), not around "porting extensions."

Second correction: the extension ecosystem is **not** your risk. Android process lifecycle is. See "Real blockers."

## Tier map

| Capability | Tier | Native path | Fidelity |
|---|---|---|---|
| Syntax highlighting | (b) grammar-only built-ins, MIT | Copy `*.tmLanguage.json` from vscode repo → TM4E / sora-editor | ~100% |
| Brackets/auto-close/on-enter/comments | (b) `language-configuration.json` | Same files; TM4E implements the semantics | ~100% |
| Color themes | (a) core + (b) `theme-*` exts | VS Code theme JSON loads directly into sora's `ThemeRegistry`. `tokenColors`+`semanticTokenColors` transfer 1:1; the ~600 `colors.*` workbench keys mostly won't map to your native chrome | 100% editor / ~40% chrome |
| Git SCM | (b) built-in `git` ext (core provides only the SCM *view model*) | git CLI subprocess — literally what VS Code does. JGit (Android forks exist) or libgit2-JNI are worse: JGit lags protocol features, libgit2 uses POSIX file APIs and fights SAF | ~95% |
| Formatting | (c) marketplace, all thin CLI wrappers | Spawn `prettier`, `ruff format`, `black`, `rustfmt`. Or use LSP `textDocument/formatting` | 100% |
| Linting | (c) | `ruff check --output-format=json`, `eslint -f json`, `cargo clippy --message-format=json` → parse to native diagnostics. eslint also has a real LSP (vscode-langservers-extracted) | ~95% |
| Debugging | (b)+(c), but adapters are standalone | debugpy (`python -m debugpy`), vscode-js-debug `dapDebugServer.js`, `lldb-dap`/codelldb for Rust, `dlv dap` for Go. All speak plain DAP over TCP/stdio | ~90% protocol, 0% UI (you build it) |
| Snippets / tasks / settings / multi-cursor / search | (a) core | Reimplement; snippet + tasks JSON schemas are small. Use ripgrep binary for search | ~100% |
| Test Explorer | (a) UI API, (c) discovery logic | No protocol exists. Shell out per-framework: `pytest --collect-only -q`, `vitest list`, `cargo test -- --list` | ~60%, bespoke |
| Notebooks | (a) renderer core + (b) `ipynb` + (c) MS Jupyter ext | `.ipynb` is JSON (easy); kernels speak ZeroMQ Jupyter protocol (doable); output renderers/widgets are HTML — you'd need a WebView per output | ~40%, expensive |
| Remote SSH | (c) **proprietary**, MS-build-locked | Plain SSH + run servers remotely + proxy LSP/DAP over the channel. Real work, no shortcut | 0% reuse |
| Live Share | (c) proprietary | None | 0% |
| Copilot | (c) MIT since 2025-06-30 but targets `vscode.chat` API | Call Anthropic/OpenAI APIs directly from Kotlin — *easier* natively than porting | ≥100% |

## Language intelligence: server vs extension-JS

| Lang | Reusable via LSP | Stranded in extension JS |
|---|---|---|
| Python | pyright-langserver / **basedpyright** (recommended: restores Pylance-only refactors, semantic tokens, inlay hints, under MIT) | ms-python's venv/conda/uv **discovery**, terminal activation, test adapter shim, REPL. All must be rewritten — non-trivial, ~1–2k LOC |
| TypeScript | typescript-language-server (wraps tsserver) | The built-in ext's client-side polish: auto-import-on-paste, JSX tag rename, some quick-fixes, tsconfig authoring. **Tailwind:** TypeScript 7 (Go) ships native LSP and erases this gap |
| Rust | rust-analyzer — pure LSP, ext is a thin downloader | ~nothing |
| Go | gopls — pure LSP | ~nothing (test/debug glue only) |
| C/C++ | clangd — pure LSP | MS `cpptools` is a different, proprietary engine; clangd is the answer |
| Java | jdtls — LSP but needs JDK 21+; Termux has no jdtls package | Heavy; realistically skip on phone |
| HTML/CSS/JSON | Extracted as standalone servers (vscode-langservers-extracted) | ~nothing |

## Real blockers (not the ecosystem)

1. **W^X.** targetSdk ≥ 29 cannot execve from app data dir. Linker-exec workaround breaks static binaries (ruff, rust-analyzer are often static) and scripts. If code-server works today you've already solved this — confirm *how*, because language servers hit the identical wall.
2. **Lifecycle.** Android kills backgrounded processes. rust-analyzer/tsserver must live under a foreground service with a persistent notification, or they die on every app switch — brutal on a fold where you *will* multitask.
3. **RAM.** rust-analyzer on a real crate: 1–3 GB. Fold8 survives one, not three.
4. **`/sdcard` is noexec** — projects must live in app-private storage.
5. **Editor widget.** Compose `BasicTextField` is not viable for large files (inferred, not verified here). Use sora-editor via `AndroidView` — but it's **LGPL-2.1**, so dynamic-link and be ready to ship object files if you ever distribute.

## Verdict math

Editing + navigation + diagnostics + git + terminal + format/lint: **~90%**. Debugging: ~70% (protocol free, UI expensive). Notebooks + test UI + marketplace long tail: ~30%. Weighted for what anyone actually does on a phone: **~85–90%**.

## Confidence
HIGH confidence / directly verified from primary source this session: the grammar-only nature and MIT provenance of VS Code's built-in language extensions (read package.json and cgmanifest.json for python and rust); the full ~95-entry extensions/ directory listing via the GitHub API; git.ts spawning the git CLI (read the source); sora-editor accepting VS Code tmLanguage.json and theme JSON (read the official docs page); TM4E being a Java port of vscode-textmate; typescript-language-features NOT using LSP; vscode-js-debug's standalone dapDebugServer; vscode-langservers-extracted's contents; the Pylance license restriction and its feature delta over pyright; Android W^X/linker-exec mechanics and limitations; the zed-android-port's noexec-/sdcard and bundled-userland findings; Copilot Chat's MIT relicensing; Anycode's scope and Microsoft's own "inaccurately implements" caveat.

MEDIUM confidence / inferred or partially verified: basedpyright's exact restored-feature list and license — I confirmed it exists, is a pyright fork advertising "pylance features," and ships via PyPI/npm/Open VSX, but the fetched page did not enumerate features or state the license text (widely understood to be MIT, treat as unconfirmed). Termux/Android availability of specific binaries (ruff, clangd) was not confirmed package-by-package against the termux-packages repo — rust-analyzer, gopls, ts-server, pyright, jdtls availability comes from the zed-android-port docs and a Termux issue thread, not a package index read. The static-binary interaction with linker-exec (i.e. that ruff's and rust-analyzer's release artifacts are static and would therefore fail) is INFERRED from the verified statement that linker-exec fails for statically linked binaries — I did not verify the linkage mode of those specific release artifacts, and this is worth a 20-minute empirical check on the actual device before committing to an architecture.

INFERRED, not verified here (flag as engineering judgment): Compose BasicTextField's unsuitability for large-file editing; Android's low-memory killer terminating backgrounded LSP child processes absent a foreground service; the rust-analyzer 1–3 GB figure; and all percentage estimates (85–90% daily-loop coverage, per-row fidelity numbers) — these are calibrated judgments, not measurements. The LGPL-2.1 obligation analysis for sora-editor is my reading of the stated license, not legal advice; for a personal, non-distributed app it is moot.

One asymmetry worth stating: I could not verify HOW the developer's existing code-server gets execute permission on-device. That single fact determines whether the W^X blocker is already solved or is the project's actual long pole, and everything in the "Real blockers" section should be re-weighted once it is known.

## Sources
- microsoft/vscode — extensions/ directory listing (full set of built-in extensions): https://github.com/microsoft/vscode/tree/main/extensions
- vscode/extensions/python/cgmanifest.json — MagicPython grammar provenance and MIT license: https://raw.githubusercontent.com/microsoft/vscode/main/extensions/python/cgmanifest.json
- vscode/extensions/git/src/git.ts — built-in git extension spawns the git CLI via child_process: https://raw.githubusercontent.com/microsoft/vscode/main/extensions/git/src/git.ts
- Rosemoe/sora-editor — Android code editor with TextMate, tree-sitter and LSP modules (LGPL-2.1): https://github.com/Rosemoe/sora-editor
- Sora Editor docs — loading VS Code tmLanguage.json grammars and VS Code theme JSON: https://project-sora.github.io/sora-editor-docs/guide/using-language
- eclipse-tm4e/tm4e — Java port of vscode-textmate plus VS Code language-configuration support: https://github.com/eclipse-tm4e/tm4e
- typescript-language-server — LSP wrapper over tsserver; notes VS Code's TS extension does not use LSP: https://github.com/typescript-language-server/typescript-language-server
- hrsh7th/vscode-langservers-extracted — standalone html/css/json/eslint language servers: https://github.com/hrsh7th/vscode-langservers-extracted
- Termux system linker execution — W^X bypass for targetSdkVersion >= 29 and its limitations: https://deepwiki.com/termux/termux-exec-package/3.3-system-linker-execution
- zed-android-port (Zdroid) — native Android editor with bundled Termux userland running rust-analyzer/gopls/pyright: https://github.com/Dylanmurzello/zed-android-port

# native-editor

## VERDICT
Use sora-editor (`io.github.rosemoe:editor-bom:0.24.6`) as the editing surface, with `language-textmate` for highlighting and `oniguruma-native` for grammar fidelity — but treat `editor-lsp` as a prototype scaffold, not the LSP layer you ship. It is the only native Android editor component with IDE-grade depth, it already satisfies your two hardest non-negotiables (native selection handles + magnifier, real `WindowInsetsCompat.ime()` participation because it is a plain `View`), and its LGPL-2.1-or-later license is cleanly compatible with your GPLv3 app. It consumes VS Code `.tmLanguage.json` grammars and VS Code theme JSON directly, so you can point it at the grammars already sitting in your on-device code-server install and keep visual continuity for free. The costs are real and concrete: no code folding, no multi-cursor, bus factor of 1, a `List<ContentLine>` document model that keeps whole files in RAM, and an LSP module that gives you the transport and the easy features but leaves navigation and refactoring for you to build on its `RequestManager` (which does already expose every LSP method). Do not treat "native UI" as one decision — build the native chrome (panes, insets, key row, Termux `terminal-view`) first while the workbench keeps serving the editing surface, then swap in sora behind that shell. Keep D9's CodeMirror 6 fallback alive; it is healthier than when you wrote it.

## Summary
sora-editor is the only credible native Android code-editor component in 2026 — there is no real competition, and that's a finding, not a preference. It is genuinely healthy: 0.24.6 shipped 2026-06-10, commits landed the day of this research, and it now has minimap, inlay hints, sticky scroll, magnifier, native selection handles, RTL, and deep IME/mouse config. But two things must be said plainly. First, the LSP story is not what you need: `editor-lsp` self-describes as "Experimental, work in progress" and ships UI for only completion/hover/signature/diagnostics/codeAction/formatting — no go-to-definition, references, rename, symbols, or semantic tokens, all of which your R4 requires. The most credible production user, Code on the Go (GPLv3, pushed today), uses sora's `editor` widget but deliberately bypasses `editor-lsp` entirely and wrote ~679 files of its own lsp4j stack. Second, code folding has been open since 2021 and multi-cursor since Jan 2026; neither exists. Also: your premise conflates two separable problems. F1 and F2 (desktop layout, IME insets) are chrome problems that a native shell fixes while keeping a web editing surface. Only the selection-handles floor genuinely forces a native editor. Recommendation: adopt sora-editor, but sequence it — native chrome first, sora second, and budget for a soft fork. Realistic effort to "better than the WebView for editing" is roughly 3 months solo; folding and multi-cursor are fork-or-never.

## Key facts
- VERIFIED: sora-editor 0.24.6 released 2026-06-10; repo pushed 2026-08-06. Coordinates changed in 0.23.7 — new group is `io.github.rosemoe`, BOM artifact renamed `bom`→`editor-bom`. Old `io.github.Rosemoe.sora-editor` group is frozen at 0.23.6 (Dec 2024).
- VERIFIED: `editor-lsp/README.md` says "Experimental, work in progress". Shipped: formatting, rangeFormatting, diagnostic, signatureHelp, completion, publishDiagnostics, hover, codeAction. NOT wired: definition, references, rename, documentSymbol, foldingRange, codeLens, semanticTokens (semanticTokens is the only listed TODO).
- VERIFIED: the gap is UI wiring, not protocol. `RequestManager.kt` already exposes definition, references, rename, prepareRename, documentSymbol, foldingRange, semanticTokensFull/Delta/Range, codeLens. The `events/` dir is what's missing — you write the editor-side integration.
- VERIFIED: Code on the Go (appdevforall/CodeOnTheGo, GPL-3.0, 286 stars, pushed 2026-08-06) pins `io.github.Rosemoe.sora-editor:bom:0.23.6` for `editor` + `language-textmate` only, uses raw `org.eclipse.lsp4j:0.22.0`, and has its own 679-file `lsp/` tree. It does NOT use editor-lsp or language-treesitter.
- VERIFIED: Code on the Go had to patch into sora's package namespace to reach package-private APIs — `io.github.rosemoe.sora.text.ContentLockAccessor` (wraps `Content.lock()/unlock()`) and `io.github.rosemoe.sora.widget.IDEEditorSearcher` — and vendored all of `language-treesitter` into its own `editor-treesitter` module. Budget for the same.
- VERIFIED: Code folding is issue #85, OPEN since 2021-08-27. Maintainer on 2026-02-26: folding comes after inlay hints (#767). Inlay hints shipped in 0.24.6, so folding is next-ish but unshipped. Multi-cursor is issue #798, open since 2026-01-28, zero comments.
- VERIFIED: No official Compose artifact exists. The docs page "CodeEditor in Compose" is a hand-written `AndroidView` recipe (and its own sample has a bug — `data class CodeEditorState(val editor: ...)` then assigns `state.editor = editor`). You write the wrapper. `CodeEditor` is a plain View, so it works in any Compose pane.
- VERIFIED: License is LGPL-2.1-**or-later** (source headers say "either version 2.1 ... or (at your option) any later version"), and FSF states LGPLv2.1 "is compatible with GPLv2 and GPLv3." No conflict with your GPLv3 app; source-availability satisfies LGPL relinking trivially.
- VERIFIED BLOCKER: sora 0.24.6 ships `kotlin-stdlib:2.3.10` and Kotlin classes with 2.3 metadata. Your project is on Kotlin 2.2.0 — expect "compiled with an incompatible version of Kotlin". Bump to Kotlin 2.3.x before integrating. Also sora builds against compileSdk 37 vs your 36 (AGP warning), and minSdk 29 means `language-textmate` REQUIRES `coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.1.5")`.
- VERIFIED: TextMate consumes VS Code assets directly — `.tmLanguage.json` and `.tmLanguage` plist grammars, VS Code theme JSON, and `.language-configuration.json`. APIs are `FileProviderRegistry` / `GrammarRegistry.loadGrammars()` / `ThemeRegistry.setTheme()` / `TextMateColorScheme.create()`. Default regex is Joni (JVM), which breaks some grammars; the new `oniguruma-native` module (1.24 MB, JNI, Unicode 17) fixes fidelity.
- VERIFIED: rich runtime config in `DirectAccessProps` — `disallowSuggestions` (kills autocorrect), `trackComposingTextOnCommit`, `minimizeComposingTextUpdate`, `stickyScroll`, `useICULibToSelectWords`, `mouseMode`/`mouseContextMenu` (DeX/hardware), `hardwrapColumn`, `cacheRenderNodeForLongLines`. `setInputType()` is public; `IME_FLAG_NO_FULLSCREEN|NO_EXTRACT_UI` already set — directly serves your R2.
- VERIFIED: bus factor is 1. Rosemoe has 1981 of ~2600 lifetime commits and 27 of 33 human commits in the last 90 days. dingyi222666 (LSP author, 240) is largely inactive. AndroidIDE's itsaky contributed 108.
- VERIFIED rough edges, currently open: #847 OOM in `EditorSearcher`, #863 crash on binary files with null bytes, #845 LSP ANR on hover, #872 crash after update. Document model is `List<ContentLine>` (whole file in RAM, no rope/piece-table, no virtualization) — fine for source files, not for huge logs.
- CORRECTION TO PREMISE / D9: CodeMirror 6 is NOT dying. All 55 GitHub repos were archived 2026-04-15 because Marijn Haverbeke migrated to self-hosted Forgejo (code.haverbeke.berlin). `@codemirror/view` 6.43.8 shipped 2026-08-04, and an official `@codemirror/lsp-client` now exists (6.2.5, 2026-06-09). D9's fallback is stronger than when written.

## Details
## 1. Verdict on the premise

Your framing — "the UI is a website" — bundles two separable problems.

| Observed failure | Is it an *editing surface* problem? | Cheapest real fix |
|---|---|---|
| F1 desktop layout doesn't fit | No — it's chrome | Native Compose shell, workbench iframed/embedded per-pane |
| F2 IME breaks layout | No — it's chrome | Native `WindowInsetsCompat.ime()` on the container |
| F3 terminal | No | Termux `terminal-view` (already your plan) |
| Selection handles / magnifier | **Yes** | Native editor, or CM6 |

Only the selection-handles floor in `docs/10` genuinely forces a native *editor*. That floor is real — Monaco renders lines into absolutely-positioned DOM and takes input through a hidden `<textarea>`, so Android never attaches native handles or the magnifier. CodeMirror 6 puts the document in `contenteditable`, so WebView *does* give it native handles, magnifier and IME — this is why Replit and CodeSandbox use CM6 on mobile. (Architecture: verified. Mobile-quality conclusion: inferred, high confidence.)

So the honest sequencing is: **native chrome first (fixes F1/F2/F3, ~all your reported pain), sora second.** Your R6 already implies this.

## 2. sora-editor — capability matrix

| Capability | State |
|---|---|
| Selection handles, magnifier | Yes (`Magnifier.java`, ICU word selection) |
| Wordwrap | Yes (`WordwrapLayout.java`), speed improved 0.24.2 |
| Sticky scroll | Yes, configurable (`stickyScroll*`) |
| Minimap | Yes, 0.24.5+ (marked experimental) |
| Inlay hints | Yes, 0.24.6, with click events |
| Diagnostics rendering | Yes — wave indicators, tooltip windows, containers |
| Snippets | Yes, full LSP-style with variable resolvers |
| Hardware keyboard / mouse | Yes — key bindings, AltGr, `mouseMode`, context menu |
| IME / autocorrect control | Yes — `disallowSuggestions`, composing-text props |
| **Code folding** | **No** — issue #85 open since 2021 |
| **Multi-cursor** | **No** — issue #798, not started |
| Large files | Whole file in RAM (`List<ContentLine>`); OK for source, not for logs |

AAR sizes (0.24.6): editor 0.75 MB, editor-lsp 0.50 MB, language-textmate 0.26 MB, language-treesitter 0.08 MB, oniguruma-native 1.24 MB.

## 3. The LSP reality — this is the crux

`editor-lsp` gives you transport + the four easy features. It does **not** give you go-to-definition, references, rename, document symbols, folding ranges, or semantic tokens — all of which your R4 names. `RequestManager` already has every one of those methods; what's missing is the `events/` + UI wiring.

The strongest evidence: **Code on the Go rejected `editor-lsp`.** They kept sora's `editor` and `language-textmate`, pinned to 0.23.6, and built their own LSP stack on lsp4j 0.22.0 across `lsp/api`, `lsp/java`, `lsp/kotlin`, `lsp/xml`, `lsp/indexing`, `lsp/jvm-symbol-index`. That is what a serious Android IDE actually needed.

**Concrete transport problem for your D4 Stage 1 (companion mode):** the language server runs inside the *Termux app's* uid, launched via `RUN_COMMAND`. You cannot reach its stdio. sora's `StreamConnectionProvider` needs streams. Practical recipe: run `socat TCP-LISTEN:<port>,reuseaddr,fork EXEC:"pyright-langserver --stdio"` in Termux and use `SocketStreamConnectionProvider` on loopback. At Stage 2 (embedded, same uid) switch to `LocalSocketStreamConnectionProvider`. Budget this bridge explicitly — it is not documented anywhere.

## 4. Alternatives, honestly

| Option | Verdict |
|---|---|
| **CodeMirror 6 in WebView** | Genuinely good on mobile (contenteditable ⇒ native handles/IME), alive (6.43.8, 2026-08-04), now has an official LSP client. Still web: no Compose theming, still a WebView per pane. Best *fallback*, and D9 is more attractive than you thought. |
| **Compose-native from scratch** | Don't. `Qawaz/compose-code-editor` (87★, dead since 2024-04), `taha-cmyk/exposed` (26★), `android-compose-highlight` (26★, JS-bridge). All toys. Compose `BasicTextField` has no viewport virtualization for 10k-line files. |
| **New 2025-26 native components** | None found. The category is empty. |
| **JetBrains multiplatform editor** | Doesn't exist as a reusable component. Fleet's editor is closed; Fleet became "Air" (May 2026). Jewel is desktop IntelliJ chrome, not an editor widget. |

## 5. Integration effort (solo, honest)

| Phase | Scope | Estimate |
|---|---|---|
| A | Gradle wiring, Kotlin 2.2→2.3 bump, desugaring, AndroidView wrapper, TextMate grammars + VS Code themes pulled from the on-device code-server | 1–2 wk |
| B | IME insets, key row, fold posture/state retention, tabs & splits | 2–4 wk |
| C | LSP socket bridge from Termux + completion/hover/diagnostics via editor-lsp | 2–4 wk |
| D | go-to-def, references, rename, symbols, semantic tokens on `RequestManager` | 4–8 wk |
| E | Folding, multi-cursor | Fork territory, 4+ wk each |

Plan on vendoring a soft fork by Phase D — every serious consumer has.

## Confidence
VERIFIED by reading primary sources (Maven Central POMs/metadata, GitHub API, raw source files, official docs): all version numbers, coordinates, module lists, dependency versions, AAR sizes, `DirectAccessProps` fields, `RequestManager` method names, `LspFeature` enum, editor-lsp `events/` tree, contributor counts, issue states, Code on the Go's catalog and its `ContentLockAccessor`/`IDEEditorSearcher` shims, CodeMirror npm publish dates, lsp4j 1.0.0 as current.

INFERRED (flagged in the body): (a) that CM6 gets native Android selection handles because it uses contenteditable while Monaco does not — the architectural difference is well established but I did not run a device test; (b) the Kotlin 2.2 vs 2.3.10 metadata collision is a strong prediction from the published POM, not something I compiled and observed — verify with a scratch build before planning around it; (c) the phase estimates are my judgement calibrated against Code on the Go's actual 679-file LSP tree, not measured.

NOT ESTABLISHED: whether sora's minimap and inlay hints are production-stable (both are recent and the minimap is labelled experimental); real-world large-file thresholds (no benchmark exists — the `List<ContentLine>` model is the only hard evidence); whether Android SELinux permits abstract-namespace LocalSocket between the Termux app and yours (I recommend TCP loopback for Stage 1 for that reason, untested).

One caveat on Code on the Go's 0.23.6 pin: I verified the pin but not the reason. It may be inertia rather than a deliberate rejection of 0.24.x — do not over-read it as evidence that 0.24.x is unstable.

## Sources
- Rosemoe/sora-editor — main repository: https://github.com/Rosemoe/sora-editor
- sora-editor releases (0.24.6, 2026-06-10) via GitHub API: https://api.github.com/repos/Rosemoe/sora-editor/releases
- editor-lsp README — "Experimental, work in progress", feature list + TODO: https://github.com/Rosemoe/sora-editor/blob/main/editor-lsp/README.md
- sora-editor issue #85 — Support code folding (open since 2021): https://github.com/Rosemoe/sora-editor/issues/85
- sora-editor issue #798 — Feature request: multiple cursor editing: https://github.com/Rosemoe/sora-editor/issues/798
- Sora Editor docs — Getting Started (coordinates, JDK 17, minSdk, desugaring): https://project-sora.github.io/sora-editor-docs/guide/getting-started
- Sora Editor docs — CodeEditor in Compose (AndroidView recipe): https://project-sora.github.io/sora-editor-docs/guide/code-editor-in-compose
- Code on the Go — version catalog pinning sora 0.23.6 + lsp4j 0.22.0: https://raw.githubusercontent.com/appdevforall/CodeOnTheGo/main/gradle/libs.versions.toml
- appdevforall/CodeOnTheGo — GPLv3 production Android IDE using sora-editor: https://github.com/appdevforall/CodeOnTheGo
- FSF license list — LGPLv2.1 "is compatible with GPLv2 and GPLv3": https://www.gnu.org/licenses/license-list.html

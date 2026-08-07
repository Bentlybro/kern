# ide-landscape

## Summary
The Android-native IDE landscape in 2026 is a graveyard of solo-maintainer projects and cloud-execution business models: AndroidIDE (the most serious on-device Android-app builder) was archived Oct 2024, CodeAssist stalled into an unfinished v3 rewrite, VHEditor was pulled from Play in Feb 2025, and Dcoder (cloud-compile, 5M users) shut down entirely on March 31, 2026. The survivors cluster into three architectures: web-view editors with plugin ecosystems (Acode on Ace, Spck), bundled-native-runtime apps (IIEC's Pydroid 3/Cxxdroid), and terminal/VS-Code-server approaches (Termux + code-server, and new 2024-26 wrappers like VSCodroid), all of which fight Android's phantom-process killer and exec restrictions. Replit built a native mobile IDE in 2022 and then pivoted the app away from direct code editing to an Agent-first "vibe coding" client in 2024-25. iPad apps (Code App, a-Shell, Swift Playgrounds) solved execution more elegantly via in-process runtimes, WASM compilation targets, and platform-blessed toolchains. The dominant recurring user complaints everywhere are keyboard/input UX, background process death, and monetization friction — not missing language breadth.

## Key facts
- AndroidIDE (on-device Gradle builds, JDK 11/17, Sora/Rosemoe editor + tree-sitter, custom Java/XML/Kotlin language servers) was archived on GitHub Oct 18, 2024 with an explicit 'THIS PROJECT IS NOT MAINTAINED ANYMORE' banner.
- Dcoder, the cloud-execution mobile IDE (60+ languages compiled server-side, freemium with daily run limits), shut down March 31, 2026 after 10 years, 5M+ developers and 15M+ projects (announcement on dcoder.tech).
- Termux's core failure mode is Android 12+'s phantom process killer (32 child-process cap, SIGKILL 'signal 9') — tracked in termux-app issue #2366; the F-Droid stable is still v0.118.3 (May 2025) while a separately-versioned Play Store build returned (~googleplay.2026.02.11, Android 11+).
- Acode (Ace editor core in a webview shell, MIT, Play + F-Droid + GitHub) is the healthiest FOSS Android editor: 250+ plugins, LSP via language-server plugins, and it added an integrated proot Alpine Linux terminal and AI features, rebranding as 'Acode IDE: Terminal & AI Code'.
- Acode's top GitHub-issue themes are stability (stuck on launch animation, crashes after F-Droid updates) and 'keyboard toolbar too large, blocks the editor' — input UX, not features.
- Squircle CE is deliberately editor-only (no execution, no LSP, no terminal); v2025.1.0 (Apr 2025) replaced its editor core with a new custom renderer and added Git; last release May 2025 — maintained but slow.
- VHEditor (code-server wrapped in an Android WebView app) was removed from Google Play on Feb 17, 2025; new 2024-26 successors like VSCodroid run a native ARM64 VS Code Server + local WebView client, and a paid 'VScode for Android' app on Play ships VS Code v1.101.2 with Copilot.
- IIEC's Pydroid 3 / Cxxdroid bundle real native toolchains (CPython + pip, clang) on-device, Play-only and closed-source; recurring complaints: aggressive ads, premium-gated libraries, device overheating, and unreachable support.
- Spck Editor (web-focused JS IDE with in-app preview and Git; NodeJS edition embeds a Node runtime, now Node 24.5.0) was still updated July 26, 2025 and added AI completion — freemium, Play-distributed.
- Replit launched native mobile IDE apps in 2022, then reoriented them around Agent in 2024-25: the iOS app is now explicitly 'built for vibe coding websites and web apps,' and building native mobile apps requires desktop web — the direct-editing mobile IDE was effectively retired in place.
- CodeAssist's original repo stalled (Play listing lingers); the v3 rewrite openly states it is 'no longer chasing Android Studio' and targets beginners instead — the second on-device-Android-builder to give up on parity.
- Code App (thebaselab, iPad, MIT, monaco-editor core) executes locally via bundled runtimes: Python 3.9 native, Node 18 via nodejs-mobile, C/C++ via clang-to-WebAssembly, OpenJDK 8, PHP 8.3, with LSP for Python/Java and a 70+-command terminal — still active (3.9k stars).
- a-Shell (iOS) executes user-compiled C/C++ by targeting WebAssembly (clang → wasm3 interpreter) with commands running in-process via ios_system; actively updated in 2025 (LLVM 18.1.5, ffmpeg 7.0, texlive-2025).
- New 2025-26 entrants trend thin-client/cloud-agent: Cosyra ($29.99/mo) runs Ubuntu x86_64 containers on Azure with Claude Code/Codex/Gemini CLI preinstalled, marketing itself explicitly as the escape from Termux's phantom-process killer — at the cost of requiring connectivity.

## Details
## Comparison table

| App | Editor core | Execution model | LSP | Status (2026) | Key complaint |
|---|---|---|---|---|---|
| Termux (+nvim/code-server) | terminal (nvim, etc.) | native pkgs in app sandbox; proot distros | via nvim/code-server | Active; F-Droid v0.118.3 (5/2025), Play build restored | Phantom-process killer kills sessions (signal 9); setup burden |
| AndroidIDE | Sora (Rosemoe) + tree-sitter | on-device Gradle, JDK 11/17 | custom Java/XML/Kotlin LS | **Archived 10/2024** | Abandoned; RAM/heat; no NDK |
| CodeAssist | Rosemoe-based (unverified) | on-device javac/AAPT2/R8 | partial custom completion | Stalled; v3 rewrite, no stable | Never left alpha; scope retreat |
| Acode | Ace (webview shell) | plugins; proot Alpine terminal; HTML preview | yes, via LSP plugins | Active (1.11.8, 1/2026); Play/F-Droid/GitHub, MIT | Startup crashes; keyboard toolbar blocks editor |
| Squircle CE | custom renderer (new in 2025.1.0) | **none** (editor only) | no | Maintained, slow (5/2025) | Can't run anything |
| Spck | web editor (CodeMirror-family, unverified) | WebView preview; embedded Node 24 (paid edition) | no (AI completion instead) | Active (7/2025), freemium | Web-stack only |
| Dcoder | custom mobile editor | **cloud compile**, daily limits | no | **Shut down 3/31/2026** | Internet-required; limits; then death |
| Pydroid 3 / Cxxdroid | custom (closed) | bundled CPython+pip / clang on-device | no (own completion) | Active, Play-only, freemium | Ads, paywalled libs, overheating |
| VHEditor | VS Code web (code-server) | Termux-like local Node backend | yes (VS Code ext.) | Delisted from Play 2/2025; semi-dormant | Heavy, laggy, extension breakage |
| VSCodroid / 'VScode for Android' (2024-26) | VS Code web client | local ARM64 VS Code Server on-device | yes (extensions) | New, active; Play | WebView perf/battery; trust in unofficial ports |
| Replit mobile | Monaco-based → Agent chat UI | cloud VMs | cloud-side | Pivoted Agent-first 2024-25 | Direct editing de-emphasized/retired |
| GitHub Mobile | simple text editor | none (commit only) | no | Active; PR-from-branch added 2025 (v1.193) | Single-file edits only |
| Code App (iPad) | Monaco | bundled runtimes: Python, Node (nodejs-mobile), clang→WASM, JDK8, PHP; + remote SSH/containers | Python/Java | Active, MIT | 585 open issues; iOS runtime version lag (Node 18, JDK 8) |
| a-Shell (iPad) | terminal | in-process ios_system cmds; user code → WASM | no | Active (LLVM 18, 2025) | No true background processes |
| Swift Playgrounds | Apple custom | full Swift toolchain on-device; App Store submission from iPad | Swift only (sourcekit) | Active, Apple-backed | Swift/SwiftUI only |

## Notable dynamics

- **Execution is where projects die.** Cloud execution (Dcoder) died of unit economics; on-device toolchains (AndroidIDE, CodeAssist) died of maintenance weight; Termux survives but is permanently at war with Android's process limits and targetSdk/W^X exec rules (Play version had to be re-engineered separately). The IIEC model — link interpreters/compilers as native libraries inside the APK — is the most Play-policy-durable local option; Code App's clang→WASM trick sidesteps OS code-signing/exec restrictions entirely and is portable to Android.
- **Editor cores converge on three choices**: Sora/Rosemoe (native Android View, tree-sitter, used by AndroidIDE; still maintained, last release 6/2025), Ace/Monaco in WebView (Acode, VS Code wrappers — best ecosystem, worse input latency and IME quirks), or bespoke (Squircle — fast but feature-poor). Mobile IME interaction (composing text, autocorrect, cursor gestures) is the hardest unsolved problem in all of them.
- **LSP is the differentiator users actually feel.** Acode's plugin LSP, AndroidIDE's bespoke servers, and Code App's Python/Java LSP are the praised features; Squircle/Spck/Dcoder skipping it capped their ceiling. Running language servers locally requires the same process infrastructure as a terminal — so a terminal/runtime layer pays for itself twice.
- **Replit's arc is the strategic warning**: they shipped the most polished native mobile IDE (2022), found mobile editing engagement insufficient, and by 2025 rebuilt the app as an Agent-first prompt/preview client for web apps. New entrants (Cosyra, VibeCode, YouWare) are all agent-thin-clients. AI assist is now table stakes (even Acode and Spck added it), but pure cloud dependence recreates Dcoder's fragility.
- **GitHub Mobile** proves demand for quick single-file commit flows but explicitly declines to be an IDE.

## Five biggest lessons for a serious foldable IDE

1. **Pick a layered execution strategy up front**: bundled in-APK native runtimes (Python/Node/clang, IIEC-style) for offline instant-run; WASM as a sandboxed compile target (Code App/a-Shell-style); optional proot distro for the long tail; optional remote/SSH/cloud-agent for heavy builds. Never make cloud the only path (Dcoder) and never rely on spawning unlimited child processes (Termux's phantom-process wound). Design around Android 12+ limits: few long-lived processes, foreground service, exec only from native lib dir.
2. **Input UX beats features**: the #1 recurring complaint across Acode, Termux, and Play reviews is the soft keyboard — toolbar occlusion, missing symbols row, cursor movement. Invest in a custom key row, gesture cursor, hardware-keyboard parity, and foldable-specific layouts (editor+terminal/preview across the fold; half-fold laptop posture).
3. **Ship LSP + Git + run-preview as the core loop, not language count.** One well-wired LSP pipeline (tree-sitter highlighting + LSP completion/diagnostics, per AndroidIDE/Acode) outperforms 60 shallow languages. Reuse maintained components (Sora editor, tree-sitter, isomorphic or libgit2 Git) — every bespoke stack here got abandoned.
4. **Plan for sustainability and distribution split**: solo-maintainer projects archived (AndroidIDE), stalled (CodeAssist), or got delisted (VHEditor). Play policy (targetSdk deadlines, exec restrictions) diverges from F-Droid freedom — architect one codebase with a compliant Play build and a fuller sideload/F-Droid build, like Termux now does.
5. **Make AI an integrated layer, not the product**: Replit's pivot shows agent-first can eclipse editing; Cosyra shows thin-client agents monetize; but the durable niche for a foldable IDE is local-first editing with agents attached (local FS access + cloud agent execution), which none of the current Android apps combine well. Monetize without ad-walls or paywalled libraries — that's the top rage-trigger in Pydroid reviews.

## Confidence notes
Dcoder shutdown (3/31/2026) confirmed directly from dcoder.tech; AndroidIDE archival confirmed from its GitHub repo — both high confidence. Medium confidence: Termux Play Store build details (googleplay.2026.02.11, Android 11+ requirement) came from one aggregated search summary and were not independently verified on Play; VHEditor's Play removal date (2/17/2025) is from AppBrain via search. Version conflict: Acode reported as 1.11.8 (Jan 2026) in one source while APKMirror lists a 1.12.7 GitHub-version APK — likely dual release channels (Play vs GitHub nightly); exact latest version unverified. Low confidence / unverified: Spck's editor core being CodeMirror-derived (inferred, not documented); CodeAssist using a Rosemoe-based editor component (from memory of the codebase, not re-verified); whether Spck NodeJS uses nodejs-mobile specifically. Replit 'retiring' its mobile IDE is an inference from official messaging (app 'rebuilt' Agent-first, iOS app 'built for vibe coding', native app builds require desktop web) — no explicit deprecation announcement was found, so characterize as a pivot/de-emphasis rather than a formal shutdown. Cosyra's pricing/architecture claims come from its own marketing page and should be treated as vendor claims. Pydroid complaint themes (ads, overheating, premium gating) are aggregated from review-summary sites rather than read directly from Play reviews. Complaint data for Squircle and Spck is thin — inferred mainly from feature absence rather than volumes of user reports.

## Sources
- AndroidIDE (archived Oct 2024): https://github.com/AndroidIDEOfficial/AndroidIDE
- Dcoder shutdown announcement: https://dcoder.tech/
- Acode GitHub (issues/themes): https://github.com/Acode-Foundation/Acode
- Squircle CE releases: https://github.com/massivemadness/Squircle-CE/releases
- VHEditor-Android (code-server wrapper): https://github.com/vhqtvn/VHEditor-Android
- Code App README (iPad runtimes): https://github.com/thebaselab/codeapp/blob/main/README.md
- Termux phantom process killer issue #2366: https://github.com/termux/termux-app/issues/2366
- VSCodroid (native VS Code Server port): https://github.com/rmyndharis/VSCodroid
- Replit mobile apps positioning: https://replit.com/mobile-apps
- Cosyra vs Termux (cloud thin-client entrant): https://cosyra.com/guides/cosyra-vs-termux.html


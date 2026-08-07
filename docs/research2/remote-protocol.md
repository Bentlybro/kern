# remote-protocol

## VERDICT
Reusing code-server's *remote protocol* as a native app's backend is not viable — not because it's impossible (it is possible; I verified every layer and the signing bypass), but because the 4-6 weeks to reach "I can list a directory and run a shell" buys you capabilities you already have on-device, and the layer that actually matters (completions, diagnostics, go-to-definition) sits behind a 4,000-line undocumented RPC surface that changes every month and has killed the one team that tried. However, "bypass code-server, go straight to LSP/DAP" is only the *second*-best answer. The best answer is a middle path the question doesn't list: keep code-server running exactly as it is, install one small custom VS Code extension into it (~500 lines of TypeScript), and have that extension expose a WebSocket/JSON-RPC API that *you* design, backed by the public, documented, semver-stable VS Code extension API (`vscode.executeCompletionItemProvider` and friends, `vscode.debug`, `Terminal`, `workspace.fs`). Your Kotlin client then talks a protocol you own and can't have broken out from under you, while the extension host keeps doing the hard work and you keep the entire VS Code extension ecosystem — which raw LSP would cost you. Fall back to direct LSP/DAP (via Eclipse LSP4J on the JVM) only for languages where you'd rather ship a single language server than a whole Node runtime. Either way, the fold-native UI work is entirely client-side and is unaffected by this choice, so it should not block on it.

## Summary
The VS Code remote protocol is real, fully readable in microsoft/vscode source, and technically speakable from Kotlin — but it is undocumented, unversioned, commit-pinned by design, and nobody in the world has built a third-party client for it. I verified the complete handshake (3-message auth/sign/connectionType exchange), the 13-byte binary framing layer, the channel-RPC layer with its custom VSBuffer serializer, and the exact list of server-side channels. I also verified the one thing that makes a third-party client possible at all: in OSS builds without the proprietary `vsda` module, `AbstractSignService` degrades to pass-through — `sign()` returns its input and `validate()` returns `true` for empty-id messages — so the "DRM" handshake is a no-op against code-server. The premise of the question is not wrong, but it is aimed at the wrong layer. Everything reachable on the management connection (files, pty, ripgrep) is stuff you already have locally on-device and could implement in a week. The one thing actually worth extracting — language intelligence — does not live there; it lives behind the extension-host RPC (`extHost.protocol.ts`, 50-70+ bidirectional proxy interfaces, no versioning, breaking changes handled by appending "Shape2" to interface names). The only project that ever seriously attempted a native UI on VS Code's extension host, Onivim 2, spent roughly two years on it, never caught up to upstream, and is dead. Verdict: do not speak the remote protocol. Either bypass code-server entirely for LSP/DAP, or — better — keep code-server and put your own extension inside it exposing an API you design.

## Key facts
- VERIFIED: The handshake is exactly three messages — AuthRequest {type:'auth', auth, data} → SignRequest {type:'sign', data, signedData} → ConnectionTypeRequest {type:'connectionType', commit, signedData, desiredConnectionType, args} → OK/Error. ConnectionType enum: Management=1, ExtensionHost=2, Tunnel=3 (remoteAgentConnection.ts).
- VERIFIED (the load-bearing finding): AbstractSignService degrades to a no-op without the proprietary `vsda` native module — `sign()` catches and `return value` unchanged; `createNewMessage()` returns `{id:'', data:value}`; `validate()` returns `true` when `!message.id`. The signing challenge is therefore not a real barrier against an OSS/code-server build.
- VERIFIED: Server hard-rejects mismatched builds — `if (rendererCommit !== myCommit) return rejectWebSocketConnection('Client refused: version mismatch')`. A Kotlin client must send the exact commit hash of the code-server build it targets. This is not a versioned protocol; it is a pinned one.
- VERIFIED: Wire framing is a 13-byte binary header inside the WebSocket payload — [1B type][4B id BE][4B ack BE][4B dataLength BE]. ProtocolMessageType: None=0, Regular=1, Control=2, Ack=3, Disconnect=5, ReplayRequest=6, Pause=7, Resume=8, KeepAlive=9. Constants: AcknowledgeTime 2000ms, TimeoutTime 20000ms, KeepAliveSendTime 5000ms, ReconnectionGraceTime 3h.
- VERIFIED: A *second* RPC layer sits on top — RequestType {Promise=100, PromiseCancel=101, EventListen=102, EventDispose=103}, ResponseType {Initialize=200, PromiseSuccess=201, PromiseError=202, PromiseErrorObj=203, EventFire=204}, with a bespoke VSBuffer serializer (DataType: Undefined=0, String=1, Buffer=2, VSBuffer=3, Array=4, Object=5, Int=6) using VQL-encoded lengths. You must reimplement this by hand; there is no schema.
- VERIFIED: Channels registered on the management connection (serverServices.ts): 'logger', 'userDataProfiles', 'remoteextensionsenvironment', 'telemetry', 'remoteterminal', 'request', 'extensions', 'mcpManagement', the remote filesystem channel, RemoteExtensionsScanner, ExtensionHostDebugBroadcast, NativeMcpDiscoveryHelper, McpGateway, and (new in 2026) AgentHostIpcChannels.RemoteProxy.
- VERIFIED: The terminal channel ('remoteterminal') is genuinely rich and usable piecemeal — CreateProcess, Start, Input, Resize, Shutdown, ListProcesses, AttachToProcess, DetachFromProcess, GetInitialCwd, GetCwd, GetDefaultSystemShell, GetProfiles, SerializeTerminalState, plus events OnProcessDataEvent/OnProcessExitEvent/OnProcessReadyEvent/OnProcessReplayEvent. None of it is documented or stable.
- VERIFIED: NO public spec exists. No documentation of the remote protocol anywhere in microsoft/vscode docs. Microsoft's Remote Development extensions and official VS Code Server are closed-source; the VS Code Server license forbids reverse engineering and forbids 'provide the software as a stand-alone offering or combine it with any of your applications for others to use'. Only code-server (MIT, built from vscode OSS) is a legally safe target.
- VERIFIED: NO third-party client exists, in any language. Exhaustive searching found zero Rust/Go/Python/Kotlin implementations. The closest artifacts are: (a) Parsiya's 2021 RCE write-up, which did drive the real handshake from browser JS to ConnectionType.ExtensionHost — but cheated on signing by shelling out to Node+vsda, and (b) xaberus/vscode-remote-oss, which is a VS Code *extension* registering a RemoteAuthorityResolver, i.e. still VS Code's own client code.
- VERIFIED: The only serious 'native UI on VS Code's backend' project ever attempted is Onivim 2 (onivim/oni2 + onivim/vscode-exthost). It spoke extHost.protocol RPC to a locally-spawned extension host, not the remote WebSocket protocol. Development stopped around 2022; oni2 issue #2779 is literally 'VS Code Extension Version Outdated'. vscode-exthost has 13 stars.
- VERIFIED: code-server's only *documented* API is /healthz (unauthenticated, returns {status, lastHeartbeat}). Its real route table is /healthz, /login, /logout (password auth only), /proxy/:port/*, /absproxy/:port/* (both with WS upgrade), /_static, /update, /robots.txt, /.well-known/security.txt, and / + /vscode (the workbench + WS upgrade). Zero stability commitment on any of them.
- VERIFIED: Upstream server HTTP surface is /version (product commit, no token needed), /delay-shutdown, /vscode-remote-resource?path=&tkn=, /static/*, /callback, /web-extension-resource/*. Connection token arrives as a query param and is converted to a 7-day cookie. WebSocket upgrade is signalled by query params reconnectionToken (UUID), reconnection (bool), skipWebSocketFrames (bool).
- VERIFIED: Language intelligence is NOT on the management connection. It requires ConnectionType.ExtensionHost and the extHost.protocol RPC surface — 50-70+ bidirectional proxy interfaces (MainThreadLanguageFeatures, MainThreadDocuments, MainThreadWorkspace, MainThreadDebugService, ExtHostLanguageFeatures, …), ~3,500-4,000 lines, with NO version negotiation; breaking changes are absorbed by creating parallel interfaces suffixed 'Shape2'.
- INFERRED (high confidence, from architecture not this session's fetches): on-device, the file and search services are worthless to reuse — the app already has the filesystem and can ship a ripgrep binary. The pty is duplicable in days. So ~90% of the protocol work buys ~10% of the value.

## Details
## 1. What the protocol actually is (VERIFIED from source)

Four stacked layers, each of which you would have to reimplement in Kotlin:

| Layer | Where | What it is | Reimplementation cost |
|---|---|---|---|
| L0 Transport | `remoteExtensionHostAgentServer.ts` | WS upgrade with `?reconnectionToken=<uuid>&reconnection=<bool>&skipWebSocketFrames=<bool>`; optional permessage-deflate | 2-3 days |
| L1 Framing | `base/parts/ipc/common/ipc.net.ts` | 13-byte header `[type:u8][id:u32be][ack:u32be][len:u32be]`, 9 message types, ack/keepalive/replay state machine | 1-2 weeks |
| L2 Handshake | `platform/remote/common/remoteAgentConnection.ts` | auth → sign → connectionType; commit hash must match server exactly | 3-5 days |
| L3 Channel RPC | `base/parts/ipc/common/ipc.ts` | Request 100-103 / Response 200-204, bespoke VSBuffer serializer with VQL lengths, header `[type, id, channelName, name]` | 2-3 weeks |
| L4 Payloads | `server/node/serverServices.ts` + each channel | Internal TypeScript types, no schema, re-derived per version | 3-6 weeks for terminal+fs+env |
| L5 Extension host | `workbench/api/common/extHost.protocol.ts` | The actual point. 50-70+ proxies, ~4k lines, "Shape2" versioning | 6-12+ months, never finished |

L0-L3 are the pleasant part: small, mechanical, testable. That is the trap. They are ~5-6 weeks of satisfying work that ends with a terminal and a file lister on a device that already has both.

## 2. The signing question, settled

The `vsda` "DRM" is not a barrier. `AbstractSignService.sign()` is `try { return await this.signValue(arg) } catch { } return value` — it silently returns the unsigned input. `validate()` short-circuits `if (!message.id) return true`. Against an OSS build (code-server), a Kotlin client sends `{"type":"auth","auth":"<token>","data":"<anything>"}`, echoes back whatever `signedData` it receives, and is admitted. Parsiya's 2021 write-up confirms this empirically — they reached `desiredConnectionType: 2` with `args: {break:true, port:55000}` and got `{debugPort: 55001}` back.

Note the corollary: this is only true because you control the server. Against Microsoft's official VS Code Server it is both harder and a licence violation (`code.visualstudio.com/license/server` prohibits reverse engineering and combining with your applications for others to use).

## 3. Precedent: there is none, and the near-precedent died

| Project | What it is | Status |
|---|---|---|
| onivim/oni2 + vscode-exthost | Native (Revery/OCaml) UI driving VS Code's extension host locally | **Dead ~2022.** The only real attempt. Never caught up with upstream extension API. |
| xaberus/vscode-remote-oss | VSCodium extension registering a `RemoteAuthorityResolver` | Alive — but it's VS Code's own client, not a reimplementation |
| Parsiya RCE PoC | Browser JS driving the real handshake | Exploit PoC, 2021, ~200 lines, not an editor |
| VSCodroid / VSCodeOnAndroid / Termux code-server | Android | All WebView-over-code-server. Exactly what you have today. |

Zero Rust, Go, Python, Java or Kotlin clients exist. For a protocol this old and this widely deployed, that absence is the strongest possible signal about its tractability.

## 4. Honest cost comparison

| Path | To first useful completion | Ongoing breakage | Ecosystem |
|---|---|---|---|
| Kotlin speaks remote protocol | 6-9 months (L0-L4 then L5 subset) | Unbounded. Monthly VS Code releases; commit-pinned; no deprecation policy because there's no policy at all | Full, eventually, in theory |
| Custom extension inside code-server | **1-2 weeks** | Near zero — VS Code extension API is public and semver-stable | Full, immediately |
| Direct LSP/DAP via LSP4J | 3-5 weeks | Near zero — LSP 3.17/3.18 is a published, capability-negotiated spec | None; you package servers yourself |

## 5. The recommended architecture

Keep the code-server process. Delete the WebView. Add a ~500-line TypeScript extension that opens a WebSocket on localhost and exposes methods you define, implemented over the *public* extension API: `vscode.commands.executeCommand('vscode.executeCompletionItemProvider', uri, pos)` and the sibling `executeDefinitionProvider` / `executeHoverProvider` / `executeDocumentSymbolProvider` commands aggregate results from every installed extension; `languages.getDiagnostics()`, `workspace.fs`, `window.createTerminal`, and `vscode.debug` cover the rest. Your Kotlin/Compose client speaks a protocol you wrote and can version yourself.

This inverts the stability problem. You stop depending on VS Code's most volatile internal surface and start depending on its most stable public one. It also preserves the actual reason to keep code-server around — extensions — which the LSP/DAP path throws away.

Reserve direct LSP/DAP (Eclipse LSP4J runs fine on the JVM/Android) for cases where shipping one language server binary beats shipping a Node runtime, e.g. a lightweight mode with `kotlin-lsp` or `gopls` only.

Finally: none of this gates the fold work. Tabletop mode, cover-screen, and real Android panes are pure client-side Compose concerns. Build the native UI against a stub backend first; the backend decision can land later without redesign.

## Confidence
HIGH CONFIDENCE / DIRECTLY VERIFIED this session by reading raw source: the handshake message shapes and ConnectionType values; the 13-byte framing header and ProtocolMessageType/ProtocolConstants values; the ipc.ts RequestType/ResponseType/DataType enums; the AbstractSignService no-op degradation (I read the full class body); the version-mismatch rejection code; the complete serverServices.ts channel registration list; the remoteterminal channel method/event names; code-server's route table; the VS Code Server licence text; the absence of any third-party client after ~6 distinct searches.

MEDIUM CONFIDENCE: the extHost.protocol.ts figures (50-70+ proxies, ~3,500-4,000 lines). The file moved from src/vs/workbench/services/extensions/common/ to src/vs/workbench/api/common/ and my fetch of the current path returned a summarised estimate rather than an exact count — treat the numbers as order-of-magnitude. The "Shape2 instead of versioning" observation is solid and independently visible in the interface names.

INFERRED, NOT VERIFIED THIS SESSION: the specific list of `vscode.execute*Provider` built-in commands in the recommended middle path comes from prior knowledge of the VS Code API command reference, not from a fetch in this session. It should be confirmed against https://code.visualstudio.com/api/references/commands before committing to the design — it is the load-bearing assumption of the recommendation. Similarly, LSP4J's Android/desugaring compatibility is asserted from general JVM knowledge and should be prototyped before being relied on.

ENGINEERING COST ESTIMATES are my judgement, not measured data. They assume one experienced developer. The L5 (extension host) figure is anchored on Onivim 2's actual multi-year trajectory, which is the only empirical data point that exists.

CAVEAT ON RECENCY: the AgentHostIpcChannels.RemoteProxy channel and the Agent Host Protocol (microsoft/agent-host-protocol, JSON-RPC over WebSocket, documented at microsoft.github.io/agent-host-protocol) are new since my training cutoff and are described by Microsoft as "under active development". AHP is agent-session-oriented (sessions, chats, terminals, changesets) rather than an editor backend, so I did not treat it as a candidate — but it is the one area where a fresher look might change the picture, and it is worth a second pass in 6 months.

## Sources
- microsoft/vscode — remoteAgentConnection.ts (handshake, ConnectionType enum): https://raw.githubusercontent.com/microsoft/vscode/main/src/vs/platform/remote/common/remoteAgentConnection.ts
- microsoft/vscode — ipc.net.ts (13-byte framing, ProtocolMessageType, ProtocolConstants): https://raw.githubusercontent.com/microsoft/vscode/main/src/vs/base/parts/ipc/common/ipc.net.ts
- microsoft/vscode — ipc.ts (channel RPC, RequestType/ResponseType, VSBuffer serializer): https://raw.githubusercontent.com/microsoft/vscode/main/src/vs/base/parts/ipc/common/ipc.ts
- microsoft/vscode — remoteExtensionHostAgentServer.ts (WS upgrade params, token validation, version-mismatch rejection, HTTP routes): https://raw.githubusercontent.com/microsoft/vscode/main/src/vs/server/node/remoteExtensionHostAgentServer.ts
- microsoft/vscode — abstractSignService.ts (vsda no-op degradation): https://raw.githubusercontent.com/microsoft/vscode/main/src/vs/platform/sign/common/abstractSignService.ts
- microsoft/vscode — serverServices.ts (full registered channel list): https://raw.githubusercontent.com/microsoft/vscode/main/src/vs/server/node/serverServices.ts
- microsoft/vscode — remoteTerminalChannel.ts (remoteterminal RPC methods and events): https://raw.githubusercontent.com/microsoft/vscode/main/src/vs/workbench/contrib/terminal/common/remote/remoteTerminalChannel.ts
- coder/code-server — src/node/routes/index.ts (actual HTTP/WS route table): https://raw.githubusercontent.com/coder/code-server/main/src/node/routes/index.ts
- code-server FAQ — /healthz is the only documented endpoint: https://coder.com/docs/code-server/FAQ
- Parsiya — RCE in Visual Studio Code's Remote WSL (only public third-party handshake implementation): https://parsiya.net/blog/2021-12-20-rce-in-visual-studio-codes-remote-wsl-for-fun-and-negative-profit/

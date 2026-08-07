# ttyd

## VERDICT
Viable and clearly the right call. A from-scratch Kotlin client over OkHttp is roughly 150-250 lines — the protocol is a 1-byte prefix on binary WebSocket frames plus one tiny JSON handshake. There is no framing, no length prefixes, no base64, no state machine. This is substantially less work than hand-rolling an out-of-band resize channel over your existing raw TCP socket, and it deletes the `stty` echo problem outright rather than papering over it.

Concretely: replace socat with `ttyd -p 13338 -i 127.0.0.1 -W -t ... tmux new -A -s main`, and resize becomes a single `ws.send(ByteString.encodeUtf8("1{\"columns\":54,\"rows\":47}"))`.

The main thing to weigh is not protocol difficulty but session lifetime semantics (see pitfall 5) and the fact that ttyd is unauthenticated on loopback by default (pitfall 8), which is a real exposure on a multi-app device — any app holding INTERNET permission can reach 127.0.0.1:13338 and get a writable shell in Termux's UID. Your current socat setup has exactly the same hole, so this is not a regression, but ttyd gives you `-c` to actually fix it.

## Summary
I read ttyd's actual C source (src/server.h, src/protocol.c, src/server.c, src/http.c, src/pty.c) at both `main` and tag `1.7.7`, its TypeScript client (html/src/components/terminal/xterm/index.ts), libwebsockets' subprotocol negotiation and bind code, OkHttp's RealWebSocket, and termux-packages/packages/ttyd/build.sh.

ttyd solves the exact problem: it has a dedicated out-of-band RESIZE_TERMINAL message that calls `pty_resize()` on the remote pty without touching the shell's stdin. Zero echo, zero stdin pollution.

Verified constants (src/server.h lines 7-17, byte-identical between `main` and `1.7.7`):
```c
// client message
#define INPUT '0'
#define RESIZE_TERMINAL '1'
#define PAUSE '2'
#define RESUME '3'
#define JSON_DATA '{'

// server message
#define OUTPUT '0'
#define SET_WINDOW_TITLE '1'
#define SET_PREFERENCES '2'
```

termux-packages confirmed: `TERMUX_PKG_VERSION="1.7.7"`, `TERMUX_PKG_REVISION=3`, `TERMUX_PKG_AUTO_UPDATE=true`, deps `json-c, libcap, libuv, libwebsockets, openssl, zlib`. The package dir contains ONLY build.sh — no patches, so Termux ships stock upstream 1.7.7. I diffed 1.7.7 vs main: server.h identical; protocol.c differs only in sprintf→snprintf hardening and shutdown sequencing. The wire protocol is unchanged.

Biggest caveat, and it is architectural rather than protocol-level: ttyd spawns a NEW pty+process per WebSocket connection (`spawn_process()` is called from the JSON_DATA branch) and KILLS it on WS close with `--signal` (default 1 = SIGHUP). Your current socat setup has the same property, but if you want the shell to survive app backgrounding/reconnects you must run `ttyd ... tmux new -A -s main` (or dtach/abduco).

## Implementation
== SERVER SIDE (inside Termux) ==

  pkg install ttyd
  ttyd -p 13338 -i 127.0.0.1 -W -o -t disableLeaveAlert=true tmux new -A -s foldcode

Flags verified against src/server.c options[] and print_help() at tag 1.7.7:
  -p, --port         7681 default, `0` = random
  -i, --interface    "Network interface to bind (eg: eth0), or UNIX domain socket path (eg: /var/run/ttyd.sock)".
                     Accepts a numeric IP: lws_interface_to_sa() first matches ifa_name via getifaddrs(),
                     then on failure falls back to lws_sa46_parse_numeric_address(ifname,&sa46).
                     `-i lo` is the version-proof alternative. Note: an arg ending .sock/.socket
                     switches to a UNIX socket (server.c:549-553) — do NOT use, wrong UID.
  -W, --writable     REQUIRED. Without it protocol.c:313 does `case INPUT: if (!server->writable) break;`
                     — every keystroke is silently discarded.
  -t, --client-option  key=value, repeatable; JSON-serialized into the SET_PREFERENCES message.
  -T, --terminal-type  default "xterm-256color" (server.c:170). Sets TERM in the child env.
  -c, --credential   user:pass. server->credential is stored BASE64-ENCODED
                     (server.c:399-400 `lws_b64_encode_string(optarg, ...)`).
  -m, --max-clients  default 0 = no limit.
  -o, --once         accept only one client, exit on its disconnect.
  -q, --exit-no-conn exit when all clients disconnect.
  -O, --check-origin require Origin to match Host.
  -b, --base-path    prefixes all endpoints, e.g. /mounted/here/ws
  -s, --signal       signal sent to the child on WS close, default 1 (SIGHUP).

Endpoints (server.c:23/24, same both versions):
  struct endpoints endpoints = {"/ws", "/", "/token", ""};

Subprotocol registration (server.c:29-31):
  static const struct lws_protocols protocols[] = {
      {"http-only", callback_http, sizeof(struct pss_http), 0},
      {"tty",       callback_tty,  sizeof(struct pss_tty),  0},
      {NULL, NULL, 0, 0}};

== WIRE PROTOCOL ==

1) HANDSHAKE. GET http://127.0.0.1:13338/ws with header:
     Sec-WebSocket-Protocol: tty
   MANDATORY. ttyd never sets vhost->default_protocol_index (I grepped both server.c versions: no
   occurrence), so libwebsockets falls back to index 0. From lws lib/roles/ws/server-ws.c:554-571:
     if (!ts.len) { int n = wsi->a.vhost->default_protocol_index;
       /* Some clients only have one protocol and do not send the protocol list header... */
   Index 0 is "http-only" → callback_http, not callback_tty. Omit the header and you get an
   upgrade that never speaks terminal.

2) SERVER GREETS FIRST, unprompted. protocol.c:15 `static char initial_cmds[] = {SET_WINDOW_TITLE, SET_PREFERENCES};`
   sent from LWS_CALLBACK_SERVER_WRITEABLE via lws_write(..., LWS_WRITE_BINARY):
     frame: '1' + "<full command line> (<gethostname()>)"
     frame: '2' + <prefs JSON from -t options>
   Both arrive before/independently of your first message. Just handle them.

3) CLIENT'S FIRST MESSAGE — the JSON_DATA handshake. This is what SPAWNS the shell.
   The '{' IS both the command byte and the first character of the JSON. protocol.c:336 passes the
   WHOLE buffer (not buffer+1) to the parser:
     json_object *obj = parse_window_size(pss->buffer, pss->len, &columns, &rows);
   Send as a BINARY frame:
     {"AuthToken":"","columns":80,"rows":24}
   With no -c, `server->credential == NULL` and the entire AuthToken block (protocol.c:337-351) is
   skipped — `{"columns":80,"rows":24}` works equally well. With -c, AuthToken must equal the
   base64 of "user:pass", AND the HTTP upgrade must carry `Authorization: Basic <same base64>`
   (check_auth, protocol.c:192-196: `n >= 7 && strstr(buf,"Basic ") && !strcmp(buf+6, server->credential)`).
   Then: `if (!spawn_process(pss, columns, rows)) return 1;`

4) CLIENT → SERVER, all BINARY frames, byte 0 = command:
     INPUT            '0' 0x30  + raw bytes         → pty_write(process, pty_buf_init(buffer+1, len-1))
     RESIZE_TERMINAL  '1' 0x31  + {"columns":C,"rows":R}
     PAUSE            '2' 0x32  (single byte, no payload) → pty_pause()  [uv_read_stop]
     RESUME           '3' 0x33  (single byte, no payload) → pty_resume() [uv_read_start]

   THE RESIZE — protocol.c:320-325, verbatim:
     case RESIZE_TERMINAL:
       if (pss->process == NULL) break;
       json_object_put(
           parse_window_size(pss->buffer + 1, pss->len - 1, &pss->process->columns, &pss->process->rows));
       pty_resize(pss->process);
       break;
   Note `pss->buffer + 1` — the JSON starts AFTER the '1'. Keys, from protocol.c:44-45:
     if (json_object_object_get_ex(obj, "columns", &o)) *cols = (uint16_t)json_object_get_int(o);
     if (json_object_object_get_ex(obj, "rows",    &o)) *rows = (uint16_t)json_object_get_int(o);
   Exact names "columns" and "rows", case-sensitive. Parsed by key so order is irrelevant, but the
   official client emits columns-then-rows (index.ts:186): JSON.stringify({ columns: cols, rows: rows })
   On-wire bytes for 54x47:  31 7B 22 63 6F 6C 75 6D 6E 73 22 3A 35 34 2C 22 72 6F 77 73 22 3A 34 37 7D
   i.e. the literal ASCII:   1{"columns":54,"rows":47}

5) SERVER → CLIENT, all BINARY frames (LWS_WRITE_BINARY), byte 0 = command:
     OUTPUT            '0' 0x30 + RAW pty bytes — NO base64, NO encoding
     SET_WINDOW_TITLE  '1' 0x31 + UTF-8 title string
     SET_PREFERENCES   '2' 0x32 + UTF-8 JSON preferences
   protocol.c:176-180, verbatim:
     *ptr = OUTPUT;
     memcpy(ptr + 1, buf->base, buf->len);
     size_t n = buf->len + 1;
     if (lws_write(wsi, (unsigned char *)ptr, n, LWS_WRITE_BINARY) < n) {

== KOTLIN / OkHttp ==

OkHttp permits a manual Sec-WebSocket-Protocol request header. RealWebSocket.kt:148 rejects only
Sec-WebSocket-Extensions ("Request header not permitted"). checkUpgradeSuccess() (RealWebSocket.kt:232-262)
validates code==101, Connection: Upgrade, Upgrade: websocket, and Sec-WebSocket-Accept — it does NOT
validate the negotiated subprotocol, so nothing fights you here.

  private val client = OkHttpClient.Builder()
      .readTimeout(0, TimeUnit.MILLISECONDS)   // long-lived stream
      .pingInterval(0, TimeUnit.SECONDS)       // ttyd/lws pings us (-P, default 5s)
      .build()

  private val req = Request.Builder()
      .url("http://127.0.0.1:13338/ws")
      .header("Sec-WebSocket-Protocol", "tty")   // MANDATORY
      // .header("Authorization", "Basic " + b64)   // only if ttyd -c
      // .header("Origin", "http://127.0.0.1:13338") // only if ttyd -O
      .build()

  private var ws: WebSocket? = null
  @Volatile private var ready = false            // true once handshake sent
  private var pending: Pair<Int,Int>? = null

  ws = client.newWebSocket(req, object : WebSocketListener() {
    override fun onOpen(s: WebSocket, r: Response) {
      s.send(ByteString.encodeUtf8("""{"AuthToken":"","columns":$cols,"rows":$rows}"""))
      ready = true
      pending?.let { (c, rr) -> resize(c, rr); pending = null }
    }
    override fun onMessage(s: WebSocket, bytes: ByteString) {
      when (bytes[0].toInt()) {
        0x30 -> termSession.write(bytes.substring(1).toByteArray())  // OUTPUT
        0x31 -> title = bytes.substring(1).utf8()                    // SET_WINDOW_TITLE
        0x32 -> applyPrefs(bytes.substring(1).utf8())                // SET_PREFERENCES
      }
    }
    override fun onMessage(s: WebSocket, text: String) { /* ttyd never sends text frames */ }
  })

  // THE FIX — replaces " stty rows N cols N\n" injected into stdin. No echo, no stdin write.
  fun resize(cols: Int, rows: Int) {
    if (!ready) { pending = cols to rows; return }
    ws?.send(ByteString.encodeUtf8("""1{"columns":$cols,"rows":$rows}"""))
  }

  fun input(data: ByteArray) {
    val b = Buffer().writeByte(0x30).write(data).readByteString()
    ws?.send(b)                                  // ByteString overload = BINARY frame
  }

  fun pause()  { ws?.send(ByteString.of(0x32)) }
  fun resume() { ws?.send(ByteString.of(0x33)) }

`send(ByteString)` = binary frame; `send(String)` = text frame. Always use ByteString (see pitfall 3).

## Pitfalls
- Sec-WebSocket-Protocol: tty is MANDATORY, not decorative. ttyd never sets vhost->default_protocol_index, so lws (server-ws.c:554-571) falls back to protocol index 0 = "http-only" → callback_http. You get a successful 101 upgrade that silently never speaks the terminal protocol. This will look like "connects but nothing happens" and is the single easiest way to lose an afternoon.
- Nothing spawns until you send the JSON_DATA frame. spawn_process() is called ONLY from `case JSON_DATA:` (protocol.c:332-353). If you send INPUT first, pss->process is NULL, pty_write returns UV_ESRCH (pty.c:136-139), and protocol.c:315-318 does `if (err) { ...; return -1; }` — lws tears the connection down. Send the `{...}` handshake in onOpen, before anything else.
- Use BINARY frames only. ttyd sets LWS_SERVER_OPTION_VALIDATE_UTF8 (server.c:324 in 1.7.7). A TEXT frame carrying arbitrary key bytes (ESC sequences, Alt-modified keys, any 0x80-0xFF byte, paste of binary data) fails lws's UTF-8 validation and kills the connection. In OkHttp that means send(ByteString), never send(String).
- -W/--writable is required or input is silently dropped, with no error and no log per-message: `case INPUT: if (!server->writable) break;` (protocol.c:313). ttyd is read-only by default. Symptom is a terminal that renders output perfectly but ignores every keystroke.
- SESSION LIFETIME: each WebSocket connection spawns a FRESH process, and LWS_CALLBACK_CLOSED kills it with server->sig_code (default 1/SIGHUP, protocol.c:377-383). An Android app that gets backgrounded, loses the socket, and reconnects gets a brand-new shell with the user's work gone. Run `ttyd ... tmux new -A -s foldcode` (or dtach) so reattach is real. This is the one thing most likely to bite you in production and it is not a protocol detail you can code around.
- Multiple clients are supported but NOT shared. Every connection gets its own pty and its own child process — connecting twice gives two independent shells, not a mirrored view. `-m/--max-clients N` (0 = unlimited) rejects the WS upgrade at LWS_CALLBACK_FILTER_PROTOCOL_CONNECTION once client_count == N. `-o/--once` refuses any second concurrent client AND exits the whole ttyd process when that client disconnects — so with -o, one backgrounding event ends your server, not just your session. Screen sharing requires tmux.
- RESIZE_TERMINAL sent before the handshake is silently discarded: `case RESIZE_TERMINAL: if (pss->process == NULL) break;` (protocol.c:321). Given that Android fires layout/resize callbacks early and often, queue any resize that arrives before onOpen completes and replay it after sending the JSON handshake (the `pending` field in the sketch above).
- SECURITY: with no -c, ttyd authenticates nobody. Any app on the device holding INTERNET permission can open 127.0.0.1:13338 and, with -W, get a writable shell running as Termux's UID. Your current socat setup is equally exposed so this is not a regression, but ttyd actually gives you a fix: `-c user:pass` requires BOTH an `Authorization: Basic <b64>` header on the upgrade AND `"AuthToken":"<same b64>"` in the handshake JSON (server->credential is stored base64-encoded, server.c:399-400). Generate a random per-launch secret and pass it to the app.

## Confidence
HIGH CONFIDENCE — read directly from source, quoted verbatim above:
- All eight command byte constants (server.h:7-17). I diffed tag 1.7.7 against main: server.h is byte-identical; protocol.c differs only in sprintf→snprintf and shutdown sequencing, with zero wire-protocol change.
- WS path /ws and subprotocol "tty" (server.c:23 and :29-31, identical in both).
- The JSON_DATA handshake passing the WHOLE buffer vs RESIZE_TERMINAL passing buffer+1 — this asymmetry is the detail most likely to be gotten wrong from memory, and I confirmed it on lines 336 and 323 respectively.
- "columns"/"rows" key names (protocol.c:44-45) and the client's emission order (index.ts:186).
- Binary framing with NO base64 (protocol.c:176-180, LWS_WRITE_BINARY, plain memcpy).
- -W gating input (protocol.c:313); --once/--max-clients gating at FILTER_PROTOCOL_CONNECTION (protocol.c:208-215).
- credential stored base64-encoded (server.c:399-400).
- Termux ships stock 1.7.7 rev 3 — I queried the GitHub contents API for packages/ttyd and the directory contains ONLY build.sh, so no downstream patches alter behavior.

COULD NOT VERIFY — treat these as assumptions to test on-device:
1. NOTHING WAS RUN. I did not execute ttyd, did not connect a client, did not test on an Android device or in Termux. Every claim is static source reading. Budget an hour to validate the handshake against a real ttyd before building on it.
2. `-i 127.0.0.1` depends on the numeric-literal fallback at the end of lws_interface_to_sa(), which I read in libwebsockets MAIN — not in whatever libwebsockets version Termux currently packages. The surrounding code comment references a recent buffer-overflow fix, so that block has been edited recently and I cannot confirm the fallback exists in older builds. Test `-i 127.0.0.1`; if ttyd reports "netif ...: Does not exist", fall back to `-i lo`. Also confirm Android's bionic getifaddrs() exposes "lo" on your minSdk (getifaddrs is API 24+).
3. Base64: I verified 1.7.7 and main contain NO base64 in wsi_output. I did NOT read ttyd 1.6.x or earlier, where I believe output was base64-encoded. Since TERMUX_PKG_AUTO_UPDATE=true the Termux version can move — re-check server.h and wsi_output if it bumps to a 2.x.
4. OkHttp unconditionally offers `Sec-WebSocket-Extensions: permessage-deflate` (RealWebSocket.kt:166) and ttyd/lws offers it too, so it will likely negotiate. I did not trace lws's response parameters against OkHttp's WebSocketExtensions.isValid(); a mismatch would surface as a graceful close with code 1010 and message "unexpected Sec-WebSocket-Extensions in response header". Browsers negotiate this pairing fine, so I rate the risk low, but OkHttp exposes no switch to suppress the offer if it does bite.
5. I did not verify Android's behavior for an app connecting to another UID's loopback listener. Your existing socat setup already proves this path works on your target devices, which is why I did not chase it — but note it is inherited evidence, not something I checked.

## Sources
- ttyd src/server.h — command byte #defines and struct endpoints (tag 1.7.7): https://raw.githubusercontent.com/tsl0922/ttyd/1.7.7/src/server.h
- ttyd src/protocol.c — callback_tty, JSON_DATA handshake, RESIZE_TERMINAL, wsi_output: https://raw.githubusercontent.com/tsl0922/ttyd/1.7.7/src/protocol.c
- ttyd src/server.c — protocols[] {"tty"}, endpoints {"/ws"}, CLI options, LWS_SERVER_OPTION_VALIDATE_UTF8: https://raw.githubusercontent.com/tsl0922/ttyd/1.7.7/src/server.c
- ttyd html/src/components/terminal/xterm/index.ts — Command enum, new WebSocket(url,['tty']), AuthToken, resize: https://raw.githubusercontent.com/tsl0922/ttyd/1.7.7/html/src/components/terminal/xterm/index.ts
- ttyd src/pty.c — pty_write/pty_resize/pty_pause NULL handling and UV_ESRCH: https://raw.githubusercontent.com/tsl0922/ttyd/1.7.7/src/pty.c
- termux-packages packages/ttyd/build.sh — version 1.7.7, revision 3, no patches: https://raw.githubusercontent.com/termux/termux-packages/master/packages/ttyd/build.sh
- libwebsockets lib/roles/ws/server-ws.c — subprotocol negotiation, default_protocol_index fallback: https://raw.githubusercontent.com/warmcat/libwebsockets/main/lib/roles/ws/server-ws.c
- OkHttp RealWebSocket.kt — checkUpgradeSuccess, permitted request headers: https://raw.githubusercontent.com/square/okhttp/master/okhttp/src/commonJvmAndroid/kotlin/okhttp3/internal/ws/RealWebSocket.kt

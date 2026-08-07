# alternatives

## VERDICT
Viable, and easier than you think — but the ranking is not what the question implies. Do it in two moves.

MOVE 1 (today, ~1 hour): out-of-band `stty -F` control port. Keeps your entire socat setup. Kills the echoed "stty rows 47 cols 54" lines immediately. Verified against Linux kernel source.

MOVE 2 (this sprint, ~1 day): switch the transport to SSH (openssh in Termux + sshj 0.40.0 in Kotlin), and make the remote command `tmux new-session -A -s foldcode`. This gets you: native resize (RFC 4254 window-change), authentication (fixing a real security hole in the current design), and session persistence — all three roadmap items — from one choice.

Do NOT hand-roll a dtach client (fragile framing over TCP). Do NOT write a Python bridge (you'd own a server AND a protocol for zero benefit). tmux control mode (-CC) is technically capable but is strictly more Kotlin work than SSH for less benefit.

Effort: Move 1 ≈ 25 lines total. Move 2 ≈ 60 lines Kotlin + 3 shell commands. Library risk is low: sshj 0.40.0 was released 2026-06-29 with commits through July 2026, targets `options.release = 8` (Android-safe), and sets `configurations.implementation.transitive = false` so BouncyCastle is NOT pulled in transitively — the classic Android BC conflict is already solved upstream.

## Summary
Surveyed six transports against primary sources (termux-packages build.sh files, upstream C source, RFC 4254, Linux kernel tty/pty drivers, Maven Central POMs). Every candidate you named IS packaged for Termux and builds for aarch64 (no arch blacklists): openssh 10.4p1, tmux 3.7b, dtach 0.9, abduco 0.6, gotty 1.8.0, ttyd 1.7.7, mosh 1.4.0, python 3.14.6, socat 1.8.1.3. None are in the Termux bootstrap — but neither is socat, which you already require, so "needs pkg install" is not a differentiator.

Three findings reframe the problem:

(1) You do not need a new transport to fix the echo bug. `stty -F /dev/pts/N rows R cols C` from a *third process* works: Linux `pty_resize()` (drivers/tty/pty.c) sets winsize on both sides and does `kill_pgrp(pgrp, SIGWINCH, 1)` for the foreground process groups of *both* ptys. A second socat listener on a control port is ~15 lines of shell and ~10 lines of Kotlin, and it deletes the bug today.

(2) Termux's openssh is far less setup burden than expected. Its postinst auto-generates host keys and creates `$HOME/.ssh/authorized_keys` (mode 600). Default port is hardcoded 8022 (servconf.c.patch). And auth.c.patch replaces `getpwnam(user)` with `getpwuid(getuid())` with the comment "Effectively a single-user system, use current user no matter supplied user" — so the username is ignored entirely. Setup is literally `pkg install openssh; passwd; sshd`. SSH also closes a real hole: your current port 13338 is an unauthenticated shell reachable by any app on the device holding INTERNET permission.

(3) dtach is a trap for TCP bridging even though its protocol carries winsize. master.c does `len = read(p->fd, &pkt, sizeof(struct packet)); if (len != sizeof(struct packet)) { close(p->fd); ... }` — a short read from TCP fragmentation kills the session. abduco does it correctly with read_all/write_all loops, but upstream is dead since April 2020 and neither replays screen state on reattach.

For persistence, `tmux new-session -A -s <name>` inside whatever pty you already have is one line and gives real screen-state repaint on reattach — something dtach and abduco cannot do (abduco's server.c has no replay buffer; new clients get only MSG_PID).

## Implementation
=== MOVE 1: OUT-OF-BAND RESIZE OVER A SECOND PORT (no transport change) ===

Verified mechanism, from https://raw.githubusercontent.com/torvalds/linux/master/drivers/tty/pty.c :

    static int pty_resize(struct tty_struct *tty,  struct winsize *ws)
    {
        struct pid *pgrp, *rpgrp;
        struct tty_struct *pty = tty->link;
        guard(mutex)(&tty->winsize_mutex);
        if (!memcmp(ws, &tty->winsize, sizeof(*ws)))
            return 0;
        /* Signal the foreground process group of both ptys */
        pgrp = tty_get_pgrp(tty);
        rpgrp = tty_get_pgrp(pty);
        if (pgrp)
            kill_pgrp(pgrp, SIGWINCH, 1);
        if (rpgrp != pgrp && rpgrp)
            kill_pgrp(rpgrp, SIGWINCH, 1);
        put_pid(pgrp); put_pid(rpgrp);
        tty->winsize = *ws;
        pty->winsize = *ws;
        return 0;
    }

So TIOCSWINSZ on the SLAVE (from any same-UID process) signals the shell. It is also idempotent — unchanged size returns early with no signal.

GNU coreutils stty implements `rows`/`cols` via TIOCSWINSZ (src/stty.c: `set_window_size()`), and `-F` does:
    fd_reopen (STDIN_FILENO, device_name, O_RDONLY | O_NONBLOCK, 0)

Server side. In ~/.foldcode/shell.sh, before `exec bash -li`, add:

    mkdir -p "$HOME/.foldcode"
    tty > "$HOME/.foldcode/tty"

New file ~/.foldcode/resize.sh (chmod 700):

    #!/data/data/com.termux/files/usr/bin/sh
    read -r R C || exit 0
    case "$R$C" in ''|*[!0-9\ ]*) exit 1;; esac
    P=$(cat "$HOME/.foldcode/tty" 2>/dev/null) || exit 1
    [ -c "$P" ] || exit 1
    exec stty -F "$P" rows "$R" cols "$C"

Second listener (EXEC, not SYSTEM — socat's SYSTEM forbids ',' and '!!' and re-parses shell metachars):

    socat TCP-LISTEN:13339,bind=127.0.0.1,reuseaddr,fork EXEC:"$HOME/.foldcode/resize.sh"

Kotlin side:

    suspend fun resizeRemote(rows: Int, cols: Int) = withContext(Dispatchers.IO) {
        runCatching {
            Socket().use { s ->
                s.connect(InetSocketAddress(InetAddress.getLoopbackAddress(), 13339), 1000)
                s.getOutputStream().apply { write("$rows $cols\n".toByteArray()); flush() }
            }
        }
    }

Call it from your TerminalView's onSizeChanged / posture listener. Nothing is written to the shell's stdin, so nothing echoes.

=== MOVE 2a: SSH SERVER IN TERMUX ===

Verified from packages/openssh/build.sh:
  --sysconfdir=$TERMUX_PREFIX/etc/ssh
  TERMUX_PKG_CONFFILES="etc/ssh/ssh_config etc/ssh/sshd_config"
  postinst creates etc/ssh/sshd_config.d/, generates ssh_host_${a}_key, and does
  touch "$HOME/.ssh/authorized_keys"; chmod 600 "$HOME/.ssh/authorized_keys"

Verified from packages/openssh/servconf.c.patch (default port):
  -   options->ports[options->num_ports++] = SSH_DEFAULT_PORT;
  +   options->ports[options->num_ports++] = 8022 /* SSH_DEFAULT_PORT */;

Verified from packages/openssh/auth.c.patch (username is ignored):
  +#ifdef __ANDROID__
  +    /* Effectively a single-user system, use current user no matter supplied user */
  +    pw = getpwuid(getuid());
  +#else
       pw = getpwnam(user);
  +#endif

Verified from packages/openssh/auth-passwd.c.patch:
  return termux_auth(authctxt->user, password);
termux-auth 1.5.0 hashes with PKCS5_PBKDF2_HMAC_SHA1(password, ..., salt="Termux!", 65536, SHA_DIGEST_LENGTH).

Setup (three commands):

    pkg install openssh
    passwd                       # or append your app's pubkey to ~/.ssh/authorized_keys
    printf '\nListenAddress 127.0.0.1\n' >> $PREFIX/etc/ssh/sshd_config
    sshd                         # daemonizes, port 8022

The ListenAddress line is NOT optional — packages/openssh/sshd_config.patch leaves `#ListenAddress 0.0.0.0` commented, so stock Termux sshd listens on every interface.

Key-auth bootstrap without a chicken-and-egg problem: you already have the socat channel. Use it once to run
  `mkdir -p ~/.ssh && printf '%s\n' 'ssh-ed25519 AAAA... foldcode' >> ~/.ssh/authorized_keys && chmod 600 ~/.ssh/authorized_keys`
then tear the socat listener down permanently.

=== MOVE 2b: KOTLIN CLIENT (sshj 0.40.0) ===

Wire format being used, RFC 4254 §6.7 (verbatim):

      byte      SSH_MSG_CHANNEL_REQUEST
      uint32    recipient channel
      string    "window-change"
      boolean   FALSE
      uint32    terminal width, columns
      uint32    terminal height, rows
      uint32    terminal width, pixels
      uint32    terminal height, pixels

  "A response SHOULD NOT be sent to this message."

And §6.2 pty-req: same shape plus `string TERM` before the dimensions and `string encoded terminal modes` after.

sshj API, verified verbatim from src/main/java/net/schmizz/sshj/connection/channel/direct/Session.java:

  On `Session`:
    void allocateDefaultPTY() throws ConnectionException, TransportException;
    void allocatePTY(String term, int cols, int rows, int width, int height, Map<PTYMode,Integer> modes)
            throws ConnectionException, TransportException;

  On the NESTED `Session.Shell` interface (this is the gotcha — resize is NOT on Session):
    /** Sends a window dimension change message. */
    void changeWindowDimensions(int cols, int rows, int width, int height) throws TransportException;

Kotlin:

    val ssh = SSHClient().apply {
        addHostKeyVerifier(myPinnedVerifier)      // TOFU: persist fingerprint on first connect
        connect("127.0.0.1", 8022)
    }
    ssh.authPublickey("termux", keyProvider)      // username is ignored by Termux sshd
    val session = ssh.startSession()
    session.allocatePTY("xterm-256color", cols, rows, 0, 0, emptyMap())
    val shell: Session.Shell = session.startShell()

    // wire to Termux TerminalEmulator
    val toShell   = shell.outputStream            // keystrokes
    val fromShell = shell.inputStream             // feed emulator

    // the whole point:
    fun onPaneResized(cols: Int, rows: Int) =
        shell.changeWindowDimensions(cols, rows, 0, 0)   // NOTE: cols BEFORE rows

Gradle (BC is not transitive in 0.40.0, add only if you need algorithms Android's Conscrypt lacks):

    implementation("com.hierynomus:sshj:0.40.0")
    implementation("org.slf4j:slf4j-api:2.0.17")
    // optional: implementation("org.bouncycastle:bcprov-jdk18on:1.80")

=== MOVE 2c: PERSISTENCE, ONE LINE ===

Make the SSH remote command (or shell.sh's final exec):

    export TERM=xterm-256color
    exec tmux new-session -A -s foldcode

Verified from cmd-new-session.c: `.args = { "Ac:dDe:EF:f:n:Ps:t:x:Xy:", 0, -1, NULL }` — `-A` "makes new-session behave like attach-session if session-name already exists". Verified from client.c: `case SIGWINCH: proc_send(client_peer, MSG_RESIZE, -1, NULL, 0);` — the tmux client forwards pty resizes to the server, so both Move 1 and SSH window-change reach tmux correctly. On app restart the new client reattaches and tmux repaints from its own screen state.

=== REJECTED OPTIONS, WITH THE EXACT REASON ===

dtach 0.9 (packages/dtach/build.sh, upstream active — commits June 2025). Protocol DOES carry resize. dtach.h:
    MSG_PUSH=0, MSG_ATTACH=1, MSG_DETACH=2, MSG_WINCH=3, MSG_REDRAW=4
    REDRAW_UNSPEC=0, REDRAW_NONE=1, REDRAW_CTRL_L=2, REDRAW_WINCH=3
    struct packet { unsigned char type; unsigned char len;
                    union { unsigned char buf[sizeof(struct winsize)]; struct winsize ws; } u; };
Socket is PF_UNIX/SOCK_STREAM, so you'd bridge with `socat TCP-LISTEN:...,fork UNIX-CONNECT:$HOME/.foldcode/sess.sock`. Master applies `ioctl(the_pty.fd, TIOCSWINSZ, &the_pty.ws)` on MSG_WINCH and MSG_REDRAW; master→client is undelimited raw bytes. Killer, from master.c client_activity():
    len = read(p->fd, &pkt, sizeof(struct packet));
    if (len < 0 && (errno == EAGAIN || errno == EINTR)) return;
    /* Close the client on an error. */
    if (len != sizeof(struct packet)) { close(p->fd); ... return; }
No accumulation loop. A TCP-fragmented packet arriving through socat closes the session. Also MSG_PUSH payload is capped at sizeof(struct winsize) = 8 bytes/packet.

abduco 0.6 (packaged; upstream last commit 2020-04-30, v0.6 is the final tag). Correct framing, unlike dtach:
    typedef struct { uint32_t type; uint32_t len;
                     union { char msg[4096 - 2*sizeof(uint32_t)];
                             struct { uint16_t rows; uint16_t cols; } ws;
                             uint32_t i; uint64_t l; } u; } Packet;
    enum PacketType { MSG_CONTENT=0, MSG_ATTACH=1, MSG_DETACH=2, MSG_RESIZE=3, MSG_EXIT=4, MSG_PID=5 };
    packet_header_size() = offsetof(Packet, u)   // 8 bytes, LE on aarch64
    recv_packet(): read_all(hdr, 8) then read_all(payload, pkt->len); read_all/write_all are real retry loops.
Resize = type 3, len 4, payload {uint16 rows, uint16 cols} — rows FIRST. Server does ioctl(server.pty, TIOCSWINSZ, &ws) and broadcasts SIGWINCH. Both directions are framed (server wraps pty output in MSG_CONTENT). This would genuinely work over a socat UNIX-CONNECT bridge in ~80 lines of Kotlin. Rejected only because it is dead upstream, has no screen replay on reattach, and SSH gives you strictly more.

ttyd 1.7.7 (packaged; upstream commits March 2026, last release 2024-03-30). src/server.h:
    #define INPUT '0'   #define RESIZE_TERMINAL '1'   #define PAUSE '2'   #define RESUME '3'   #define JSON_DATA '{'
    #define OUTPUT '0'  #define SET_WINDOW_TITLE '1'  #define SET_PREFERENCES '2'
Binary WebSocket, first byte = command. Resize is '1' + JSON `{"columns":N,"rows":N}`; initial `{` frame carries `{"AuthToken":"...","columns":N,"rows":N}`. Clean, but needs OkHttp WS + an HTTP layer, and gives no persistence.

gotty 1.8.0 (sorenisanerd fork, packaged, upstream commits May 2026). webtty/message_types.go: Input='1', Ping='2', ResizeTerminal='3', SetEncoding='4'; Output='1', Pong='2', SetWindowTitle='3', SetPreferences='4', SetReconnect='5', SetBufferSize='6'. Note the Termux build.sh still symlinks the source into $GOPATH/src/github.com/yudai/gotty. Functionally equivalent to ttyd with a heavier payload encoding; no reason to prefer it.

tmux -CC control mode. Carries resize: `refresh-client -C size` where "size must be one of 'widthxheight' or 'window ID:widthxheight', for example '80x24' or '@0:80x24'". Command results are bracketed by %begin/%end (or %begin/%error) each with epoch time, command number, flags. But you must un-escape pane output — control.c:
    evbuffer_add_printf(message, "%%output %%%u ", wp->id);
    if (new_data[i] < ' ' || new_data[i] == '\\')
        evbuffer_add_printf(message, "\\%03o", new_data[i]);
i.e. every byte < 0x20 and every backslash arrives as a 3-digit octal escape. Plus you'd need `capture-pane -p -e` to rebuild the screen on attach, and you STILL need an authenticated transport underneath. More Kotlin than SSH, less benefit.

Python bridge. python 3.14.6 is packaged but is NOT in the bootstrap — build-bootstraps.sh ships apt, bash, bzip2, command-not-found, coreutils, dash, diffutils, findutils, gawk, grep, gzip, less, procps, psmisc, sed, tar, termux-core, termux-exec, termux-keyring, termux-tools, util-linux, ed, debianutils, dos2unix, inetutils, lsof, nano, net-tools, patch, unzip. Neither socat nor openssh is in there either, so the install burden is identical — but you'd be maintaining a server and a protocol you invented, with no auth story.

mosh 1.4.0 is packaged and SSP carries window size natively, but there is no Java/Kotlin mosh client. Not viable.

## Pitfalls
- Termux sshd listens on ALL interfaces by default. packages/openssh/sshd_config.patch leaves `#ListenAddress 0.0.0.0` commented, and the port is hardcoded to 8022 in servconf.c.patch. You MUST add `ListenAddress 127.0.0.1` or you expose a shell to the whole LAN. Your existing socat line already gets this right with bind=127.0.0.1 — don't regress.
- Your current port 13338 has NO authentication. Any app on the device with INTERNET permission can connect and get a shell in Termux's UID. This is a live vulnerability, not a theoretical one, and it is the strongest argument for SSH over any of the framed-protocol options (dtach/abduco/ttyd all inherit the same hole unless you add a token).
- sshj's `changeWindowDimensions` is on the nested `Session.Shell` interface, NOT on `Session`. And the parameter order is (cols, rows, width, height) — columns before rows, matching RFC 4254. Getting this backwards silently produces a transposed terminal.
- dtach's master closes the connection on any read that isn't exactly sizeof(struct packet): `if (len != sizeof(struct packet)) { close(p->fd); ... }`. Bridged over TCP through socat this will drop sessions under burst input (large pastes). I did not compile-verify sizeof(struct packet); by C layout rules it should be 10 bytes (uchar, uchar, then a 2-aligned 8-byte union) but confirm before relying on it.
- `stty -F DEV rows R cols C` issues TWO separate TIOCSWINSZ ioctls (coreutils src/stty.c calls set_window_size() once per keyword), so the pty briefly holds (new rows, old cols) and the shell gets two SIGWINCHes. Harmless for bash/tmux but can cause a visible double-repaint. A 10-line C or Python helper doing one ioctl avoids it.
- GNU stty's `-F` opens the device WITHOUT O_NOCTTY: `fd_reopen (STDIN_FILENO, device_name, O_RDONLY | O_NONBLOCK, 0)`. Do not run the resize helper under `setsid` — a session leader with no controlling terminal could otherwise interact badly with ctty acquisition. Plain `socat ... EXEC:resize.sh` (no setsid option) is fine.
- With socat's `fork` option every connection gets its OWN pty, so a single `$HOME/.foldcode/tty` file is last-writer-wins. Fine for a single-session app; if you ever allow two panes you need a per-connection token in the path, and the resize port needs to carry that token.
- abduco's resize payload is `{uint16 rows; uint16 cols}` — ROWS FIRST — while dtach, ttyd, gotty and SSH all order it columns/width first. If you prototype more than one of these, this asymmetry will bite you.

## Confidence
HIGH confidence (read directly from primary source this session): all termux-packages build.sh contents and versions; the openssh patches (port 8022, getpwuid, termux_auth, sshd_config ListenAddress); Linux pty_resize()/tty_do_resize() SIGWINCH behavior; coreutils stty open flags and set_window_size; RFC 4254 §6.2/§6.7 field order; dtach's dtach.h constants, packet struct, PF_UNIX socket, and the fatal `len != sizeof(struct packet)` close; abduco's Packet struct, PacketType enum, read_all/write_all loops, AF_UNIX socket, TIOCSWINSZ handling, and absence of a replay buffer; ttyd's INPUT/RESIZE_TERMINAL/OUTPUT command bytes from src/server.h and the RESIZE_TERMINAL JSON parse in src/protocol.c; gotty's message_types.go constants; tmux control.c %output `\\%03o` escaping, client.c SIGWINCH handling, cmd-new-session.c -A flag, refresh-client -C syntax; sshj v0.40.0 build.gradle (release=8, non-transitive deps, BC optional in OSGi manifest); sshj Session/Session.Shell signatures; the Termux bootstrap package list.

MAINTENANCE DATES via authenticated GitHub API (reliable): sshj v0.40.0 published 2026-06-29 with commits through 2026-07-10; dtach upstream commits 2025-06-21; abduco last commit 2020-04-30 (v0.6 final tag); gotty/sorenisanerd commits 2026-05-24; ttyd commits 2026-03-20, last release 1.7.7 (2024-03-30); connectbot/sshlib (now github.com/connectbot/cbssh) commits 2026-08-05/06, Maven Central org.connectbot.sshlib:sshlib 0.4.2 lastUpdated 20260806061115.

COULD NOT VERIFY — treat as open items:
- sizeof(struct packet) in dtach = 10. Reasoned from C struct layout, not compiled.
- Whether sshj 0.40.0 actually negotiates successfully against Termux openssh 10.4p1 on Android WITHOUT BouncyCastle on the classpath. The non-transitive dependency config and `org.bouncycastle*;resolution:=optional` OSGi instruction strongly imply it is supported, but which kex/host-key algorithms survive on Conscrypt alone is untested. Budget an afternoon; if negotiation fails, add bcprov-jdk18on:1.80 explicitly (the old jdk15to18 advice is stale).
- Whether Termux's `Include /etc/ssh/sshd_config.d/*.conf` resolves under $PREFIX. build.sh creates `etc/ssh/sshd_config.d` and passes `--sysconfdir=$TERMUX_PREFIX/etc/ssh`, which strongly implies yes, but I did not read OpenSSH's Include path-resolution code. Appending directly to $PREFIX/etc/ssh/sshd_config sidesteps the question.
- SELinux behavior for `stty -F /dev/pts/N` on the user's specific Android build. Same UID, same app's devpts, so it should be permitted, but untested on device.
- Android's INTERNET permission requirement for loopback TCP is from background knowledge, not verified this session — moot since the current app already works.
- connectbot/cbssh 0.4.2 is a genuinely interesting alternative (idiomatic Kotlin coroutines, `suspend fun requestPty(terminalType, widthChars, heightRows, widthPixels, heightPixels, terminalModes)` and `suspend fun resizeTerminal(widthChars, heightRows, widthPixels, heightPixels)`, no BouncyCastle — uses Google Tink + kyber-jvm + ktor-network). By the same maintainer as ConnectBot, so Android is clearly the target. But it is pre-1.0 at 0.4.2 with ten releases and a publish yesterday, so expect API churn. I did not verify it runs on Android. Worth a spike, not worth betting the ship date on.

METHOD LIMITATION: WebSearch quota was exhausted at the start of this session, so there is no community/blog sentiment input here — every claim above comes from source repositories, man pages, RFCs, the GitHub API, or Maven Central. For protocol details that is the better input anyway, but it does mean I cannot report on any 2026 discussion of, say, sshj-on-Android gotchas that never made it into the source tree.

## Sources
- termux-packages: packages/openssh/build.sh (10.4p1, sysconfdir, postinst host keys + authorized_keys): https://github.com/termux/termux-packages/blob/master/packages/openssh/build.sh
- termux-packages: packages/openssh/servconf.c.patch (default port hardcoded to 8022) and auth.c.patch (getpwuid(getuid()) — username ignored): https://github.com/termux/termux-packages/tree/master/packages/openssh
- Linux kernel drivers/tty/pty.c — pty_resize() sets winsize on both sides and kill_pgrp(SIGWINCH) to both foreground process groups: https://raw.githubusercontent.com/torvalds/linux/master/drivers/tty/pty.c
- RFC 4254 §6.7 Window Dimension Change Message and §6.2 pty-req: https://www.rfc-editor.org/rfc/rfc4254.html
- sshj Session.java — allocatePTY / nested Shell.changeWindowDimensions(cols, rows, width, height); build.gradle at v0.40.0 shows options.release = 8 and implementation.transitive = false: https://github.com/hierynomus/sshj/blob/master/src/main/java/net/schmizz/sshj/connection/channel/direct/Session.java
- dtach dtach.h (MSG_* / REDRAW_* constants, struct packet) and master.c (fixed-size read, closes on short read): https://github.com/crigler/dtach
- abduco abduco.c (Packet struct, PacketType enum, packet_header_size, read_all/write_all) and server.c (TIOCSWINSZ on MSG_RESIZE, no replay buffer): https://github.com/martanne/abduco
- tmux control.c (%output octal escaping), client.c (SIGWINCH -> MSG_RESIZE), cmd-new-session.c (-A flag), and refresh-client -C size in tmux(1): https://man.openbsd.org/tmux#refresh-client

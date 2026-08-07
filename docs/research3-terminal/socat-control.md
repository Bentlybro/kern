# socat-control

## VERDICT
VIABLE — ship it, but ship the EXEC variant, not the FIFO variant.

The core mechanism is sound and every kernel/userspace assumption behind it checks out at the source level. `stty rows R cols C` on a pts fd held by a background process does resize the pty and does deliver SIGWINCH to bash/vim. That is verified, not inferred.

The FIFO control channel as literally proposed will ship, but only after you fix three things that will otherwise bite you: (1) you MUST add `-u`, or socat opens the FIFO O_RDWR and consumes its own writes — your stated risk is confirmed correct; (2) the background reader must not hold the pty fd, or sessions never terminate; (3) a fixed control port collides across the `fork`ed sessions.

Difficulty: ~1-2 hours if you take the EXEC design. The FIFO design is maybe 3-4 hours because of the reader-loop lifetime management, and it buys you nothing — socat's `fork` already spawns a process per resize event either way, so the long-lived reader loop is pure added liability.

Recommended: drop the FIFO. Use a second socat with `EXEC:` and address the pty by path (`stty -F /dev/pts/N`) rather than by inherited fd 9. That removes the FIFO, the EOF loop, the busy-spin question, the reopen question, and the fd-9-holds-the-pty-open session leak, all at once.

## Summary
Verified against socat 1.8.0.3 source (downloaded tarball), the socat man page, Linux drivers/tty/{tty_io.c,pty.c}, GNU coreutils src/stty.c, bash builtins/read.def, fifo(7), AOSP first_stage_init.cpp, fs/devpts/inode.c, and Termux's TermuxConstants.java + RUN_COMMAND wiki.

Headline results:

1. `stty rows R cols C <&9` from a background process group WORKS. GNU stty's rows/cols path never calls tcsetattr (it only does TIOCGWINSZ+TIOCSWINSZ), and the kernel never calls tty_check_change() for TIOCSWINSZ — so there is no SIGTTOU and no foreground-pgrp requirement. The foreground process (bash/vim) DOES get SIGWINCH, because TIOCSWINSZ on the pts slave falls through to tty_do_resize(), which does kill_pgrp(tty_get_pgrp(tty), SIGWINCH, 1) on the slave's foreground pgrp.

2. The PIPE: echo hazard is REAL but only in bidirectional mode. socat opens the FIFO with exactly the access mode implied by direction (`rw = xioflags & XIO_ACCMODE`, and XIO_RDWR==O_RDWR). Default bidirectional => open(fifo, O_RDWR) => man page: "When a pipe is used for both reading and writing, it works as echo service" => socat eats its own bytes. Adding `-u` makes address2 XIO_WRONLY => open(fifo, O_WRONLY) => correct one-way writer. PIPE: has a second trap (unlink-on-close when socat created the FIFO); GOPEN: does not. Use `-u ... GOPEN:`.

3. socat has ZERO window-size support. Grepping the whole 1.8.0.3 tree, `winsize` appears only in the Openpty() wrapper signature, and both call sites pass NULL for it. No TIOCSWINSZ, no SIGWINCH anywhere in socat. There is no socat-native option for this — confirmed, not assumed.

4. The correct FIFO reader idiom is `exec 8<>"$FIFO"` (Linux-specific O_RDWR-on-FIFO, explicitly documented in fifo(7)), which simultaneously kills the EOF-loop bug and the busy-spin. bash's `read` does 1-byte reads on a pipe/FIFO so it cannot over-read past the newline.

5. But the FIFO is unnecessary: `socat -u TCP-LISTEN:P,fork EXEC:/path/resize.sh` is simpler, spawns the same number of processes, and has no EOF/reopen/liveness problems. I verified EXEC is legal as address2 under `-u` (mayexec is gated on XIO_DOESCONVERT, not on unidirectional mode).

6. RUN_COMMAND works technically but is the wrong tool for per-resize events (startService round trip, requires user to set allow-external-apps=true, Android 10+ background-start restrictions).

7. I found two bugs in the proposed design that the question didn't ask about: (a) any helper forked from shell.sh inherits fd 0 = the pts slave and will prevent socat's master from ever seeing EOF, so the session never tears down; (b) the main socat uses `fork`, so N concurrent shells contend for one fixed control port.

## Implementation
========================================================
PART 1 — VERIFIED KERNEL / USERSPACE FACTS
========================================================

--- 1a. Does `stty rows R cols C <&9` work from a background pgrp? YES ---

GNU coreutils src/stty.c, set_window_size() (quoted verbatim, full function):

    static void
    set_window_size (int rows, int cols, char const *device_name)
    {
      struct winsize win;

      if (get_win_size (STDIN_FILENO, &win))
        {
          if (errno != EINVAL)
            error (EXIT_FAILURE, errno, "%s", quotef (device_name));
          memset (&win, 0, sizeof (win));
        }

      if (rows >= 0)
        win.ws_row = rows;
      if (cols >= 0)
        win.ws_col = cols;

    # ifdef TIOCSSIZE
      ... (SunOS 4.x workaround, not compiled on Linux) ...
    # endif

      if (ioctl (STDIN_FILENO, TIOCSWINSZ, (char *) &win))
        error (EXIT_FAILURE, errno, "%s", quotef (device_name));
    }

    static int
    get_win_size (int fd, struct winsize *win)
    {
      int err = ioctl (fd, TIOCGWINSZ, (char *) win);
      return err;
    }

Two things follow:
  * stty always operates on STDIN_FILENO. `<&9` is therefore the correct way to
    target fd 9. (`-F dev` also works — it does
    `fd_reopen (STDIN_FILENO, device_name, O_RDONLY | O_NONBLOCK, 0)`, i.e. it
    reopens the device ONTO fd 0.)
  * `require_set_attr` is NOT set by the rows/cols branches. The only tcsetattr
    call is guarded:
        if (require_set_attr)
          { if (tcsetattr (STDIN_FILENO, tcsetattr_options, &mode)) ... }
    => `stty rows R cols C` issues NO tcsetattr => NO SIGTTOU path at all.

Kernel, drivers/tty/tty_io.c — tty_ioctl()'s switch calls tty_check_change()
only for:

    switch (cmd) {
    case TIOCSETD:
    case TIOCSBRK:
    case TIOCCBRK:
    case TCSBRK:
    case TCSBRKP:
            retval = tty_check_change(tty);

TIOCSWINSZ is NOT in that set. There is no background-process-group check, no
SIGTTOU, and no permission constraint on TIOCSWINSZ beyond holding the fd.

--- 1b. Does the foreground process get SIGWINCH? YES ---

tty_io.c:
    static struct tty_struct *tty_pair_get_tty(struct tty_struct *tty)
    {
            if (tty->driver->type == TTY_DRIVER_TYPE_PTY &&
                tty->driver->subtype == PTY_TYPE_MASTER)
                    tty = tty->link;
            return tty;
    }
    ...
    real_tty = tty_pair_get_tty(tty);
    ...
    case TIOCGWINSZ:
            return tiocgwinsz(real_tty, p);
    case TIOCSWINSZ:
            return tiocswinsz(real_tty, p);

    static int tiocswinsz(struct tty_struct *tty, struct winsize __user *arg)
    {
            struct winsize tmp_ws;
            if (copy_from_user(&tmp_ws, arg, sizeof(*arg)))
                    return -EFAULT;
            if (tty->ops->resize)
                    return tty->ops->resize(tty, &tmp_ws);
            else
                    return tty_do_resize(tty, &tmp_ws);
    }

    int tty_do_resize(struct tty_struct *tty, struct winsize *ws)
    {
            struct pid *pgrp;
            guard(mutex)(&tty->winsize_mutex);
            if (!memcmp(ws, &tty->winsize, sizeof(*ws)))
                    return 0;
            pgrp = tty_get_pgrp(tty);
            if (pgrp)
                    kill_pgrp(pgrp, SIGWINCH, 1);
            put_pid(pgrp);
            tty->winsize = *ws;
            return 0;
    }

drivers/tty/pty.c — the SLAVE ops struct has NO .resize member:

    static const struct tty_operations pty_unix98_ops = {
            .lookup = pts_unix98_lookup,
            .install = pty_unix98_install,
            .remove = pty_unix98_remove,
            .open = pty_open,
            .close = pty_close,
            .write = pty_write,
            .write_room = pty_write_room,
            .flush_buffer = pty_flush_buffer,
            .unthrottle = pty_unthrottle,
            .set_termios = pty_set_termios,
            .start = pty_start,
            .stop = pty_stop,
            .cleanup = pty_cleanup,
    };                      /* <-- no .resize */

(the MASTER's ptm_unix98_ops does have `.resize = pty_resize`, but real_tty
maps master->slave first, so the slave path is what actually executes.)

Net effect for your case: fd 9 is the pts slave. TIOCSWINSZ on it ->
real_tty == slave -> ops->resize == NULL -> tty_do_resize(slave) ->
kill_pgrp(tty->ctrl.pgrp, SIGWINCH, 1). tty->ctrl.pgrp of the slave IS the
foreground process group, i.e. bash's pgrp, or vim's pgrp while vim runs.
The `1` argument is `priv` (from-kernel), so no signal permission check.

TWO IMPORTANT CAVEATS, both source-verified:

  (i)  `if (!memcmp(ws, &tty->winsize, sizeof(*ws))) return 0;`
       Setting the SAME size sends no SIGWINCH at all. Resizes are idempotent
       and free — you can spam them safely.

  (ii) `stty rows R cols C` is TWO calls to set_window_size() — main() has
       separate `else if (streq (arg, "rows"))` and
       `else if (streq (arg, "cols") || streq (arg, "columns"))` branches, each
       calling set_window_size(R,-1,...) / set_window_size(-1,C,...).
       So you get TWO TIOCGWINSZ+TIOCSWINSZ pairs and up to TWO SIGWINCH,
       with a transient (newRows, oldCols) state in between. vim will redraw
       twice on a fold. If that flicker matters, compile a 20-line C helper
       that does one TIOCSWINSZ; otherwise accept it.

  (iii) ws_xpixel/ws_ypixel: stty preserves whatever TIOCGWINSZ returned.
        socat creates the pty with `Openpty(&ptyfd, &ttyfd, ptyname, NULL, NULL)`
        (xio-progcall.c:267, xio-pty.c:110) — winsize arg is NULL — so pixel
        dims start at 0 and stay 0. Anything using sixel/kitty graphics will
        see a 0x0 pixel cell.

========================================================
PART 2 — socat FIFO ADDRESS SEMANTICS (your stated risk: CONFIRMED)
========================================================

--- 2a. PIPE: DOES open O_RDWR and DOES echo, in default mode ---

socat man page, PIPE:<filename>:

    "If <filename> already exists, it is opened.
     If it does not exist, a named pipe is created and opened. Beginning with
     socat version 1.4.3, the named pipe is removed when the address is closed
     (but see option unlink-close)
     Note: When a pipe is used for both reading and writing, it works
     as echo service."

Source, socat-1.8.0.3/xio-pipe.c, xioopen_fifo():

    int rw = (xioflags & XIO_ACCMODE);
    ...
    if ((result = _xioopen_open(pipename, rw, opts)) < 0) { return result; }

socat-1.8.0.3/xio-named.c:159, _xioopen_open():

    int _xioopen_open(const char *path, int rw, struct opt *opts) {
       mode_t mode = 0666;
       flags_t flags = rw;
       ...
       do { fd = Open(path, flags, mode); } while (fd < 0 && errno == EINTR);

socat-1.8.0.3/xio.h:26-29:

    #define XIO_RDONLY  O_RDONLY /* asserted to be 0 */
    #define XIO_WRONLY  O_WRONLY /* asserted to be 1 */
    #define XIO_RDWR    O_RDWR   /* asserted to be 2 */
    #define XIO_ACCMODE (XIO_RDONLY|XIO_WRONLY|XIO_RDWR)   /* must be 3 */

socat-1.8.0.3/socat.c:764-820:

    if (socat_opts.lefttoright) {            /* this is -u */
       sock1 = xioopen(address1, XIO_RDONLY|XIO_MAYFORK|...);
    ...
    mayexec = (sock1->common.flags&XIO_DOESCONVERT ? 0 : XIO_MAYEXEC);
    if (XIO_WRITABLE(sock1)) {
       if (XIO_READABLE(sock1)) {
          sock2 = xioopen(address2, XIO_RDWR|XIO_MAYFORK|XIO_MAYCHILD|mayexec|...);
       } else {
          sock2 = xioopen(address2, XIO_RDONLY|...);
       }
    } else {   /* assuming sock1 is readable  -- this is the -u path */
       sock2 = xioopen(address2, XIO_WRONLY|XIO_MAYFORK|XIO_MAYCHILD|mayexec|...);
    }

=> WITHOUT -u:  address2 gets XIO_RDWR   => open(fifo, O_RDWR)   => ECHO. BROKEN.
=> WITH    -u:  address2 gets XIO_WRONLY => open(fifo, O_WRONLY) => CORRECT.

Your suspicion is exactly right. `socat TCP-LISTEN:P,fork PIPE:/path` is a bug.

--- 2b. PIPE: also unlinks your FIFO (sometimes) ---

xio-pipe.c declares `bool opt_unlink_close = true;` and the man page says:
"For named pipes, UNIX domain sockets, and the symbolic links of pty addresses,
the default is remove (1); for created files, opened files, and generic opened
files the default is keep (0)."

Reading the code, the registration is nested INSIDE the "didn't exist" branch:

    if (Stat64(pipename, &pipstat) < 0) {
       if (errno != ENOENT) { Error3(...); }
       else { ... Mkfifo(pipename, mode); ... }
       if (opt_unlink_close) {
          if ((sfd->unlink_close = strdup(pipename)) == NULL) {...}
          sfd->opt_unlink_close = true;
       }
    } else {
       /* exists */
       Info1("xioopen_fifo(\"%s\"): already exist, opening it", pipename);
       ...          /* <-- no unlink_close registration here */
    }

So a PRE-EXISTING fifo is not unlinked — but if socat wins the startup race and
creates the fifo itself, it deletes it on close, and with `fork` that is on
every single connection close. Pass `unlink-close=0` explicitly if you use PIPE:.

--- 2c. GOPEN: is the clean choice ---

xio-gopen.c:

    flags_t openflags = (xioflags & XIO_ACCMODE);
    bool opt_unlink_close = false;          /* <-- default KEEP */
    ...
    if (exists) {
       if ((xioflags&XIO_ACCMODE) != XIO_RDONLY) { openflags |= O_APPEND; }
    } else {
       openflags |= O_CREAT;
    }
    ...
    if ((result = _xioopen_open(filename, openflags, opts)) < 0) return result;

With -u on an existing FIFO: open(fifo, O_WRONLY|O_APPEND). O_APPEND is a no-op
on a FIFO. Default unlink-close is false. No echo. This is the right address.

socat's OWN test suite uses exactly this shape (socat-1.8.0.3/test.sh):
    line 13550: CMD0="$TRACE $SOCAT $opts -u UNIX-LISTEN:$uns,unlink-close=0 GOPEN:$tf"
    line 13508: CMD0="$TRACE $SOCAT $opts -u UNIX-RECV:$uns,unlink-close=0 GOPEN:$tf"
    line 12011: CMD0="$TRACE $SOCAT $opts -u TCP4-LISTEN:$PORT,$REUSEADDR,range=127.0.0.1/0 CREATE:$tf"

--- 2d. CREATE: works but is worse ---

Man page: "Opens <filename> with creat() and uses the file descriptor for
writing. This is a write-only address ... If <filename> is a named pipe,
creat() might block; if <filename> refers to a socket, this is an error."
creat() == open(O_WRONLY|O_CREAT|O_TRUNC). Fine on a FIFO but pointless flags,
and it REQUIRES -u since it can't be read. GOPEN is strictly better.

--- 2e. socat has NO window-size mechanism, at all ---

Grep of the entire socat-1.8.0.3 source for TIOCSWINSZ|winsize|SIGWINCH|ws_row|ws_col:

    sycls.c:1643:      struct winsize *winp) {
    sycls.h:156:  struct winsize;   /* avoid warnings */
    sycls.h:158:      struct winsize *winp);

...and both call sites pass NULL:

    xio-progcall.c:267:  if ((result = Openpty(&ptyfd, &ttyfd, ptyname, NULL, NULL)) < 0) {
    xio-pty.c:110:       if ((result = Openpty(&ptyfd, &ttyfd, ptyname, NULL, NULL)) < 0) {

Grep of the man page for winsize/SIGWINCH/"window size": only two hits, both
about TCP socket window size (SO_RCVBUF docs). CONFIRMED: there is no
socat-native resize propagation. You have to build the side channel yourself.

========================================================
PART 3 — FIFO SEMANTICS (all from fifo(7) + bash source)
========================================================

fifo(7), quoted:
  * "The FIFO must be opened on both ends (reading and writing) before data can
     be passed. Normally, opening the FIFO blocks until the other end is opened
     also."
  * "opening for read-only succeeds even if no one has opened on the write side
     yet and opening for write-only fails with ENXIO unless the other end has
     already been opened."  (this is the O_NONBLOCK behaviour)
  * "Under Linux, opening a FIFO for read and write will succeed both in
     blocking and nonblocking mode. POSIX leaves this behavior undefined."
  * "When a process tries to write to a FIFO that is not opened for read on the
     other side, the process is sent a SIGPIPE signal."

Consequences for your reader loop:

  * `while read -r r c; do ...; done < "$FIFO"`  -- BROKEN. Exits at the first
    writer close (classic EOF-loop bug).
  * `while read -r r c < "$FIFO"; do ...; done`  -- does NOT busy-spin (the
    open() blocks), but LOSES DATA: bytes the writer sent after the first line
    are discarded when the fd is closed at the end of each iteration.
  * `exec 8<>"$FIFO"; while read -r r c <&8; do ...; done`  -- CORRECT.
    The `<>` open is O_RDWR, which fifo(7) explicitly documents as working on
    Linux and never blocking. Because the reader itself holds a write end, the
    FIFO never reaches EOF, so one blocking read loop suffices: no reopen, no
    EOF bug, no busy-spin. Android is Linux, so this is safe here.

Partial-line reads: bash's read builtin does 1-byte reads on a pipe/FIFO, so it
cannot over-read past the newline and swallow the next message.
bash builtins/read.def:

    input_is_tty = isatty (fd);
    if (input_is_tty == 0)
        input_is_pipe = fd_ispipe (fd);
    ...
    if ((nchars > 0) && (input_is_tty == 0) && ignore_delim)
      unbuffered_read = 2;
    else if (((nchars > 0 || delim != '\n') && input_is_tty) || input_is_pipe)
      unbuffered_read = 1;
    ...
    else if (unbuffered_read)
      retval = posixly_correct ? zreadintr (fd, &c, 1) : zread (fd, &c, 1);

Multiple sequential writers: fine. Concurrent writers: each write() under
PIPE_BUF (4096) is atomic, and "47 54\n" is 6 bytes, so lines never interleave.

========================================================
PART 4 — THE EXACT COMMANDS I RECOMMEND
========================================================

### PROTOCOL CHANGE (app side)

Extend the handshake so the app tells the shell which control port to listen on.
This is required because the main socat uses `fork` — a single hardcoded control
port cannot serve N concurrent sessions.

  App: ServerSocket s = new ServerSocket(0, 0, InetAddress.getByName("127.0.0.1"));
       int ctlPort = s.getLocalPort(); s.close();      // small TOCTOU race, acceptable
  App -> shell, first line on port 13338:
       "SIZE <rows> <cols> <ctlPort>\n"
  App on every resize (fire-and-forget, off the main thread):
       Socket k = new Socket(); k.connect(new InetSocketAddress("127.0.0.1", ctlPort), 200);
       k.getOutputStream().write((rows + " " + cols + "\n").getBytes());
       k.close();

### OPTION B — RECOMMENDED: EXEC control channel, no FIFO

Main listener (unchanged from what you have):

  socat TCP-LISTEN:13338,bind=127.0.0.1,reuseaddr,fork \
        EXEC:"$HOME/.kern/shell.sh",pty,setsid,ctty,stderr,echo=0

$HOME/.kern/shell.sh:

  #!/data/data/com.termux/files/usr/bin/bash
  # fd 0/1 are the pts slave (socat's EXEC:...,pty)
  CTL=

  cleanup() { [ -n "$CTL" ] && kill "$CTL" 2>/dev/null; }
  trap cleanup EXIT HUP TERM

  IFS=' ' read -r tag rows cols ctlport || exit 1
  [ "$tag" = SIZE ] || exit 1

  PTY=$(tty)                                  # -> /dev/pts/N
  stty rows "$rows" cols "$cols"

  case "$ctlport" in
    ''|*[!0-9]*) ;;
    *)
      # </dev/null >/dev/null 2>&1 is MANDATORY: otherwise this socat inherits
      # fd 0/1/2 == the pts slave and the session can never reach EOF.
      socat -u -T 5 \
        TCP-LISTEN:"$ctlport",bind=127.0.0.1,reuseaddr,fork \
        EXEC:"$HOME/.kern/resize.sh $PTY" \
        </dev/null >/dev/null 2>&1 &
      CTL=$!
      ;;
  esac

  stty echo
  bash -li            # deliberately NOT `exec`, so the EXIT trap can reap $CTL

$HOME/.kern/resize.sh  (chmod 700):

  #!/data/data/com.termux/files/usr/bin/bash
  PTY=$1
  read -r r c || exit 0
  case "$r" in ''|*[!0-9]*) exit 0;; esac
  case "$c" in ''|*[!0-9]*) exit 0;; esac
  [ "$r" -ge 1 ] && [ "$r" -le 1000 ] || exit 0
  [ "$c" -ge 1 ] && [ "$c" -le 1000 ] || exit 0
  exec stty -F "$PTY" rows "$r" cols "$c"

Why `stty -F "$PTY"` and not `<&9`: the helper then holds NO handle on the pty,
so nothing delays session teardown, and once the master closes, the /dev/pts/N
node disappears and stty fails harmlessly.

EXEC command-line parsing constraint (man page): "<command-line> is a simple
command with arguments separated by single spaces." No quoting, no shell. The
Termux paths involved (/data/data/com.termux/files/home/...) contain no spaces,
so this is fine — but do not put a space in the script path.

`-u` + EXEC is legal: mayexec is computed as
`mayexec = (sock1->common.flags&XIO_DOESCONVERT ? 0 : XIO_MAYEXEC);`
— gated on crlf-conversion, not on unidirectional mode. TCP-LISTEN without
`crlf` does not set XIO_DOESCONVERT, so XIO_MAYEXEC is present in -u mode.

### OPTION A — FIFO variant, if you insist. EXACT verified syntax:

  socat -u -T 5 \
    TCP-LISTEN:"$ctlport",bind=127.0.0.1,reuseaddr,fork \
    GOPEN:"$CTLFIFO",nonblock \
    </dev/null >/dev/null 2>&1 &

and in shell.sh:

  CTLFIFO="$HOME/.kern/ctl.$$"
  rm -f "$CTLFIFO"; mkfifo -m 600 "$CTLFIFO"
  exec 8<>"$CTLFIFO"          # O_RDWR: never EOFs, never blocks (fifo(7))
  MAIN=$$
  (
    while :; do
      if read -r -t 2 r c <&8; then
        case "$r" in ''|*[!0-9]*) continue;; esac
        case "$c" in ''|*[!0-9]*) continue;; esac
        stty -F "$PTY" rows "$r" cols "$c" 2>/dev/null
      fi
      kill -0 "$MAIN" 2>/dev/null || break     # liveness escape
    done
    rm -f "$CTLFIFO"
  ) </dev/null >/dev/null 2>&1 &

Notes on that command line, each source-verified:
  * `-u` is REQUIRED (see 2a) — without it socat opens O_RDWR and echoes.
  * `GOPEN:` not `PIPE:` (see 2b/2c) — GOPEN defaults unlink-close=0.
    If you must use PIPE:, write `PIPE:"$CTLFIFO",unlink-close=0`.
  * `,nonblock` => open(O_WRONLY|O_NONBLOCK); per fifo(7) that fails ENXIO
    instead of hanging a socat child forever when the reader is gone.
    Man page: "Its only effects are that the connect() call of TCP addresses
    does not block, and that opening a named pipe for reading does not block."
    and "If the address is member of the OPEN option group, socat uses the
    O_NONBLOCK flag with the open() system call."
  * `-T 5` is an inactivity timeout so wedged children get reaped. NB semantics
    changed: "Up to version 1.8.0.0 '0' meant 'infinite'; since version 1.8.0.1
    '0' means 0 and values <0 mean infinite, default is -1."

========================================================
PART 5 — THE RUN_COMMAND ALTERNATIVE (works, but don't)
========================================================

Verified constants from termux-shared TermuxConstants.java:

    TERMUX_PACKAGE_NAME     = "com.termux"
    PERMISSION_RUN_COMMAND  = "com.termux.permission.RUN_COMMAND"
    ACTION_RUN_COMMAND      = "com.termux.RUN_COMMAND"
    EXTRA_COMMAND_PATH      = "com.termux.RUN_COMMAND_PATH"
    EXTRA_ARGUMENTS         = "com.termux.RUN_COMMAND_ARGUMENTS"
    EXTRA_WORKDIR           = "com.termux.RUN_COMMAND_WORKDIR"
    EXTRA_BACKGROUND        = "com.termux.RUN_COMMAND_BACKGROUND"   // @Deprecated
    EXTRA_RUNNER            = "com.termux.RUN_COMMAND_RUNNER"
    EXTRA_SESSION_ACTION    = "com.termux.RUN_COMMAND_SESSION_ACTION"
    EXTRA_STDIN             = "com.termux.RUN_COMMAND_STDIN"
    EXTRA_PENDING_INTENT    = "com.termux.RUN_COMMAND_PENDING_INTENT"
    (service class: com.termux/com.termux.app.RunCommandService)

Wiki-documented shape:
    am startservice --user 0 -n com.termux/com.termux.app.RunCommandService \
      -a com.termux.RUN_COMMAND \
      --es com.termux.RUN_COMMAND_PATH '/data/data/com.termux/files/usr/bin/top' \
      --esa com.termux.RUN_COMMAND_ARGUMENTS '\-n,5'

Is /dev/pts/N reachable from a Termux shell? YES, verified:
  * AOSP system/core/init/first_stage_init.cpp:
        CHECKCALL(mount("devpts", "/dev/pts", "devpts", 0, NULL));
    — no mount options, so kernel defaults apply.
  * Linux fs/devpts/inode.c:
        #define DEVPTS_DEFAULT_MODE 0600
        #define DEVPTS_DEFAULT_PTMX_MODE 0000
        inode->i_uid = opts->setuid ? opts->uid : current_fsuid();
        inode->i_gid = opts->setgid ? opts->gid : current_fsgid();
    Since opts->setuid is false (no uid= mount option), the slave node is mode
    0600 owned by the UID that opened /dev/ptmx — i.e. Termux's UID. Any other
    Termux-UID process can open it. Your app (different UID) cannot.
  * Termux itself does exactly this (terminal-emulator/src/main/jni/termux.c):
        int ptm = open("/dev/ptmx", O_RDWR | O_CLOEXEC);
        if (grantpt(ptm) || unlockpt(ptm) || ptsname_r(ptm, devname, sizeof(devname)) ...
        ioctl(ptm, TIOCSWINSZ, &sz);
        setsid();
        int pts = open(devname, O_RDWR);

Discovery of the right pts path: `PTY=$(tty)` inside the shell wrapper. But note
your app CANNOT read that value — it lives in Termux's private data dir under a
different UID. So the intent would have to invoke a script that rediscovers it
from a per-session file inside Termux, which means you need a session identifier
passed in anyway — at which point you've rebuilt the handshake, minus the speed.

Why not to use it for resize, concretely:
  * Requires com.termux.permission.RUN_COMMAND in your manifest AND
    `allow-external-apps=true` in ~/.termux/termux.properties — a manual user
    edit you cannot perform for them. A TCP port needs no such opt-in.
  * Every resize is a startService: Binder round-trip + service lifecycle +
    process spawn. Tens to hundreds of ms. Keyboard show/hide and fold posture
    changes fire in bursts; this will visibly lag and can coalesce badly.
  * Android 10+ background-start restrictions apply (the wiki recommends
    granting Termux "Draw Over Apps" to work around them) — so it can silently
    fail depending on app state.

Verdict: fine as a one-shot bootstrap (e.g. to start the socat listeners in the
first place, which you presumably already do). Wrong for a hot path.

## Pitfalls
- MUST pass -u. Without it socat opens the FIFO O_RDWR (xioopen_fifo: `rw = xioflags & XIO_ACCMODE`; socat.c gives address2 XIO_RDWR in bidirectional mode; XIO_RDWR == O_RDWR) and the man page states plainly: 'When a pipe is used for both reading and writing, it works as echo service.' socat will read back its own resize line and your reader loop will never see it. Your suspicion was correct.
- SESSION-LEAK BUG NOT IN THE ORIGINAL DESIGN: any helper forked from shell.sh inherits fd 0/1/2 == the pts slave (and fd 9 if you `exec 9<&0` before forking). socat's pty master only returns EOF when the LAST slave fd closes, so a long-lived reader loop or control socat holding one means bash can exit and the TCP session never tears down — the app thinks the shell is alive and you leak a process per session. Always launch helpers with `</dev/null >/dev/null 2>&1`, and prefer `stty -F "$PTY"` over `<&9` so no helper holds a pty handle at all.
- FIXED CONTROL PORT vs `fork`: the main socat uses `fork`, so N concurrent shells exist, but only one process can listen on a given TCP port (SO_REUSEADDR does not permit two live listeners — that needs SO_REUSEPORT). Session 2's control listener will fail to bind, silently, and session 2 will never resize. Fix: have the app choose a free ephemeral port and pass it in the handshake line ('SIZE rows cols ctlport'). Also make sure the control listener dies with its session, or an orphan will hold the port and resize a dead pty.
- `stty rows R cols C` is TWO ioctls, not one. coreutils main() dispatches 'rows' and 'cols' to separate set_window_size() calls, each doing its own TIOCGWINSZ+TIOCSWINSZ. That means up to two SIGWINCH per resize and a transient (newRows, oldCols) winsize that vim/tmux will briefly render. Harmless but visible as a double redraw on fold. A 20-line C helper doing a single TIOCSWINSZ removes it.
- PIPE: may delete your FIFO. xio-pipe.c has `bool opt_unlink_close = true;` and unlinks on close — but only when socat itself created the FIFO (the registration is nested inside the `Stat() < 0` ENOENT branch). With `fork`, that's a delete on every connection close, and it only triggers if socat wins the startup race against your mkfifo. Non-deterministic and miserable to debug. Use GOPEN: (defaults to keep) or pass `unlink-close=0` explicitly.
- Do NOT use `while read line < "$FIFO"; do ...; done` as the reopen-per-iteration fix. It avoids the EOF bug and does not busy-spin (open() blocks), but it silently DISCARDS any bytes the writer sent after the first line, because the fd closes at the end of each iteration. Use `exec 8<>"$FIFO"` instead — fifo(7): 'Under Linux, opening a FIFO for read and write will succeed both in blocking and nonblocking mode. POSIX leaves this behavior undefined.' Holding a write end yourself means the FIFO never EOFs, so one plain blocking loop works.
- A blocking-open FIFO writer can wedge forever. fifo(7): 'Normally, opening the FIFO blocks until the other end is opened also.' If your reader loop has died, each socat child blocks in open(O_WRONLY) holding the accepted TCP connection open — the app's connect() and write() both succeed, so it never notices. Add `,nonblock` (fails ENXIO instead) plus `-T 5`. Note -T semantics changed: 'Up to version 1.8.0.0 "0" meant "infinite"; since version 1.8.0.1 "0" means 0 and values <0 mean infinite.'
- SECURITY, pre-existing and now widened: on Android the loopback interface is shared by every installed app. Any app with INTERNET permission can already connect to 127.0.0.1:13338 and get a full interactive Termux shell with your UID and data — that is a serious hole independent of this work. A second port makes it worse only marginally, but since you are touching the handshake anyway, add a random per-session token that the app must send as the first line on BOTH ports, and have the shell drop connections that fail it.

## Confidence
HIGH CONFIDENCE (read directly from primary source, quoted above):
- socat's FIFO open modes and the -u => O_WRONLY mapping. Read from the actual socat-1.8.0.3 tarball I downloaded and extracted, not from docs or memory.
- The PIPE: unlink-close nesting quirk. Read from xio-pipe.c directly; note it partially contradicts the man page's blanket claim, and I trust the code.
- socat having no winsize support anywhere. This is a negative claim but I grepped the complete source tree and the complete man page text; the only hits were the Openpty() wrapper signature (both call sites pass NULL) and TCP socket buffer sizes.
- TIOCSWINSZ not going through tty_check_change(), and tty_do_resize() signalling the foreground pgrp. Quoted from tty_io.c.
- coreutils stty never calling tcsetattr for rows/cols, and always using STDIN_FILENO.
- fifo(7) on O_RDWR-under-Linux.
- devpts default mode 0600 / current_fsuid ownership, and Android mounting devpts with NULL options.

MEDIUM CONFIDENCE:
- The claim that `-u ... GOPEN:fifo` is a *tested* socat configuration: I saw `-u UNIX-LISTEN:... GOPEN:$tf` and `-u TCP4-LISTEN:... CREATE:$tf` in socat's test.sh, but not the exact `-u TCP-LISTEN ... GOPEN:<fifo>` triple. The pieces are each tested; the combination is inferred.
- bash's `read` doing 1-byte reads on a FIFO. I verified the call site (`input_is_pipe = fd_ispipe(fd)` -> `unbuffered_read = 1` -> `zread(fd, &c, 1)`) but did NOT read fd_ispipe()'s implementation, which lives in another file. A FIFO is not seekable so it should classify as a pipe, but I did not confirm that byte-for-byte.

COULD NOT VERIFY — flagging explicitly:
1. I ran NOTHING on an Android device or in Termux. This environment is Windows with no device attached. Every claim here is source-level verification, not empirical testing. Before shipping, actually run the resize path on a real fold and confirm with `stty -a` inside the session.
2. socat version in Termux. I read upstream 1.8.0.3. If Termux ships 1.7.x, `-T 0` means "infinite" rather than 0, and I did not diff xio-pipe.c / xio-gopen.c against 1.7.x (these are old, stable code paths, but I did not check). Run `socat -V` in Termux first.
3. AOSP SELinux policy. I tried to fetch platform_system_sepolicy public/app.te at two paths and got 404 both times, so I did NOT directly verify a rule like `allow appdomain devpts:chr_file ...`. I am inferring devpts access from the fact that Termux's own terminal works at all (termux.c opens /dev/ptmx and the slave). Same-UID same-domain access is near-certain but unverified by policy text.
4. Whether Termux's `stty` is GNU coreutils or busybox on a default install. I analysed GNU coreutils. busybox stty also supports `rows`/`cols` and also uses TIOCSWINSZ, so the conclusion should hold either way, but I did not read busybox's stty source.
5. Whether bash's `exec 9<&0` fd survives into `exec bash -li` without FD_CLOEXEC. This is standard, well-known bash behaviour and the whole reason the idiom exists, but I did not read bash's redirect.c to confirm no CLOEXEC is set on user-specified fds. My recommendation sidesteps the question entirely by using `stty -F "$PTY"` instead of fd 9.
6. The exact latency of a RUN_COMMAND startService round trip on a modern device. I characterised it as "tens to hundreds of ms" from the architecture (Binder + service lifecycle + process spawn); I did not find a measured number, and the wiki does not give one.

## Sources
- socat man page (official, dest-unreach.org) — PIPE:/GOPEN:/CREATE:/EXEC: address semantics, -u, nonblock, unlink-close, -T, fork: http://www.dest-unreach.org/socat/doc/socat.html
- socat 1.8.0.3 source tarball — xio-pipe.c, xio-gopen.c, xio-named.c (_xioopen_open), xio.h (XIO_RDWR==O_RDWR), socat.c (-u -> XIO_WRONLY, mayexec), sycls.c/xio-progcall.c (Openpty(...,NULL,NULL)), test.sh: http://www.dest-unreach.org/socat/download/socat-1.8.0.3.tar.gz
- Linux drivers/tty/tty_io.c — tty_do_resize(), tiocswinsz(), tty_pair_get_tty(), and the tty_check_change() case list (TIOCSWINSZ absent): https://raw.githubusercontent.com/torvalds/linux/master/drivers/tty/tty_io.c
- Linux drivers/tty/pty.c — pty_resize() and the pty_unix98_ops slave ops struct (no .resize member): https://raw.githubusercontent.com/torvalds/linux/master/drivers/tty/pty.c
- GNU coreutils src/stty.c — set_window_size(), get_win_size(), the rows/cols dispatch in main(), require_set_attr gating of tcsetattr, and fd_reopen for -F: https://raw.githubusercontent.com/coreutils/coreutils/master/src/stty.c
- fifo(7) — blocking open semantics, O_RDWR on a FIFO under Linux, ENXIO with O_NONBLOCK, SIGPIPE: https://man7.org/linux/man-pages/man7/fifo.7.html
- bash builtins/read.def — input_is_pipe/fd_ispipe, unbuffered_read, zread(fd,&c,1) one-byte reads on pipes: https://cgit.git.savannah.gnu.org/cgit/bash.git/plain/builtins/read.def
- Termux TermuxConstants.java (RUN_COMMAND_SERVICE constants) and the RUN_COMMAND-Intent wiki (permission + allow-external-apps requirements): https://github.com/termux/termux-app/wiki/RUN_COMMAND-Intent

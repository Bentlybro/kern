# 13 — Git and GitHub

Implementation: `runtime/GitHubAuth.kt`, `ui/GitHubSection.kt`.

## What the feature actually is

The valuable part is not `gh`. It is **`gh auth setup-git`**, which registers gh as git's
credential helper. After that, plain `git clone`, `git pull` and `git push` over HTTPS
authenticate on their own — the editor's Git panel, the terminal, and any CLI agent
running in the guest all inherit it without knowing the sign-in screen exists.

Everything else in this document is in service of getting that one command run.

## Why device flow

On a phone, the options are:

| Approach | Problem |
|---|---|
| Password | GitHub does not accept them for git over HTTPS |
| Personal access token | ~40 characters of random text typed on a touch keyboard |
| SSH key | Generate, then copy a public key between apps, then paste into a browser |
| **Device flow** | Eight characters, and the app can put them on the clipboard |

Device flow wins on mobile by a wide margin. GitHub issues a short code, the user
approves in a browser, and nothing long is ever typed.

The one mobile-hostile part is copying the code out of a terminal, so the app shows it
natively with a **Copy code & open GitHub** button that does both.

## Driving `gh` has two sharp edges

Both were discovered the hard way and are worth recording.

### 1. Its prompts block on a terminal handshake

`gh`'s prompts are drawn by a TUI that asks the terminal where the cursor is (`ESC[6n`)
and **blocks until the terminal answers**. Feeding keystrokes to a bare PTY deadlocks —
there is nobody on the other end to reply.

The fix is to run `gh` under **tmux**, which is a real terminal emulator and answers on
our behalf. `tmux capture-pane -p` then hands back the screen as plain text, so the
one-time code is read without parsing escape sequences.

    bare pty  →  ? Authenticate Git with your GitHub credentials? (Y/n) ^[[?25l^[7^[[999;999f^[[6n   ← hangs here
    tmux      →  ! First copy your one-time code: 4D91-8610

### 2. tmux servers do not cross PRoot instances

A tmux server started under one PRoot instance is unreachable from another:

    access not allowed

PRoot's fake ownership lives in process memory and never reaches the socket on disk, so a
second instance sees a socket it does not believe it owns.

So the whole flow stays inside **one** PRoot process, and the script mirrors the pane to a
file. The app reads that file directly — the rootfs is its own private storage, so a file
the guest writes is a file Kotlin can simply open. No pipes, no ANSI stripping.

## The flow, end to end

1. The app writes a shell script into the guest's `/tmp` — a plain file write, so no
   shell quoting is involved.
2. One PRoot process runs it. Inside, tmux starts `gh auth login --web` detached.
3. The script polls `capture-pane` into a file once a second, and answers gh's two
   prompts with `send-keys`. Each answer is guarded by a flag file, because the prompt
   stays on screen after it is answered and would otherwise be pressed repeatedly.
4. Kotlin reads the pane file and extracts the code, anchored on gh's own wording
   (`one-time code: XXXX-XXXX`) so it cannot match a stray token.
5. The UI shows the code; the button copies it and opens `github.com/login/device`.
6. gh polls GitHub. On approval it writes its token and exits; the script records the
   exit code to a file.
7. Kotlin sees that file, runs `gh auth setup-git`, and sets the commit identity.

Cancelling is a deliberate stop, not a failure — the polling loop detects it and returns
without posting an error over the UI that `cancel()` has already reset.

## Commit identity

Setup leaves a placeholder identity so `git commit` does not fail outright. On sign-in
that is replaced with the real account:

- `user.name` from the account's name, falling back to its login
- `user.email` from the public profile email, falling back to GitHub's
  `ID+login@users.noreply.github.com` form for accounts that keep their address private

## Requirements in the guest

`git`, `gh` and `tmux`, all from Ubuntu's own repositories — no third-party apt source is
needed. `ca-certificates` is not optional: without a trust store, gh's Go TLS stack
rejects github.com outright with `certificate signed by unknown authority`.

## Where it surfaces

- **Status** — a GitHub row: not installed, not signed in, or signed in as *who*. Not
  signed in is a warning rather than a failure, because cloning public repositories still
  works; pushing does not.
- **Settings** — the full section: install, sign in, the device code, and sign out.

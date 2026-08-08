# 13 — Git and GitHub

The implementation lives in `runtime/GitHubAuth.kt` and `ui/GitHubSection.kt`.

## What the feature actually is

The valuable part is not `gh`. It is **`gh auth setup-git`**, which registers gh as git's credential helper. After that, plain `git clone`, `git pull` and `git push` over HTTPS authenticate on their own — the editor's Git panel, the terminal, and any CLI agent running in the guest all inherit it without knowing the sign-in screen exists.

Everything else in this document is in service of getting that one command run.

## Why device flow

On a phone, these are the options:

| Approach | Problem |
|---|---|
| Password | GitHub does not accept passwords for git over HTTPS. |
| Personal access token | The user has to type about 40 characters of random text on a touch keyboard. |
| SSH key | The user has to generate a key, copy the public half between apps, and paste it into a browser. |
| **Device flow** | It is eight characters, and the app can put them on the clipboard. |

Device flow wins on mobile by a wide margin. GitHub issues a short code, the user approves in a browser, and nothing long is ever typed.

The one mobile-hostile part is copying the code out of a terminal, so the app shows the code natively with a **Copy code & open GitHub** button that does both.

## Driving `gh` has two sharp edges

We found both of them the hard way, and both are worth recording.

### 1. Its prompts block on a terminal handshake

`gh`'s prompts are drawn by a TUI that asks the terminal where the cursor is, using `ESC[6n`, and **blocks until the terminal answers**. Feeding keystrokes to a bare PTY deadlocks, because there is nobody on the other end to reply.

The fix is to run `gh` under **tmux**, which is a real terminal emulator and answers on our behalf. `tmux capture-pane -p` then hands back the screen as plain text, so we read the one-time code without parsing escape sequences.

    bare pty  →  ? Authenticate Git with your GitHub credentials? (Y/n) ^[[?25l^[7^[[999;999f^[[6n   ← hangs here tmux      →  ! First copy your one-time code: 4D91-8610

### 2. tmux servers do not cross PRoot instances

A tmux server started under one PRoot instance is unreachable from another:

    access not allowed

PRoot's fake ownership lives in process memory and never reaches the socket on disk, so a second instance sees a socket it does not believe it owns.

The whole flow therefore stays inside **one** PRoot process, and the script mirrors the pane to a file. The app reads that file directly, because the rootfs is its own private storage, so a file the guest writes is a file Kotlin can open. There are no pipes and no ANSI stripping.

## The flow, end to end

1. The app writes a shell script into the guest's `/tmp`. That is a plain file write, so no shell quoting is involved.
2. One PRoot process runs it, and inside it tmux starts `gh auth login --web` detached.
3. The script polls `capture-pane` into a file once a second, and answers gh's two prompts with `send-keys`. Each answer is guarded by a flag file, because the prompt stays on screen after it is answered and would otherwise be pressed repeatedly.
4. Kotlin reads the pane file and extracts the code, anchoring on gh's own wording, `one-time code: XXXX-XXXX`, so that it cannot match a stray token.
5. The UI shows the code, and the button copies it and opens `github.com/login/device`.
6. gh polls GitHub. On approval it writes its token and exits, and the script records the exit code to a file.
7. Kotlin sees that file, runs `gh auth setup-git`, and sets the commit identity.

Cancelling is a deliberate stop rather than a failure, so the polling loop detects it and returns without posting an error over the UI that `cancel()` has already reset.

## Commit identity

Setup leaves a placeholder identity so that `git commit` does not fail outright. On sign-in the real account replaces it:

- `user.name` comes from the account's name, falling back to its login.
- `user.email` comes from the public profile email, falling back to GitHub's `ID+login@users.noreply.github.com` form for accounts that keep their address private.

## Requirements in the guest

The guest needs `git`, `gh` and `tmux`, and all three come from Ubuntu's own repositories, so no third-party apt source is needed. `ca-certificates` is not optional: without a trust store, gh's Go TLS stack rejects github.com outright with `certificate signed by unknown authority`.

## Where it surfaces

- **Status** shows a GitHub row, which reads as not installed, not signed in, or signed in as a named account. Not being signed in is a warning rather than a failure, because cloning public repositories still works, though pushing does not.
- **Settings** carries the full section: install, sign in, the device code, and sign out.

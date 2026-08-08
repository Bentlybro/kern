# 18 — The terminal and the agent

The implementation is in `ui/TerminalSessions.kt`, `ui/TerminalPane.kt`, `ui/CockpitScreen.kt` and `runtime/AgentRepository.kt`.

## One registry, not two special cases

`TerminalSessions` owns every terminal in the app. It replaced a singleton holding one view and one session, which forced two limitations that looked unrelated but were the same problem:

- You could only ever have one shell.
- The agent cockpit had no terminal at all, so it kept agent output as a list of lines.

That second one is why TUI agents rendered as gibberish there. **A TUI does not emit lines.** It repaints a screen with cursor movement, and no amount of escape stripping turns that into text — the more carefully you strip, the more wrong it looks. The fix was not a better parser. It was to stop parsing and give the agent the same emulator the terminal uses, which fell out for free once a session was just a session.

Deleting the line buffer removed the pty handling, the ring buffer, the line splitting and the escape filter along with it. `AgentRepository` now holds only the choice of agent and the git working tree, which is the half that is useful with no agent at all.

## Sessions

| Kind | Runs | Shown in |
|---|---|---|
| `Shell` | It runs a login shell under tmux. | It appears in the terminal pane, with one tab for each shell. |
| `Agent` | It runs the configured agent command. | It appears in the cockpit's output tab. |

Each shell gets **its own tmux session name**, which is `kern-<id>`. `tmux new-session -A` attaches to an existing session of the same name, so a shared name meant that every new tab attached to the first one, leaving several terminals that were all the same terminal.

Terminals open in the **current project**, and Kern creates the tmux session there too, so the directory holds after a detach. An open terminal does not follow a project change, because it may be mid-command and injecting `cd` into something that is running would corrupt it. New terminals pick up the new project.

## Failures worth remembering

Three bugs here had causes nowhere near their symptoms.

**Back killed whatever was running.** Termux maps back to escape, because a phone often has no other way to send one. Kern has an `esc` key in the key row, so the mapping bought nothing, and back never reached the keyboard. The terminal swallowed it and sent ESC to the running program, which anything interactive reads as "quit". Putting the keyboard away killed the thing you were watching.

**`exit` left a dead terminal.** The session ended, the pane stayed, and there was no way to start another short of restarting the app. A shell that finishes now takes its tab with it, and if it was the last one a fresh shell opens in its place. An agent that exits is simply gone, because the cockpit then offers to start it again. That is right for something you chose to run and wrong for a shell you always want available.

**The sidebar chips would not toggle.** Neither shortcut is a toggle on its own. `Ctrl+B` changes whether the sidebar is *visible* without changing which view it holds, so after switching to source control the files chip only hid and showed source control. `Ctrl+Shift+E` and `Ctrl+Shift+G` select a view but only collapse the sidebar when focus is already inside it, and a chip tap leaves focus in the editor, so both panels became stuck open. The chips now remember which view they last opened and send `Ctrl+B` when asked for it again.

## The needs-you notification

`SessionService` polls the agent's **rendered screen**, not a byte stream. For a TUI the last thing written and the last thing shown are routinely different, and only the second one is the question being asked.

The classification is a heuristic — agents do not announce that they are waiting — but it only drives a notification, so a wrong guess is cheap.

## What does not survive

This is worth being blunt about, because tmux implies more than it delivers here.

tmux keeps a shell alive across **detaching from a pane**. It does not keep anything alive across the **app dying**, because the tmux server runs inside Kern's own PRoot: force-stop the app, or let Android reclaim it, and every guest process goes with it.

- Files saved to the guest filesystem are safe.
- Unsaved editor buffers and running builds are not safe.
- Swiping the app away is fine, because the foreground service keeps the session running.

**Quit**, in the overflow menu, stops the supervisor deliberately, which takes code-server and the guest down cleanly. Until it existed the only way to end a session was the notification action, which is not where anyone looks.

Making guest processes genuinely outlive the app is unsolved and is design work, not a tweak. See [19 — Next](19-next.md).

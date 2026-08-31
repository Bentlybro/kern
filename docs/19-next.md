# 19 — What is next

This is an honest list, ordered by what would most change Kern as a daily tool rather than by what is easiest. Anything marked **unverified** builds and deploys but has not been proven on device, and should be treated as unfinished.

## 1. Guest processes should outlive the app

This is the biggest gap, and the one most likely to lose someone's work.

tmux protects a shell from a pane being detached. It does not protect anything from the app dying, because the tmux server is a child of Kern's own PRoot. Force-stop, or Android reclaiming memory, takes every guest process with it — including a build you left running.

This is design work rather than a fix. The options are all awkward, which is why it is still open:

- Kern could keep the foreground service alive harder, which helps with memory pressure but not with a force-stop.
- Kern could re-exec the guest from the service rather than the activity, so that the process tree hangs off something longer-lived.
- Kern could accept the behaviour and say so plainly before a long build.

One thing is also still open from [11](11-embedded-linux.md): process survival has only been measured on a device with **child process restrictions disabled**. On a stock phone the 32-process phantom killer applies, and the guest already runs ~70 processes. That measurement is the prerequisite for choosing between the options above.

## 2. A detachable, movable terminal

Somebody asked for this and nobody has started it. The terminal is currently either a fixed split or a full pane.

What was asked for is a terminal that sits above the keyboard, can be resized, and can be dragged wherever the user wants it. The interaction is worth sketching before building, because "floating panel" covers several quite different designs and the wrong guess is a lot of work to undo.

## 3. ~~Prove the cockpit with a real TUI agent~~ — **verified 2026-08-31**

Done, with `less` standing in for an agent. It draws a full screen, redraws on input, and renders its reverse-video status line correctly; a `G` typed into the native reply field reached it and jumped the view to `(END)`. So the cockpit's terminal handles full-screen redraw, cursor addressing, attributes, and input from the native chrome.

What is still open is narrower than it was: no *actual* coding agent has run in it, because none is installed on the test device and each wants credentials. The rendering question — the one that made TUI agents look broken before the cockpit got a real emulator — is answered.

Two things worth knowing, both found while doing this. `htop` cannot work in the guest at all: Android denies apps `/proc/stat`, which is the platform rather than PRoot, and is now recorded in [03](03-android-constraints.md). And `less` was not installed, which is why `git log` used to dump its whole output at the terminal; it is in the toolchain now.

## 4. Extensions from Open VSX

This is untested end to end. A large part of the "VS Code-class" claim rests on extensions installing and persisting across a restart, and nobody has checked.

## 5. The fold layouts

The fold layouts are the least-tested surface in the app. Nobody has exercised tabletop mode, the crease split or the cover display on real hardware since the rename. Everything else in this project was verified on a Pixel.

## 6. Quality of life, once specifics exist

This entry is deliberately vague until it is not. "Easier as a daily tool" could mean a dozen things, and building the three that are actually missed beats building twelve that are not. It is worth collecting real friction from use rather than guessing.

## Known smaller items

- **PR #15**, the grouped Gradle updates, needs its rebase to pick up the `compilerOptions` migration. It should go green on its own, but confirm that rather than assuming it.
- **AGP 9 and Gradle 9** are open as separate Dependabot PRs. They are migrations rather than updates, and they deserve their own session.
- The **release draft `v0.1.0`** is built, signed and verified installable, and it is waiting to be published.
- `docs/11`'s verification table is still from the original 24.04 run. 26.04 was verified the same way, but nobody rewrote the table.

# 19 — What is next

An honest list. Ordered by what would most change Kern as a daily tool, not by what is
easiest. Anything marked **unverified** builds and deploys but has not been proven on
device, and should be treated as unfinished.

## 1. Guest processes should outlive the app

The biggest gap, and the one most likely to lose someone's work.

tmux protects a shell from a pane being detached. It does not protect anything from the
app dying, because the tmux server is a child of Kern's own PRoot. Force-stop, or Android
reclaiming memory, takes every guest process with it — including a build you left running.

This is design work rather than a fix. The options are all awkward, which is why it is
still open:

- Keep the foreground service alive harder, which helps with memory pressure but not with
  force-stop.
- Re-exec the guest from the service rather than the activity, so the process tree hangs
  off something longer-lived.
- Accept it, and make the app say so plainly before a long build.

Also still open from [11](11-embedded-linux.md): process survival has only been measured
on a device with **child process restrictions disabled**. On a stock phone the 32-process
phantom killer applies, and the guest already runs ~70 processes. That measurement is the
prerequisite for choosing between the options above.

## 2. A detachable, movable terminal

Requested and not started. The terminal is currently a fixed split or a full pane.

What was asked for: sitting above the keyboard, resizable, and draggable to wherever the
user wants it. Worth sketching the interaction before building, because "floating panel"
covers several quite different designs and the wrong guess is a lot of work to undo.

## 3. Prove the cockpit with a real TUI agent — **unverified**

The cockpit now renders a real terminal, which should fix TUI agents. That has not been
demonstrated, because no agent is installed on the test device. Install one, run it, and
confirm a full-screen interface actually draws and accepts input.

## 4. Extensions from Open VSX

Untested end to end. A large part of the "VS Code-class" claim rests on extensions
installing and persisting across a restart, and nobody has checked.

## 5. The fold layouts

Least-tested surface in the app. Tabletop mode, the crease split, and the cover display
have not been exercised on real hardware since the rename. Everything else in this project
was verified on a Pixel.

## 6. Quality of life, once specifics exist

Deliberately vague until it is not. "Easier as a daily tool" could mean a dozen things,
and building the three that are actually missed beats building twelve that are not. Worth
collecting real friction from use rather than guessing.

## Known smaller items

- **PR #15** (grouped Gradle updates) needs its rebase to pick up the `compilerOptions`
  migration. Should go green on its own; confirm rather than assume.
- **AGP 9 and Gradle 9** are open as separate Dependabot PRs. They are migrations, not
  updates, and deserve their own session.
- The **release draft `v0.1.0`** is built, signed and verified installable, waiting to be
  published.
- `docs/11`'s verification table is still from the original 24.04 run. 26.04 was verified
  the same way but the table was not rewritten.

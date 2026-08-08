# 14 — Storage

The implementation lives in `runtime/StorageManager.kt` and `ui/SettingsScreen.kt`.

## What it costs

These figures were measured on device after a complete setup:

| | |
|---|---|
| Download | The download comes to about 400 MB, of which 34 MB is the base image, 218 MB is code-server, and the rest is packages. |
| Installed | The result occupies about 1.2 GB on disk. |
| Package cache | About 140 MB of that is package cache, and it can be reclaimed. |

The setup screen states both figures before the user commits to anything. They are measured rather than estimated, and an earlier "120 MB / 900 MB" was simply wrong, mostly because nobody had checked how large the code-server package is.

## There is no hard cap, and the UI says so

The guest is a **directory** in app-private storage rather than a disk image, so nothing can enforce a ceiling on it. A real quota would need a loopback ext4 image, and Android does not let an unprivileged app mount one.

The budget of 2, 4, 8 or 16 GB is therefore presented honestly as a **warning threshold**, and the UI states that reason rather than implying it. Pretending otherwise would be a lie the user discovers at the worst possible moment.

## Measuring it correctly

Counting bytes in the guest is not as simple as walking the tree, and getting it wrong is not subtle: settings once reported **3.2 GB** for a rootfs that `du` put at **1.2 GB**.

The cause is worth understanding, because it follows directly from how the guest works. PRoot rewrites every hard link into a symlink pointing at a shared file (see [11 — Embedded Linux](11-embedded-linux.md)), and `File.length()` on a symlink returns the **target's** size. Ubuntu is full of hard links — Ubuntu 26.04's coreutils alone is one binary behind ~115 of them — so the same bytes were billed once per link.

The measurement therefore skips symlinks entirely and does not descend into symlinked directories. It now agrees with `du` exactly.

## Reclaiming space

**Free up space** clears what is safe to throw away: the apt cache, the package lists, `~/.cache`, `/tmp`, and the server log. It leaves projects and installed tools untouched, and the button reports how much it actually freed rather than claiming success.

**Repair** re-runs the idempotent installer, which reinstalls anything missing. It runs on the installer's own scope, so closing settings does not cancel it mid-package.

**Delete Linux** removes the environment entirely and drops the app back to setup. It requires confirmation and states what will be lost, including anything unpushed in `~/projects`.

## Deleting must be observable

Whether Linux is installed is a question about the filesystem, which Compose cannot observe. Held in a bare `remember {}`, the answer was computed once when the activity opened and never revisited, so deleting the environment left the app on "starting linux" forever, waiting for a guest that no longer existed. Only a restart cleared it.

`LinuxRuntime` therefore publishes a counter that it bumps whenever the rootfs is created or destroyed, and the app's top-level routing is keyed on that counter.

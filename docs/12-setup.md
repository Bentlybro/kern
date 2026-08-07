# 12 — Setup and the installer

First run is the only moment Kern asks the user to wait, so it is worth doing well. This
covers what setup actually does, why it is split in two, and where the time goes.

Implementation: `runtime/RootfsInstaller.kt`.

## The shape

Setup runs in **two halves**, and the split is the single most important thing about it.

| Phase | Steps | Why here |
|---|---|---|
| **1 — essential** | Download the Ubuntu base image, unpack it through PRoot, write apt/DNS/dpkg configuration, `apt-get update`, install code-server | The editor cannot open without a filesystem and a server |
| **2 — background** | `ca-certificates git gh tmux curl ripgrep python3 python3-pip`, then `polish()` | Worth having, but nothing here blocks writing code |

Between the two, the app is told the environment is usable. The IDE opens, and the
toolchain installs behind it with a strip in the editor reporting progress.

Measured on device from a wiped guest:

    editor usable      133s
    toolchain complete 184s

Before the split, nothing was usable until everything had finished.

## Where the time went, and what was done about it

Three changes, each measured rather than assumed:

- **The largest download now overlaps the CPU-bound work.** The code-server package is
  218 MB against the base image's 34, and unpacking is CPU bound, so code-server is
  fetched *during* unpacking. It lands in the app cache and is `renameTo`'d into the
  rootfs, which is free — same filesystem, so no 218 MB copy.
- **`force-unsafe-io` for dpkg.** dpkg fsyncs after every extracted file, which on phone
  storage is the single largest cost of installing anything. Container images disable it
  for the same reason. The exposure is a half-written install if the device loses power
  mid-apt, which Repair already recovers from.
- **Docs, man pages and translations are excluded.** Nothing on a phone reads them.

## Live output

Setup used to show one line per phase, and "Installing tools" for three minutes is
indistinguishable from being stuck. `LinuxRuntime.run` can now tail its own output file —
cheap, because the file is inside our own storage — and the setup screen shows apt
scrolling live.

apt's `(Reading database ... N%` redraws are filtered out. They fire dozens of times a
second and say nothing.

## Idempotence

Every step checks whether it is needed before doing it. That is what makes the same
function serve three purposes:

- **first run** — everything is missing, so everything runs
- **resume** — an interrupted setup re-runs only what did not finish
- **Repair** (in settings) — reinstalls whatever has gone missing

## Setup must outlive its screen

`RootfsInstaller` owns a `CoroutineScope` and `start()` is single-flight. This is not
incidental tidiness — it is a fix for a real failure.

Setup originally ran in the setup screen's own `rememberCoroutineScope()`. code-server is
installed *before* the toolchain, so partway through setup the screen decided the
environment looked usable and replaced itself — cancelling its own scope, and the running
install with it. On the test device that killed apt mid-unpack of coreutils and left a
guest where even `ls` was `command not found`.

Two defences now:

1. The install runs in a scope owned by the installer, so navigation cannot cancel it.
2. The setup screen does not treat "code-server exists" as "ready" while the installer is
   still working, so the IDE cannot be opened into a half-built environment where it
   would race dpkg for its lock.

## Recovery

A guest can be damaged past what re-running setup fixes — an interrupted apt can take
coreutils with it, and then nothing in the guest runs. Settings lives *inside* the IDE,
so a guest too broken to start one would be impossible to throw away.

The setup screen therefore offers **Delete and start over** whenever an environment
exists, not only when one does not.

## Configuration written into the guest

| File | Why |
|---|---|
| `/etc/resolv.conf` | The base image ships none; without it nothing resolves |
| `/etc/apt/sources.list` | arm64 lives on `ports.ubuntu.com`, not `archive.ubuntu.com` |
| `/etc/apt/apt.conf.d/99kern` | `APT::Sandbox::User "root"` (apt drops to `_apt` otherwise, which cannot work under PRoot), pipelining and PDiffs off (classic causes of apt hanging under PRoot), no recommends, no translations |
| `/etc/dpkg/dpkg.cfg.d/01-kern` | `force-unsafe-io` and the doc/man exclusions |

The deb822 `ubuntu.sources` shipped in the base image is removed, or apt warns that every
target is configured twice.

## Distro version

The release and its codename live together in `RootfsInstaller`, because `sources.list`
is written from the codename and the two drifting apart yields a rootfs that cannot
install anything.

Changing the version affects **fresh setups only** — an existing rootfs is left alone.
Settings names the installed release and, when it differs from the current one, explains
that Delete Linux and setting up again is how to move over.

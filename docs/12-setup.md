# 12 — Setup and the installer

First run is the only moment Kern asks the user to wait, so it is worth doing well. This document covers what setup actually does, why it is split in two, and where the time goes.

The implementation lives in `runtime/RootfsInstaller.kt`.

## The shape

Setup runs in **two halves**, and the split is the single most important thing about it.

| Phase | Steps | Why here |
|---|---|---|
| **1 — essential** | It downloads the Ubuntu base image, unpacks it through PRoot, writes the apt, DNS and dpkg configuration, runs `apt-get update` and installs code-server. | The editor cannot open without a filesystem and a server. |
| **2 — background** | It installs `ca-certificates git gh tmux curl ripgrep python3 python3-pip`, then runs `polish()`. | All of this is worth having, but none of it blocks writing code. |

Between the two, the installer tells the app that the environment is usable. The IDE opens, and the toolchain installs behind it with a strip in the editor reporting progress.

These figures were measured on device from a wiped guest:

    editor usable      133s toolchain complete 184s

Before the split, nothing was usable until everything had finished.

## Where the time went, and what was done about it

There are three changes, and each one was measured rather than assumed.

- **The largest download now overlaps the CPU-bound work.** The code-server package is 218 MB against the base image's 34, and unpacking is CPU bound, so the installer fetches code-server *during* unpacking. It lands in the app cache and is `renameTo`'d into the rootfs, which is free: the two sit on the same filesystem, so nothing copies 218 MB.
- **dpkg runs with `force-unsafe-io`.** dpkg fsyncs after every extracted file, which on phone storage is the single largest cost of installing anything. Container images disable it for the same reason. The exposure is a half-written install if the device loses power mid-apt, which Repair already recovers from.
- **Docs, man pages and translations are excluded.** Nothing on a phone reads them.

## Live output

Setup used to show one line per phase, and "Installing tools" for three minutes is indistinguishable from being stuck. `LinuxRuntime.run` can now tail its own output file, which is cheap because the file sits inside our own storage, and the setup screen shows apt scrolling live.

The installer filters out apt's `(Reading database ... N%` redraws. They fire dozens of times a second and say nothing.

## Idempotence

Every step checks whether it is needed before doing it. That is what makes the same function serve three purposes.

- On a **first run**, everything is missing, so everything runs.
- On a **resume**, an interrupted setup re-runs only what did not finish.
- **Repair**, in settings, reinstalls whatever has gone missing.

## Setup must outlive its screen

`RootfsInstaller` owns a `CoroutineScope`, and `start()` is single-flight. This is not incidental tidiness; it is a fix for a real failure.

Setup originally ran in the setup screen's own `rememberCoroutineScope()`. code-server is installed *before* the toolchain, so partway through setup the screen decided that the environment looked usable and replaced itself, cancelling its own scope and the running install with it. On the test device that killed apt mid-unpack of coreutils and left a guest where even `ls` was `command not found`.

There are two defences now.

1. The install runs in a scope owned by the installer, so navigation cannot cancel it.
2. The setup screen does not treat "code-server exists" as "ready" while the installer is still working, so the IDE cannot be opened into a half-built environment where it would race dpkg for its lock.

## Recovery

A guest can be damaged past what re-running setup fixes, because an interrupted apt can take coreutils with it, and then nothing in the guest runs. Settings lives *inside* the IDE, so a guest too broken to start one would be impossible to throw away.

The setup screen therefore offers **Delete and start over** whenever an environment exists, not only when one does not.

## Configuration written into the guest

| File | Why |
|---|---|
| `/etc/resolv.conf` | The base image ships none, and without it nothing resolves. It names the device's own DNS servers first and public resolvers underneath, and it is rewritten before every `apt` and every clone rather than only at setup — see below. |
| `/etc/apt/sources.list` | arm64 lives on `ports.ubuntu.com` rather than on `archive.ubuntu.com`. |
| `/etc/apt/apt.conf.d/99kern` | It sets `APT::Sandbox::User "root"`, because apt otherwise drops to `_apt`, which cannot work under PRoot. It also turns pipelining and PDiffs off, since those are the classic causes of apt hanging under PRoot, and it turns off recommends and translations. |
| `/etc/dpkg/dpkg.cfg.d/01-kern` | It carries `force-unsafe-io` and the doc and man exclusions. |

The installer removes the deb822 `ubuntu.sources` that the base image ships, because apt otherwise warns that every target is configured twice.

### DNS

`/etc/resolv.conf` used to name `1.1.1.1` and `8.8.8.8` and nothing else, written once at setup. That is wrong in both directions on a phone.

A guest that only ever asks a public resolver cannot see names that only the network it is on knows, and it is refused outright wherever outbound port 53 to anywhere else is blocked, which is ordinary on hotel, campus and corporate Wi-Fi and universal behind a captive portal. `apt` then fails in a way that reads as a dead mirror. Meanwhile a guest that only named the device's resolvers would be carrying whatever was current when it was installed, which on a phone is stale within the day.

So the file names both, device first, public underneath, capped at the three entries glibc actually reads — and `GuestConfig.refreshDns` rewrites it before anything that has to reach the network, which is every `apt` and every `git clone`. Reading the device's resolvers is what `ACCESS_NETWORK_STATE` in the manifest is for, and it is the only thing it is for.

## Distro version

The release and its codename live together in `RootfsInstaller`, because `sources.list` is written from the codename, and if the two drift apart the result is a rootfs that cannot install anything.

Changing the version affects **fresh setups only**, and an existing rootfs is left alone. Settings names the installed release and, when it differs from the current one, explains that Delete Linux followed by setting up again is how to move over.

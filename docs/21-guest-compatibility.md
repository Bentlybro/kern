# What runs in the guest, and what does not

Kern gives you a real Ubuntu with a working `apt`, so the honest answer to "will this program run" is "almost certainly yes". The exceptions are not random, and they are not about PRoot being an incomplete Linux. They come from two specific restrictions, and once you know both you can predict the answer for a program you have not tried yet.

Everything below was measured on a Galaxy Z Fold8 running Ubuntu 26.04 under Kern, not inferred from documentation.

## The two rules

**The first rule is that `/proc` belongs to Android, not to the guest.** PRoot binds the host's `/proc` into the rootfs, so a program that reads it is reading the phone's kernel through the eyes of an untrusted application. Android's SELinux policy denies exactly three files that matter here: `/proc/stat`, `/proc/loadavg` and `/proc/uptime`. Anything that needs processor utilisation over time, or the load average, therefore fails. Anything that needs the amount of memory, the processor model, the number of cores, or the guest's own processes works, because those files are readable.

**The second rule is that the guest has no capabilities.** PRoot makes you look like root and that is enough for `apt` and `dpkg`, which only ever check whether the user id is zero. It is not enough for anything that asks the kernel for a real privilege. Raw sockets are the common case, which is why `ping` fails while every other kind of networking is fine.

## What works

| | Verified with |
|---|---|
| **C and C++** | `gcc` and `g++` both compile a program and run the result. `make` 4.4.1 and `cmake` 4.2.3 are fine. `clang` installs. |
| **Python** | 3.14 with `pip`. Packages that are pure Python install and run normally. |
| **Node** | 22.22.1 on arm64, with `npm`. |
| **Editors** | `vim`, `neovim`, `nano` and `micro` all run. Neovim was checked properly rather than by version string: it opened a file, edited it and wrote it back. |
| **Search and files** | `ripgrep`, `fd`, `bat`, `tree`, `ncdu`, `file`, `less`, `jq`, `fzf`, `zip` and `unzip`. |
| **Version control** | `git` and the GitHub CLI, including pushing over HTTPS once you have signed in. |
| **Databases** | `sqlite3`. |
| **Networking** | Outbound TCP works normally. `curl`, `wget`, `ssh` and `nc` all reach the internet; `ssh` connects to GitHub and authenticates. |
| **Terminal programs** | `fastfetch` and `cmatrix` render correctly, so the terminal itself is not the limitation. |
| **`strace`** | This one is a genuine surprise. Tracing works inside PRoot, which is itself a `ptrace` supervisor. PRoot prints `ptrace request not supported yet` for some requests, so it is partial rather than complete, but the common case produces a usable syscall summary. |
| **`top`** | It runs and lists processes. Processor percentages are not meaningful, because that number comes from the file Android will not let it read, so it degrades rather than failing outright. |

## What does not work

| | What you see | Why |
|---|---|---|
| `htop` | `Cannot open /proc/stat: Permission denied` | The first rule. It refuses to start without processor statistics. |
| `btop` | Starts, but cannot report processor usage | The first rule. |
| `bpytop` and `glances` | Install, then fail on first use | The first rule again, one layer down. Both use `psutil`, and `psutil` raises `PermissionError: [Errno 13] Permission denied: '/proc/stat'`. |
| `uptime` | `Cannot get system uptime: Permission denied` | The first rule. |
| `ping` | `ping: prctl: Operation not permitted` | The second rule. Raw sockets need a capability the app does not have. Use `nc -z host port` or `curl` to test connectivity instead. |
| Docker and any container runtime | Nothing to try | Namespaces and control groups are unavailable to an unprivileged application. This will not change. See the section below, because the full answer is more interesting than the headline. |

## Docker, which everybody asks about

Docker itself will never work, and the reason is worth stating precisely rather than waving at. A container is built from namespaces, control groups and real mounts, and those are exactly the primitives the kernel refuses to hand an unprivileged application. There is no clever way around it, because isolation is the one thing you cannot fake: PRoot does not perform mounts at all, it rewrites paths with `ptrace`.

Docker *images*, though, are a different question, and we tested it rather than assuming. `udocker` installs with `pip`, and it pulled Alpine from Docker Hub and unpacked a complete and correct root filesystem, with `/etc/alpine-release` reading `3.24.1`. Pulling an image needs no privilege, because an image is only a stack of tarballs.

Running that image as a container fails. `udocker` does its rooting with its own copy of PRoot, and PRoot inside PRoot does not re-root: asking for `ls /` from inside the container listed `boot` and `data`, which is Ubuntu and Android rather than Alpine. Clearing the inherited `PROOT_*` variables in case they were confusing the inner instance changed nothing, and the fakechroot execution mode is unavailable because no `libfakechroot` is published for this architecture and distribution combination.

The binaries inside the image do run, which is the entertaining part. Invoking musl's loader from the unpacked image directly gets you Alpine's own BusyBox:

```
$ ld-musl-aarch64.so.1 --library-path ROOT/lib ROOT/bin/busybox
BusyBox v1.37.0 (2026-01-10 15:38:28 UTC) multi-call binary.
```

So you cannot have containers, but you can run software from any container image, because Kern already knows how to execute arbitrary ELF. The useful conclusion is that the way to run Alpine here would be to make it the *outer* root filesystem rather than to nest it inside the Ubuntu one. That is a configuration change rather than an architectural one, and it is not worth doing today only because Ubuntu's archive is most of why anyone wants this.

## Two things worth knowing that are not failures

`ps` shows around twenty processes rather than the several hundred the phone is running, because Android hides other applications' processes from you. What you see is your own guest, which is usually what you wanted anyway.

Installing a Python package that has no prebuilt aarch64 wheel makes `pip` compile it from source, and Ubuntu 26.04 ships Python 3.14, which is new enough that many projects have not published wheels for it yet. `psutil` is the example that bit us. When that happens, look for the Ubuntu package first, since `apt install python3-psutil` succeeds where `pip install psutil` spends several minutes and then fails. Note that installing it this way fixes the build and not the permission, so `psutil` still cannot read `/proc/stat`.

## Working out the answer for something we have not tried

Ask what the program needs from the kernel. If it wants files, processes, memory, the network or a terminal, it will almost certainly work, because those are the parts of Linux PRoot reproduces faithfully. If it wants processor utilisation, the load average, the system uptime, a raw socket, a namespace or a control group, expect it to fail, and expect the error to name the thing it could not have.

The quickest way to settle it is to install it and run it in Kern's own terminal. A one line error naming `/proc/stat` or a permission is the first rule or the second, and neither is something Kern can fix, because both come from the operating system underneath rather than from the environment Kern builds on top of it.

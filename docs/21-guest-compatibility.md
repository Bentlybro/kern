# What runs in the guest, and what does not

Kern gives you a real Ubuntu with a working `apt`, so the honest answer to "will this
program run" is "almost certainly yes". The exceptions are not random, and they are not
about PRoot being an incomplete Linux. They come from two specific restrictions, and once
you know both you can predict the answer for a program you have not tried yet.

Everything below was measured on a Galaxy Z Fold8 running Ubuntu 26.04 under Kern, not
inferred from documentation.

## The two rules

**The first rule is that `/proc` belongs to Android, not to the guest.** PRoot binds the
host's `/proc` into the rootfs, so a program that reads it is reading the phone's kernel
through the eyes of an untrusted application. Android's SELinux policy denies exactly three
files that matter here: `/proc/stat`, `/proc/loadavg` and `/proc/uptime`. Anything that
needs processor utilisation over time, or the load average, therefore fails. Anything that
needs the amount of memory, the processor model, the number of cores, or the guest's own
processes works, because those files are readable.

**The second rule is that the guest has no capabilities.** PRoot makes you look like root
and that is enough for `apt` and `dpkg`, which only ever check whether the user id is zero.
It is not enough for anything that asks the kernel for a real privilege. Raw sockets are the
common case, which is why `ping` fails while every other kind of networking is fine.

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
| Docker and any container runtime | Nothing to try | Namespaces and control groups are unavailable to an unprivileged application. This will not change. |

## Two things worth knowing that are not failures

`ps` shows around twenty processes rather than the several hundred the phone is running,
because Android hides other applications' processes from you. What you see is your own
guest, which is usually what you wanted anyway.

Installing a Python package that has no prebuilt aarch64 wheel makes `pip` compile it from
source, and Ubuntu 26.04 ships Python 3.14, which is new enough that many projects have not
published wheels for it yet. `psutil` is the example that bit us. When that happens, look
for the Ubuntu package first, since `apt install python3-psutil` succeeds where
`pip install psutil` spends several minutes and then fails. Note that installing it this way
fixes the build and not the permission, so `psutil` still cannot read `/proc/stat`.

## Working out the answer for something we have not tried

Ask what the program needs from the kernel. If it wants files, processes, memory, the
network or a terminal, it will almost certainly work, because those are the parts of Linux
PRoot reproduces faithfully. If it wants processor utilisation, the load average, the system
uptime, a raw socket, a namespace or a control group, expect it to fail, and expect the
error to name the thing it could not have.

The quickest way to settle it is to install it and run it in Kern's own terminal. A one line
error naming `/proc/stat` or a permission is the first rule or the second, and neither is
something Kern can fix, because both come from the operating system underneath rather than
from the environment Kern builds on top of it.

# Kern

**Kern is a real Linux IDE for Android.** It is not a remote client and not a toy editor: it is a complete Ubuntu development environment and the VS Code workbench, running entirely on the phone, in one app with nothing else to install.

```
Ubuntu 26.04 LTS · code-server 4.131 · git · tmux · python3 · ripgrep
```

Kern installs a real Ubuntu filesystem inside its own sandbox and runs it through PRoot. `apt install` works. Compilers, language servers and CLI agents are the ordinary glibc builds from the Ubuntu archive, not special mobile ports. There is no root, no Termux, and no server in a datacentre.

**Kern works, and it is pre-release.** Setup takes about two minutes to reach a usable editor. It has been verified end to end on device, so the numbers in this README are measured rather than estimated.

<p align="center">
  <img src="docs/images/editor.png" width="24%" alt="The VS Code workbench with a Python file open, syntax highlighted, file tree beside it">
  <img src="docs/images/terminal.png" width="24%" alt="A native terminal running python3 inside the Ubuntu guest">
  <img src="docs/images/projects.png" width="24%" alt="Creating a new project or cloning a repository">
  <img src="docs/images/status.png" width="24%" alt="Environment health checks with one-tap fixes">
</p>

<p align="center"><sub>The editor, the terminal, projects and health all run on the phone, with nothing remote.</sub></p>

---

## What it does

|  |  |
|---|---|
| **Editor** | Kern runs the real VS Code workbench (code-server) with its own chrome hidden. The web layer renders only the editor, and every surface around it is native Compose. |
| **Terminal** | The terminal opens genuine PTYs into the guest and draws them with a native terminal emulator, so there is no xterm.js and no WebView in the input path. You can run multiple shells in tabs, and each one opens in the current project. |
| **Linux** | The guest is Ubuntu 26.04 LTS, with a working `apt`, fake root, and the whole Ubuntu archive available. |
| **Projects** | You can create a project, `git clone` one, or open any folder, and Kern reads them natively from the guest filesystem. |
| **GitHub** | Signing in takes one tap and uses the device flow. Kern registers gh as git's credential helper, so `git push` then works everywhere: in the editor's Git panel, in the terminal, and in any agent. |
| **Adaptive** | Kern lays out for the screen it is on, whether that is a phone, a tablet, an unfolded foldable, tabletop posture (the editor above the crease and the terminal below) or desktop mode. |
| **Optional agent** | A cockpit runs whatever CLI coding agent you name, in a real terminal, alongside a diff and a one-thumb commit. Wanting no agent is a supported choice, and the diff half still earns its place. |
| **Input** | Kern adds a coding key row with sticky modifiers, explicit keyboard control, and hardware-keyboard chords. |
| **Health** | A status screen says what is wrong with the environment and gives you a button that fixes it rather than a command to copy. |

## How it works

The interesting problem is that Android forbids executing code from app-writable storage. Kern sidesteps that without root and without a hack:

1. **PRoot lives in `nativeLibraryDir`**, which is read-only and therefore exempt from W^X. Executing from there is Google's own documented alternative.
2. **Guest binaries are never handed to the kernel.** `PROOT_LOADER` points at a small static stub that `mmap`s the guest ELF `PROT_EXEC` and jumps to its entry point. That needs only the SELinux `file execute` permission, which apps retain, and never `execute_no_trans`, which was removed at targetSdk ≥ 29.
3. **PRoot fakes uid 0**, which is what makes `apt` and `dpkg` work at all.

The consequence is that Kern runs at **targetSdk 35** on a stock, unrooted device.

The full mechanism, including the flags that are load-bearing and the ways they fail when they are wrong, is in [docs/11-embedded-linux.md](docs/11-embedded-linux.md).

## Requirements

- Kern needs Android 10 or later on **arm64**.
- The Linux environment costs ~400 MB of download and ~1.2 GB of storage.
- The first run needs Wi-Fi.

Kern was developed against a Galaxy Z Fold8 and a Pixel. The adaptive layouts need a foldable to exercise properly; everything else is device-agnostic.

## What Kern will not do

- **Docker will never work here, and neither will any other container runtime.** PRoot fakes root, but it cannot create namespaces or cgroups, and an unprivileged Android app never gets to. This is permanent rather than a missing feature.
- **Kern runs on arm64 only.** There is no x86_64 build and no plan for one, because PRoot's ptrace interception is unproven under the binary translation an x86 Android device would need.
- **Guest processes do not survive a force-stop.** Everything in the Linux environment is a child of Kern's own process, so killing the app from the task switcher or from Settings kills the shell, the server and anything they were running. Android offers an app no way around this without root. Files on disk are safe; unsaved work and running jobs are not.

## Building

```
./gradlew assembleDebug      # → app/build/outputs/apk/debug/app-debug.apk
```

Building requires JDK 17 and the Android SDK with NDK, because there is a small JNI PTY implementation. The details are in [docs/09-building.md](docs/09-building.md).

## First run

Press **Set up Linux** once. Setup runs in two halves:

| Phase | What happens | Measured |
|---|---|---|
| 1 | Setup downloads the Ubuntu base image, unpacks it, configures apt and installs code-server. | **It reaches a usable editor in ~133s.** |
| 2 | Setup installs git, gh, tmux, curl, ripgrep and python3 *behind* the running IDE. | It completes in ~184s. |

You are in the editor while the second half runs, and a strip reports what is still installing. Kern shows live output throughout, so there are no unexplained multi-minute pauses.

## Documentation

Start at **[docs/README.md](docs/README.md)**, which indexes everything and says which documents describe the system as it is versus how it got here.

## Licence

Kern is licensed under GPLv3. It vendors Termux's terminal emulator and view, which are GPLv3, so the whole work is GPLv3. Attribution and third-party components are listed in [NOTICE.md](NOTICE.md).

## Acknowledgements

- **[Termux](https://github.com/termux/termux-app)** provides the terminal emulator and view, and the maintained Android-patched PRoot build this depends on.
- **[code-server](https://github.com/coder/code-server)** is VS Code as a server.
- **[Ubuntu](https://ubuntu.com/)** provides the base image and the archive behind `apt`.
- **[PRoot](https://proot-me.github.io/)** gives userspace `chroot` via `ptrace`.

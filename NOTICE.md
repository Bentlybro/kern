# Third-party notices

Kern is licensed under the **GNU General Public License v3.0 or later** (see `LICENSE`). It is GPL because it vendors GPLv3 source from Termux; that obligation is inherited deliberately and the full corresponding source of this app is published alongside every release.

Third-party material arrives here in three different ways, and they are kept apart because the obligations differ: source compiled into the app, prebuilt binaries shipped inside the APK, and software the app downloads onto the device during setup.

## Vendored source

### Termux (`app/src/main/java/com/termux/**`)

Kern vendors 21 Java files from [termux/termux-app](https://github.com/termux/termux-app), taken from the `terminal-emulator` and `terminal-view` modules (the terminal emulator, screen buffer, renderer, view, and text-selection UI). It also vendors two selection-handle drawables (`res/drawable/text_select_handle_*_material.xml`) and three strings (`res/values/termux_strings.xml`).

- The copyright is held by Fredrik Fornwall and the Termux contributors.
- The files are licensed under GPLv3.
- This project made the following modifications:
  - `TerminalSession.java` was **rewritten** by this project. It keeps the identical public API, but the shell is spawned on a pty owned by this app rather than by Termux's JNI, and the command is the bundled PRoot entering Kern's own Linux filesystem. The shell is therefore a child of this app's process, with no second app involved.
  - `JNI.java` was **deleted** and replaced by original code, namely `app/src/main/cpp/pty.c`, which CMake builds into `libkern_pty.so`. That file is this project's own work and is GPLv3 along with the rest of the app.
  - `textselection/*.java` had their `import com.termux.view.R` changed to `import dev.kern.app.R`, because the modules are compiled into this app rather than as separate library modules.

Every other vendored file is unmodified upstream code.

## Binaries distributed inside the APK

`app/src/main/jniLibs/arm64-v8a/` contains five prebuilt aarch64 shared objects. They are not built from source by this project; they are extracted from Termux's official aarch64 `.deb` packages, because Termux maintains a real build recipe and security process for the Android patches PRoot needs. `useLegacyPackaging = true` makes Android extract them as real files into `nativeLibraryDir`, which is the read-only directory PRoot must run from for the whole scheme to be legal under W^X. That mechanism is described in [docs/11-embedded-linux.md](docs/11-embedded-linux.md).

| File | Upstream | Version | License | Notes |
|---|---|---|---|---|
| `libproot.so` | [PRoot](https://proot-me.github.io/), from Termux's `proot` package | 5.1.107.89 | GPL-2.0-or-later | **This project modified this file**, as described below. |
| `libproot_loader.so` | same package, `libexec/proot/loader` | 5.1.107.89 | GPL-2.0-or-later | This is the stub that maps and enters guest ELFs. |
| `libproot_loader32.so` | same package, `libexec/proot/loader32` | 5.1.107.89 | GPL-2.0-or-later | This is the same stub, built for 32-bit guests. |
| `libtalloc.so` | [talloc](https://talloc.samba.org/) (Samba), from Termux's `libtalloc` package, file `libtalloc.so.2.4.3` | 2.4.3 | LGPL-3.0-or-later | PRoot depends on it. |
| `libandroid-shmem.so` | [termux/libandroid-shmem](https://github.com/termux/libandroid-shmem) | 0.7 | BSD-3-Clause | PRoot depends on it. |

`libproot.so` is **not** byte-identical to the file in Termux's `.deb`. Its `DT_NEEDED` entry said `libtalloc.so.2`, and Android only extracts files matching `lib*.so` into `nativeLibraryDir`, so a `.so.2` suffix is silently dropped and the load fails. The string was rewritten in place to `libtalloc.so`. That is the entire change, since no source was edited and nothing was recompiled. The corresponding source for all three PRoot binaries is Termux's build recipe at `packages/proot/` in [termux/termux-packages](https://github.com/termux/termux-packages) at version 5.1.107.89, plus this one-string ELF edit.

These are the SHA-256 digests of the files as committed, so that what ships can be checked against what is described here:

```
7da118895e971ea9fba4bb250b28af0f8db2edcbfdbaa8075cc645a0d7cf16fe  libproot.so
44ef39c1e1a18c09f6e4c4b5d6f8bba82d30596598bd155ec162d05c5122ff04  libproot_loader.so
25f6bd90bc5a3d3088026289a0d3eaf3e502bd2b00e5cb74fadd9791132efa34  libproot_loader32.so
3c9b207c0a6ea2896b7523e03f55d9ab0d9e88baa115d4c32b84058ff4246fbb  libtalloc.so
84475798e07c8174dbbfaec70a827fdb02f19ffa69a589380c13e7507fd0e731  libandroid-shmem.so
```

## Downloaded onto the device at setup (not distributed with this app)

First run fetches these with the user's explicit consent and installs them into the app's private Linux filesystem. None of them is in the APK, and uninstalling Kern removes them.

| Component | License | Role |
|---|---|---|
| [Ubuntu Base 26.04 LTS](https://cdimage.ubuntu.com/ubuntu-base/) (arm64) | per package; mostly GPL and permissive | This is the guest filesystem itself. |
| [code-server](https://github.com/coder/code-server) 4.131.0 (official arm64 `.deb`) | MIT | It hosts the VS Code workbench that the editor surface renders. |
| [Code - OSS](https://github.com/microsoft/vscode) | MIT | This is the workbench itself, reached through code-server. |
| git, gh, tmux, curl, ripgrep, python3, python3-pip, ca-certificates | various | These make up the starter toolchain, and they come from `ports.ubuntu.com`. |
| Anything the user installs later with `apt` | various | This project neither chooses nor redistributes these. |

**Deliberately not used:** Kern uses none of Microsoft's Visual Studio Marketplace, the proprietary VS Code Server binary, or Microsoft's product-locked extensions (Pylance, C/C++, C# Dev Kit, Remote-*). Extensions come from [Open VSX](https://open-vsx.org) instead. The full licensing map is in `docs/04-architecture.md`.

**Trademarks:** "Visual Studio Code" and "VS Code" are Microsoft trademarks. This project is not affiliated with, endorsed by, or derived from the branded Visual Studio Code product. "Termux" is the name of the Termux project, and "Ubuntu" is a registered trademark of Canonical Ltd, and this project is likewise unaffiliated with both.

## Android dependencies

Kern depends on Jetpack (androidx.core, activity-compose, lifecycle, window, compose-bom, material3) and on kotlinx-coroutines, all of which are under the Apache License 2.0.

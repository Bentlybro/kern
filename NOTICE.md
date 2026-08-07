# Third-party notices

Kern is licensed under the **GNU General Public License v3.0 or later** (see
`LICENSE`). It is GPL because it vendors GPLv3 source from Termux; that obligation is
inherited deliberately and the full corresponding source of this app is published
alongside every release.

Third-party material arrives here in three different ways, and they are kept apart
because the obligations differ: source compiled into the app, prebuilt binaries shipped
inside the APK, and software the app downloads onto the device during setup.

## Vendored source

### Termux (`app/src/main/java/com/termux/**`)

21 Java files from [termux/termux-app](https://github.com/termux/termux-app) —
the `terminal-emulator` and `terminal-view` modules (terminal emulator, screen buffer,
renderer, view, and text-selection UI), plus two selection-handle drawables
(`res/drawable/text_select_handle_*_material.xml`) and three strings
(`res/values/termux_strings.xml`).

- Copyright: Fredrik Fornwall and the Termux contributors.
- License: GPLv3.
- Modifications:
  - `TerminalSession.java` was **rewritten** by this project. It keeps the identical
    public API, but the shell is spawned on a pty owned by this app rather than by
    Termux's JNI, and the command is the bundled PRoot entering Kern's own Linux
    filesystem. The shell is therefore a child of this app's process, with no second
    app involved.
  - `JNI.java` was **deleted**, and replaced by original code — `app/src/main/cpp/pty.c`,
    built by CMake into `libkern_pty.so`. That file is this project's own work and is
    GPLv3 along with the rest of the app.
  - `textselection/*.java` had their `import com.termux.view.R` changed to
    `import dev.kern.app.R`, because the modules are compiled into this app rather
    than as separate library modules.

Every other vendored file is unmodified upstream code.

## Binaries distributed inside the APK

`app/src/main/jniLibs/arm64-v8a/` contains five prebuilt aarch64 shared objects. They are
not built from source by this project; they are extracted from Termux's official aarch64
`.deb` packages, because Termux maintains a real build recipe and security process for
the Android patches PRoot needs. `useLegacyPackaging = true` makes Android extract them as
real files into `nativeLibraryDir`, which is the read-only directory PRoot must run from
for the whole scheme to be legal under W^X — see
[docs/11-embedded-linux.md](docs/11-embedded-linux.md).

| File | Upstream | Version | License | Notes |
|---|---|---|---|---|
| `libproot.so` | [PRoot](https://proot-me.github.io/), from Termux's `proot` package | 5.1.107.89 | GPL-2.0-or-later | **Modified by this project** — see below |
| `libproot_loader.so` | same package, `libexec/proot/loader` | 5.1.107.89 | GPL-2.0-or-later | The stub that maps and enters guest ELFs |
| `libproot_loader32.so` | same package, `libexec/proot/loader32` | 5.1.107.89 | GPL-2.0-or-later | Same, for 32-bit guests |
| `libtalloc.so` | [talloc](https://talloc.samba.org/) (Samba), from Termux's `libtalloc` package, file `libtalloc.so.2.4.3` | 2.4.3 | LGPL-3.0-or-later | PRoot dependency |
| `libandroid-shmem.so` | [termux/libandroid-shmem](https://github.com/termux/libandroid-shmem) | 0.7 | BSD-3-Clause | PRoot dependency |

`libproot.so` is **not** byte-identical to the file in Termux's `.deb`. Its `DT_NEEDED`
entry said `libtalloc.so.2`, and Android only extracts files matching `lib*.so` into
`nativeLibraryDir`, so a `.so.2` suffix is silently dropped and the load fails. The string
was rewritten in place to `libtalloc.so`. That is the entire change: no source was edited
and nothing was recompiled. The corresponding source for all three PRoot binaries is
Termux's build recipe at `packages/proot/` in
[termux/termux-packages](https://github.com/termux/termux-packages) at version
5.1.107.89, plus this one-string ELF edit.

SHA-256 of the files as committed, so that what ships can be checked against what is
described here:

```
7da118895e971ea9fba4bb250b28af0f8db2edcbfdbaa8075cc645a0d7cf16fe  libproot.so
44ef39c1e1a18c09f6e4c4b5d6f8bba82d30596598bd155ec162d05c5122ff04  libproot_loader.so
25f6bd90bc5a3d3088026289a0d3eaf3e502bd2b00e5cb74fadd9791132efa34  libproot_loader32.so
3c9b207c0a6ea2896b7523e03f55d9ab0d9e88baa115d4c32b84058ff4246fbb  libtalloc.so
84475798e07c8174dbbfaec70a827fdb02f19ffa69a589380c13e7507fd0e731  libandroid-shmem.so
```

## Downloaded onto the device at setup (not distributed with this app)

First run fetches these with the user's explicit consent and installs them into the app's
private Linux filesystem. None of them is in the APK, and uninstalling Kern removes them.

| Component | License | Role |
|---|---|---|
| [Ubuntu Base 26.04 LTS](https://cdimage.ubuntu.com/ubuntu-base/) (arm64) | per package; mostly GPL and permissive | The guest filesystem itself |
| [code-server](https://github.com/coder/code-server) 4.131.0 (official arm64 `.deb`) | MIT | Hosts the VS Code workbench that the editor surface renders |
| [Code - OSS](https://github.com/microsoft/vscode) | MIT | The workbench itself, via code-server |
| git, gh, tmux, curl, ripgrep, python3, python3-pip, ca-certificates | various | The starter toolchain, from `ports.ubuntu.com` |
| Anything the user installs later with `apt` | various | Not chosen or redistributed by this project |

**Not used, deliberately:** Microsoft's Visual Studio Marketplace, the proprietary VS
Code Server binary, and Microsoft's product-locked extensions (Pylance, C/C++, C# Dev
Kit, Remote-*). Extensions come from [Open VSX](https://open-vsx.org) instead. See
`docs/04-architecture.md` for the full licensing map.

**Trademarks:** "Visual Studio Code" and "VS Code" are Microsoft trademarks. This project
is not affiliated with, endorsed by, or derived from the branded Visual Studio Code
product. "Termux" is the name of the Termux project, and "Ubuntu" is a registered
trademark of Canonical Ltd; likewise unaffiliated.

## Android dependencies

Jetpack (androidx.core, activity-compose, lifecycle, window, compose-bom, material3) and
kotlinx-coroutines — all Apache License 2.0.

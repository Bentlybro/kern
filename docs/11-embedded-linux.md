# 11 — Embedded Linux: dropping the Termux dependency

**Status: proven on device 2026-08-07.** A full Ubuntu LTS runs inside the app's own sandbox, `apt` works, and no second app is involved. We proved it first on 24.04 and now ship 26.04. The verification table below is from the original 24.04 run, and 26.04 was re-verified the same way.

This supersedes the staged plan in decision **D4**, which assumed that we would eventually build a Termux-prefix bootstrap. The PRoot route is better: it is self-contained, it gives us the *entire Ubuntu archive* instead of termux-packages, and — this was the surprise — it works at **targetSdk 35**, and would at 36.

## Why this is allowed at a modern targetSdk

Android forbids `execve()` of files in app-writable storage, which is the W^X rule. The scheme sidesteps it in two steps, neither of which is a hack.

1. **PRoot itself** lives in `applicationInfo.nativeLibraryDir`, which is read-only and therefore exempt. Exec'ing from there is the alternative Google itself documents.
2. **Guest binaries are never handed to the kernel.** `PROOT_LOADER` points at a tiny static stub, `libproot_loader.so`, which also lives in `nativeLibraryDir`. PRoot POKEs a load script into it, and the stub then `openat(O_RDONLY)`s the guest ELF, `mmap`s it `PROT_EXEC`, rewrites auxv and jumps to the entry point. Loading a binary that way needs only SELinux `file execute`, the dlopen-class permission that apps retain, and never `execute_no_trans`, the execve-class permission that was removed at targetSdk ≥ 29.

PRoot also fakes uid 0 through its `-0` flag, and that is what makes `apt` and `dpkg` possible without root.

## The native libraries

The libraries come from Termux's maintained, Android-patched proot build (**5.1.107.89**) rather than from prebuilt binaries of unknown provenance, because Termux has a real build recipe and a security process. We extracted them from the official aarch64 `.deb`s and placed them in `app/src/main/jniLibs/arm64-v8a/`.

| File | Source | Notes |
|---|---|---|
| `libproot.so` | `proot` | **Patched.** Its `DT_NEEDED` named `libtalloc.so.2`, and Android only extracts files matching `lib*.so` to `nativeLibraryDir`, so a `.so.2` suffix is silently dropped. We rewrote it in place to `libtalloc.so`. |
| `libproot_loader.so` | `libexec/proot/loader` | This is the stub described above. |
| `libproot_loader32.so` | `libexec/proot/loader32` | This one serves 32-bit guests. |
| `libtalloc.so` | `libtalloc.so.2.4.3` | proot depends on it. |
| `libandroid-shmem.so` | taken as-is | proot depends on it. |

`useLegacyPackaging = true` is required, because it makes Android extract these as real files rather than leaving them compressed inside the APK.

## The flags that matter

Each of these was verified by experiment rather than assumed.

- **`-l` (`--link2symlink`) is mandatory.** Without it the first `apt install` dies with `error creating hard link './usr/bin/perl5.38.2': Permission denied`, because SELinux forbids hard links in app storage and `dpkg` relies on them. A direct test confirms it:

      without -l →  ln: failed to create hard link: Permission denied with -l    →  HARDLINK_OK, link count 2

  The same applies to **extraction**, so the rootfs tarball is unpacked *through* PRoot by Android's own `/system/bin/tar` rather than by a Java tar implementation.
- **`PROOT_L2S_DIR` must live inside the rootfs, and be bound onto itself.** Those are two separate requirements, and both of them are load-bearing.

  PRoot implements `-l` by moving the real file into the l2s directory and pointing a symlink at it, and it writes that symlink's target as the **host** path. The directory therefore has to sit somewhere the guest can also reach, at *the same path*. Putting it outside the rootfs breaks it, and putting it inside is not enough on its own, because the guest's root is the rootfs and `/data/user/0/…` still means nothing there. That is what the self-bind is for:

      -b /data/user/0/dev.kern.app/files/linux/.l2s:/data/user/0/…/.l2s

  If either half is wrong, the failures are wildly misleading:

      wrong directory →  dpkg: error setting ownership of '/usr/bin/perl5.38.2.dpkg-new': No such file or directory   (about a file that is plainly there — chown follows the dangling symlink) no self-bind    →  ls: command not found

  The second is the nastier one, and Ubuntu 26.04 makes it fatal, because its coreutils is a single multi-call binary sitting behind ~115 hard links, so one dangling link removes every core utility at once. The guest boots and `bash` works, since its builtins are compiled in, but nothing else does.
- `-0` gives fake root, `-r <rootfs>` sets the new root, and `-w` sets the working directory.
- `PROOT_TMP_DIR` must be set, because the Termux build has Termux's prefix compiled in as its default temp path and warns `can't canonicalize /data/data/com.termux/...` without it.
- `LD_LIBRARY_PATH` must point at `nativeLibraryDir`, because proot's baked `RUNPATH` is a Termux path that does not exist for us.
- apt needs `APT::Sandbox::User "root"`, since it otherwise drops privileges to `_apt`, which cannot work under PRoot. It also needs `Acquire::http::Pipeline-Depth "0"` and `Acquire::PDiffs "false"`, because pipelining and PDiffs are the classic causes of apt hanging under PRoot.
- arm64 packages come from `ports.ubuntu.com`, **not** from `archive.ubuntu.com`.

## What was verified on device

| Step | Result |
|---|---|
| PRoot runs from `nativeLibraryDir` | ✅ It reports `PRoot 5.1.107.89` with `process_vm = yes, seccomp_filter = yes`. |
| Fake root | ✅ The guest reports `uid=0(root) gid=0(root)`. |
| Rootfs extraction (28 MB Ubuntu Base) | ✅ The full tree was extracted and the command exited 0. |
| Guest glibc binaries execute | ✅ The guest reports `Ubuntu 24.04.4 LTS` on `aarch64`, with 294 binaries in `/usr/bin`. |
| `apt-get update` | ✅ It fetched 35.5 MB of lists in 8s. |
| `apt-get install git` (dpkg, triggers, ldconfig) | ✅ The installed git reports `git version 2.43.0`. |

`seccomp_filter = yes` matters for the performance worry, because PRoot uses seccomp so that most syscalls are not ptrace-trapped. Do **not** set `PROOT_NO_SECCOMP=1` in production without measuring first, since it forces the slow path.

## What this changes

- **Everything is `apt install`.** node, python, clang, git, ripgrep and the language servers (pyright, clangd, rust-analyzer, gopls) all come from the Ubuntu archive as glibc builds, which is the form upstream actually ships and tests, unlike Termux's bionic rebuilds.
- **The terminal gets simpler.** With a local pty we delete the socat bridge on 13338, the resize control port on 13339, and the terminal's `AUTH` token. Our vendored `TerminalSession` reverts *closer to upstream Termux*, since upstream was always pty-based, and only the transport changes back.
- **code-server still needs its token.** It remains a localhost HTTP server, so risk R16 stays closed by the existing `--auth password` mechanism.

## Performance — measured, 2026-08-07

The ptrace tax was the largest open risk in this design, so we measured it rather than arguing about it. The honest comparison is the **same file tree walked twice**: once by Android's own `find` with no PRoot, and once inside the guest. The storage is the same and the 14,761 files are the same, so the difference is the tax and nothing else.

| Measurement | Result |
|---|---|
| Syscall storm, native | 1235 ms |
| Syscall storm, under PRoot | 1466 ms — **1.19×** |
| PRoot process launch | ~285 ms |
| python, 5M-iteration loop (pure CPU) | 471 ms |
| python interpreter start (mmap/openat heavy) | 158 ms |
| `git status` / `git log` on a small repo | 74 ms / 46 ms |
| ripgrep over `/usr/include` | 80 ms |

**The tax is about 19% on syscall-heavy work, and nothing on CPU.** That is far better than the 2–5× that ptrace usually implies, because `seccomp_filter = yes` keeps most syscalls off the traced path. This is the concrete reason not to set `PROOT_NO_SECCOMP=1`.

The cost that does matter is the ~285 ms it takes to start a PRoot. That lands on *our* short-lived `LinuxRuntime.run` calls rather than on the user's builds, and it is why anything long-running — code-server, the agent — is held open rather than re-entered per command.

## Process survival — partly answered

With the app backgrounded and the screen off for four minutes, guest processes went from 71 to 80 and code-server kept its listening sockets. Nothing was killed.

**This is a best case, not a verdict.** The test device has "disable child process restrictions" turned on in developer options, so the result says nothing about a stock phone, where the 32-process phantom killer applies, and 71 processes is already well past that threshold. Re-testing on an untouched device is still outstanding, and the status screen continues to warn about it.

## First-run cost — answered

Setup downloads about 400 MB and leaves about 1.2 GB installed, with a usable editor at **133 s** and the full toolchain at 184 s. See [12 — Setup](12-setup.md) for the detail.

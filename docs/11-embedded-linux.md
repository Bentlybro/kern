# 11 — Embedded Linux: dropping the Termux dependency

**Status: proven on device 2026-08-07.** A full Ubuntu 24.04.4 LTS runs inside the app's
own sandbox, with `apt` working, and no second app involved.

This supersedes the staged plan in decision **D4** (which assumed we would eventually
build a Termux-prefix bootstrap). The PRoot route is better: it is self-contained, gives
us the *entire Ubuntu archive* instead of termux-packages, and — the surprise — works at
**targetSdk 36**, which also reopens Play Store distribution that decision D5 had ruled
out.

## Why this is allowed at targetSdk 36

Android forbids `execve()` of files in app-writable storage (W^X). The scheme sidesteps
it in two steps, neither of which is a hack:

1. **PRoot itself** lives in `applicationInfo.nativeLibraryDir`, which is read-only and
   therefore exempt. Exec'ing from there is Google's own documented alternative.
2. **Guest binaries are never handed to the kernel.** `PROOT_LOADER` points at a tiny
   static stub (`libproot_loader.so`, also in `nativeLibraryDir`). PRoot POKEs a load
   script into it; the stub `openat(O_RDONLY)`s the guest ELF and `mmap`s it `PROT_EXEC`,
   then rewrites auxv and jumps to the entry point. Loading that way needs only SELinux
   `file execute` (the dlopen-class permission, which apps retain), never
   `execute_no_trans` (the execve-class permission, removed at targetSdk ≥ 29).

PRoot also fakes uid 0 (`-0`), which is what makes `apt`/`dpkg` possible without root.

## The native libraries

Taken from Termux's maintained, Android-patched proot build (**5.1.107.89**) rather than
from prebuilt binaries of unknown provenance — Termux has a real build recipe and
security process. Extracted from the official aarch64 `.deb`s and placed in
`app/src/main/jniLibs/arm64-v8a/`:

| File | Source | Notes |
|---|---|---|
| `libproot.so` | `proot` | **Patched**: its `DT_NEEDED` said `libtalloc.so.2`, and Android only extracts files matching `lib*.so` to `nativeLibraryDir` — a `.so.2` suffix is silently dropped. Rewritten in place to `libtalloc.so`. |
| `libproot_loader.so` | `libexec/proot/loader` | The stub described above |
| `libproot_loader32.so` | `libexec/proot/loader32` | 32-bit guests |
| `libtalloc.so` | `libtalloc.so.2.4.3` | proot dependency |
| `libandroid-shmem.so` | as-is | proot dependency |

`useLegacyPackaging = true` is required so these are extracted as real files rather than
left compressed inside the APK.

## The flags that matter

Verified by experiment, not assumption:

- **`-l` (`--link2symlink`) is mandatory.** Without it the first `apt install` dies with
  `error creating hard link './usr/bin/perl5.38.2': Permission denied`, because SELinux
  forbids hard links in app storage and `dpkg` relies on them. Direct test:

      without -l →  ln: failed to create hard link: Permission denied
      with -l    →  HARDLINK_OK, link count 2

  This also applies to **extraction**, so the rootfs tarball is unpacked *through* PRoot
  (using Android's own `/system/bin/tar`) rather than by a Java tar implementation.
- **`PROOT_L2S_DIR` must live inside the rootfs, and be bound onto itself.** This is two
  separate requirements and both are load-bearing.

  PRoot implements `-l` by moving the real file into the l2s directory and pointing a
  symlink at it — and it writes that symlink's target as the **host** path. So the
  directory has to be somewhere the guest can also reach, at *the same path*. Putting it
  outside the rootfs breaks it; putting it inside is not enough on its own, because the
  guest's root is the rootfs and `/data/user/0/…` still means nothing there. Hence the
  self-bind:

      -b /data/user/0/dev.foldcode.app/files/linux/.l2s:/data/user/0/…/.l2s

  Get either half wrong and the failures are wildly misleading:

      wrong directory →  dpkg: error setting ownership of '/usr/bin/perl5.38.2.dpkg-new':
                         No such file or directory   (about a file that is plainly there —
                         chown follows the dangling symlink)
      no self-bind    →  ls: command not found

  The second is the nastier one, and Ubuntu 26.04 makes it fatal: its coreutils is a
  single multi-call binary sitting behind ~115 hard links, so one dangling link removes
  every core utility simultaneously. The guest boots, `bash` works (its builtins are
  compiled in), and nothing else does.
- `-0` fake root, `-r <rootfs>` new root, `-w` working directory.
- `PROOT_TMP_DIR` must be set: the Termux build has Termux's prefix compiled in as its
  default temp path and warns `can't canonicalize /data/data/com.termux/...` without it.
- `LD_LIBRARY_PATH` must point at `nativeLibraryDir`; proot's baked `RUNPATH` is a Termux
  path that does not exist for us.
- apt needs `APT::Sandbox::User "root"` (it otherwise drops privileges to `_apt`, which
  cannot work under PRoot) plus `Acquire::http::Pipeline-Depth "0"` and
  `Acquire::PDiffs "false"`, which are the classic causes of apt hanging under PRoot.
- arm64 packages come from `ports.ubuntu.com`, **not** `archive.ubuntu.com`.

## What was verified on device

| Step | Result |
|---|---|
| PRoot runs from `nativeLibraryDir` | ✅ `PRoot 5.1.107.89`, `process_vm = yes, seccomp_filter = yes` |
| Fake root | ✅ `uid=0(root) gid=0(root)` |
| Rootfs extraction (28 MB Ubuntu Base) | ✅ full tree, exit 0 |
| Guest glibc binaries execute | ✅ `Ubuntu 24.04.4 LTS`, `aarch64`, 294 binaries in `/usr/bin` |
| `apt-get update` | ✅ 35.5 MB of lists in 8s |
| `apt-get install git` (dpkg, triggers, ldconfig) | ✅ `git version 2.43.0` |

`seccomp_filter = yes` matters for the performance worry: PRoot uses seccomp so most
syscalls are not ptrace-trapped. Do **not** set `PROOT_NO_SECCOMP=1` in production
without measuring — it forces the slow path.

## What this changes

- **Everything is `apt install`.** node, python, clang, git, ripgrep, and language servers
  (pyright, clangd, rust-analyzer, gopls) come from the Ubuntu archive as glibc builds —
  the form upstream actually ships and tests, unlike Termux's bionic rebuilds.
- **The terminal gets simpler.** With a local pty we delete the socat bridge (13338), the
  resize control port (13339), and the terminal's `AUTH` token. Our vendored
  `TerminalSession` reverts *closer to upstream Termux*, since upstream was always
  pty-based; only the transport changes back.
- **code-server still needs its token** — it remains a localhost HTTP server, so risk R16
  stays closed by the existing `--auth password` mechanism.

## Open questions before committing

1. **Performance.** PRoot's ptrace tax on syscall-heavy work (npm install, compilers,
   language servers). Must be measured against the current Termux-native setup rather
   than argued about.
2. **Process survival.** Guest processes are children of *our* app, so Android killing us
   kills the session — Termux at least had its own foreground service. tmux inside the
   guest plus our existing foreground service is the likely answer.
3. **First-run cost.** ~28 MB download, plus apt on top; several minutes and 1–2 GB of
   storage before the IDE is usable.

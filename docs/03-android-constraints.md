# 03 — Android Constraints: The Hard Rules

This is the make-or-break chapter, because every dead mobile IDE died here. The sources and the caveats are in [research/runtime-exec.md](research/runtime-exec.md).

## 1. Executing binaries (W^X)

Since Android 10, apps targeting API ≥ 29 **cannot `exec()` binaries from writable app storage**, because SELinux enforces W^X. There are two proven ways around it, and shipping apps use both of them in 2026.

| Path | How | Who uses it | Trade-off |
|---|---|---|---|
| **targetSdk 28** | The exemption is keyed to targetSdk rather than to the OS version, so it still works on Android 16/17, and the installable floor is only targetSdk 24 | The Termux F-Droid build and AndroidIDE | It confines us to sideloading and F-Droid, since Play requires API 36+, but it is conservative and battle-tested |
| **system_linker_exec** | The app calls `exec("/system/bin/linker64", "/path/to/bin", …)`, and the system linker loads the ELF because the linker itself is exempt; termux-exec wraps this transparently via LD_PRELOAD | The **official Termux Play build** (v0.120) | It allows a modern targetSdk, but `/proc/self/exe` lies, and it is an unblessed hole that Google *could* close, in which case the fallback is targetSdk 28 |

There is a third path, Play-compliant but limited: ship the binaries as `lib*.so` inside the APK (`extractNativeLibs=true`) and exec them from the read-only `nativeLibraryDir`, which is how Pydroid and Cxxdroid live on Play. The binaries are frozen at APK build time, so the user can install no packages of their own. It is useful for a bootstrap seed, not for the full story.

**Decision (D2):** we do a Termux-style bootstrap of termux-packages aarch64 binaries into app-private storage and exec them via **system_linker_exec** with a modern targetSdk, keeping a targetSdk-28 build variant as the fallback lever. We verify this on One UI 9 in M0.

## 2. Process lifetime — the #1 stability threat

- The **phantom process killer** (Android 12+) enforces a system-wide cap of **32 child processes**, and a process that exceeds the cap or uses "excessive CPU while background" is SIGKILLed (`signal 9`). This is the Termux wound that kills builds and servers.
  - On Android 14+ there is a Developer options toggle, **"Disable child process restrictions"**.
  - The onboarding wizard must walk the user through that toggle once, along with the battery-optimisation exemption and removing the app from Samsung's "put to sleep" lists, since Samsung is the most aggressive OEM killer.
  - The architecture must ALSO keep the child count down: one Node process for code-server, a few ptys and the language servers come to well under 32, and the app warns when the count nears the cap.
- The **foreground service** uses the `specialUse` FGS type plus a partial wakelock while a session is active. We avoid the dataSync and mediaProcessing types because they carry 6h and 24h caps on Android 15+.
- **One UI pins background work to the little cores.** Backgrounded Termux gets pinned to the efficiency cores on recent One UI *even with* unrestricted battery and an FGS, which is open Termux issue #5086. There is no workaround except staying visible. We mitigate it with split-screen usage patterns, an in-app "keep screen on while building" option, and honest UX in the form of progress notifications and resume.
- **Sessions have to survive.** We run the server and the shells under a tmux-style session layer, so that when Android wins anyway the user reattaches and never loses work. Cosyra's entire sales pitch is escaping this; we solve it locally.

## 3. Storage

- **Projects live in app-private storage** (`getFilesDir()`) with direct POSIX paths, exactly like Termux's `$HOME`. git on SAF is impractical, since it costs a ContentProvider IPC per operation and ~2× on random I/O, so we never put repos behind SAF.
- For interop we expose our project tree to other apps through a **DocumentsProvider**, and the user imports work by SAF copy or by git clone.
- An optional power-user mode grants `MANAGE_EXTERNAL_STORAGE` for `/sdcard`-wide access, which is banned-ish on Play but fine for a sideloaded build.
- **Backup is on us**, because app-private data dies with the uninstall. We provide built-in git push plus an optional folder export or sync to `/sdcard` or the cloud.

## 4. Distribution

- **Google Play is a no** for v1. New apps must target API 36 (Aug 2026) and 16KB pages, and the full local-toolchain model fights Play policy forever. The linker64 trick makes Play *technically* possible — Termux did get a Play build approved — but it is not worth the v1 constraint tax.
- **Sideloading and GitHub Releases are a yes.** Note that Google's **developer verification** regime starts Sept 30, 2026, covering BR/ID/SG/TH first and going global in 2027. A US device today is unaffected on a v1 timescale. Escape hatches exist, namely ADB install and the free 20-device dev tier, and we register a verified dev identity when it becomes relevant. F-Droid's position under verification is contested, so we watch it and do not depend on it.
- **The 16KB page size cuts two ways.** Play requires 16KB-compatible native code, and more importantly, if the Fold8 kernel runs 16KB pages then *termux-packages binaries that aren't 16KB-ready won't run at all*. **The M0 spike is to check `getconf PAGESIZE` on the Fold8 first thing.** If it is 16KB, we pin to Termux's 16KB-ready package track when it lands, or build the affected packages ourselves.

## 5. WebView / UI constraints

- The workbench runs in a WebView over `http://127.0.0.1`, which needs `usesCleartextTraffic` scoped to localhost (or a `network_security_config` domain rule), `setAllowFileAccess` turned off, and service worker support (WebView 105+, and the Fold8 ships far newer).
- Android WebViews deliberately limit raw key events, so hardware-keyboard chords, Tab traversal and IME-consumed keys need native `dispatchKeyEvent` and `dispatchKeyShortcutEvent` interception *before* the WebView sees them, forwarded over a JS bridge. This is a core subsystem, not a patch (docs/05).
- code-server treats `process.platform === 'android'` as unsupported and therefore gates extension installs to web-only ones. The known workaround is a platform spoof via a `node --require` shim, or carrying a patch. We budget for maintaining it.

## 6. Battery & thermals

- Sustained compiles throttle after ~15 min and drain the battery fast. In DeX dev-machine tests an npm install ran ~3× slower than on an M2 Air, which is fine for scripting and a real but bounded cost for builds. The UX has to set that expectation: quick iteration happens on-device, while heavy builds go to remote SSH or take patience.
- Docker will never work here, because apps get no kernel namespaces or cgroups. proot-distro covers the glibc needs at 3–8% CPU, rising to up to ~40% overhead on syscall-heavy work, so we prefer native termux-packages and reserve proot for the long tail.

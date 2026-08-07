# 03 — Android Constraints: The Hard Rules

This is the make-or-break chapter. Every dead mobile IDE died here. Sources and caveats:
[research/runtime-exec.md](research/runtime-exec.md).

## 1. Executing binaries (W^X)

Since Android 10, apps targeting API ≥ 29 **cannot `exec()` binaries from writable app
storage** (SELinux W^X). Two proven ways around it, both used by shipping apps in 2026:

| Path | How | Who uses it | Trade-off |
|---|---|---|---|
| **targetSdk 28** | Exemption is keyed to targetSdk, not OS version; still works on Android 16/17; installable floor is only targetSdk 24 | Termux F-Droid, AndroidIDE | Sideload/F-Droid only (Play requires API 36+); conservative, battle-tested |
| **system_linker_exec** | `exec("/system/bin/linker64", "/path/to/bin", …)` — the system linker loads the ELF and is exempt; wrapped transparently via LD_PRELOAD (termux-exec) | **Official Termux Play build** (v0.120) | Modern targetSdk allowed; `/proc/self/exe` lies; unblessed hole Google *could* close (fallback = targetSdk 28) |

A third, Play-compliant-but-limited path: ship binaries as `lib*.so` in the APK
(`extractNativeLibs=true`) and exec from the read-only `nativeLibraryDir` — how
Pydroid/Cxxdroid live on Play. Binaries are frozen at APK build time, so no user package
installs. Useful for a bootstrap seed, not for the full story.

**Decision (D2):** Termux-style bootstrap of termux-packages aarch64 binaries in
app-private storage, exec'd via **system_linker_exec** with modern targetSdk;
targetSdk-28 build variant as the fallback lever. Verify on One UI 9 in M0.

## 2. Process lifetime — the #1 stability threat

- **Phantom process killer** (Android 12+): system-wide cap of **32 child processes**;
  over the cap or "excessive CPU while background" → SIGKILL (`signal 9`). This is the
  Termux wound that kills builds and servers.
  - Android 14+: Developer options → **"Disable child process restrictions"** toggle.
  - Onboarding wizard must walk the user through this once (plus battery-optimization
    exemption and removing the app from Samsung's "put to sleep" lists — Samsung is the
    most aggressive OEM killer).
  - Architecture must ALSO minimize child count: one Node (code-server) + a few ptys +
    language servers ≈ well under 32; warn in-app when nearing the cap.
- **Foreground service**: `specialUse` FGS type + partial wakelock while a session is
  active (dataSync/mediaProcessing types have 6h/24h caps on Android 15+ — avoid).
- **One UI little-core pinning**: backgrounded Termux gets pinned to efficiency cores on
  recent One UI *even with* unrestricted battery + FGS (open Termux issue #5086). No
  workaround except staying visible. Mitigations: split-screen usage patterns, in-app
  "keep screen on while building" option, and honest UX (progress notifications, resume).
- **Session survivability**: run the server + shells under a tmux-style session layer so
  when Android wins anyway, reattach — never lose work. (Cosyra's entire sales pitch is
  escaping this; we solve it locally.)

## 3. Storage

- **Projects live in app-private storage** (`getFilesDir()`) with direct POSIX paths —
  exactly like Termux's `$HOME`. git on SAF is impractical (ContentProvider IPC per op,
  ~2× random I/O); never put repos behind SAF.
- Interop: expose our project tree to other apps via a **DocumentsProvider**; import via
  SAF copy or git clone.
- Optional power-user mode: `MANAGE_EXTERNAL_STORAGE` for `/sdcard`-wide access —
  banned-ish on Play, fine for sideload.
- **Backup is on us**: app-private data dies with uninstall. Built-in git push +
  optional folder export/sync to `/sdcard` or cloud.

## 4. Distribution

- **Google Play: no** (v1). New apps must target API 36 (Aug 2026) and 16KB pages; the
  full local-toolchain model fights Play policy forever. (The linker64 trick makes Play
  *technically* possible — Termux got a Play build approved — but it's not worth the v1
  constraint tax.)
- **Sideload / GitHub Releases: yes.** Note: Google's **developer verification** regime
  starts Sept 30, 2026 (BR/ID/SG/TH first, global 2027). US device today: unaffected at
  v1 timescale. Escape hatches exist (ADB install, free 20-device dev tier); register a
  verified dev identity when it becomes relevant. F-Droid's position under verification
  is contested — watch, don't depend.
- **16KB page size**: Play requires 16KB-compatible native code; more importantly, if
  the Fold8 kernel runs 16KB pages, *termux-packages binaries that aren't 16KB-ready
  won't run at all*. **M0 spike: check `getconf PAGESIZE` on the Fold8 first thing.**
  (If 16KB: pin to Termux's 16KB-ready package track when it lands, or build affected
  packages ourselves.)

## 5. WebView / UI constraints

- The workbench runs in a WebView over `http://127.0.0.1` — needs
  `usesCleartextTraffic` scoped to localhost (or a `network_security_config` domain
  rule), `setAllowFileAccess` off, service worker support (WebView 105+; Fold8 ships far
  newer).
- Android WebViews deliberately limit raw key events: hardware-keyboard chords, Tab
  traversal, and IME-consumed keys need native `dispatchKeyEvent` /
  `dispatchKeyShortcutEvent` interception *before* the WebView, forwarded over a JS
  bridge. This is a core subsystem, not a patch (docs/05).
- code-server treats `process.platform === 'android'` as unsupported → gates extension
  installs to web-only. Known workaround: platform spoof via `node --require` shim (or
  carry a patch). Budget for maintaining it.

## 6. Battery & thermals

- Sustained compiles throttle after ~15 min and drain fast (DeX dev-machine tests:
  npm install ~3× slower than an M2 Air; fine for scripting, real but bounded for
  builds). Set expectations in UX: quick iteration on-device, heavy builds → remote SSH
  or patience.
- No Docker, ever (kernel namespaces/cgroups absent for apps). proot-distro covers
  glibc needs at 3–8% CPU / up to ~40% syscall-heavy overhead; prefer native
  termux-packages, reserve proot for the long tail.

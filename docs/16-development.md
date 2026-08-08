# 16 — Development

## The device harness

`tools/device.ps1` is the single tested entry point for talking to a phone. It replaced a pile of ad-hoc `adb` one-liners, and exists because the one-liners kept being subtly wrong in ways that wasted time.

```
.\tools\device.ps1 deploy              # build, install, restart
.\tools\device.ps1 shot setup          # screenshot -> tools/shots/setup.png
.\tools\device.ps1 logs 30
.\tools\device.ps1 status
.\tools\device.ps1 guest "ls -la /root"
.\tools\device.ps1 tap 540 2140
.\tools\device.ps1 reset               # uninstall, for a clean first-run test
```

**The rule is that it only ever reads from the device and writes to the scratch folder, and that it must never write into `app/src`.** Source edits belong in an editor rather than in a shell, and that rule exists because a shell script once truncated five source files to zero bytes.

### Running commands in the guest

`guest` is the most useful command and the fiddliest. The rootfs is app-private, so it goes through `run-as` to borrow the app's uid, and the command travels as **base64** because written literally it would cross five levels of quoting — PowerShell, adb, sh, `run-as`, bash — and any quote or `$` would be mangled on the way.

It mirrors the app's PRoot invocation exactly, including the `.l2s` self-bind. Without that bind the guest's coreutils symlinks dangle and even `ls` is missing, so a harness that omitted it would report failures the app does not have.

`KERN_ROOTFS` overrides which rootfs the harness enters, so you can exercise a candidate distro side by side with the installed one.

### PowerShell traps worth knowing

Three separate bugs in this harness had the same root cause: **PowerShell claims arguments that begin with a dash**.

```
Invoke-Adb shell screencap -p /sdcard/x.png     # -p is eaten; screencap writes nothing
adb logcat -d ...                               # -d is eaten; logcat tails forever
```

Pass such commands as a single quoted string, or build the argument list as an array.

The other trap is native stderr. PowerShell 5.1 wraps every stderr line in an `ErrorRecord`, so under `ErrorActionPreference = 'Stop'` a harmless javac deprecation note aborts the whole script even though the build succeeded. The `build` command relaxes that for the build only.

And for multi-line git commit messages, write the message to a file and use `git commit -F`. A here-string passed to `-m` is mangled the moment it contains a quote, and the failure looks like a wall of unrelated pathspec errors.

## Testing on a real device

Most of this project's bugs were only visible on hardware, so the loop goes like this:

1. Run `deploy`.
2. Drive the UI with `tap`, `swipe`, `text` and `key`.
3. Take a `shot` and **look at the screenshot**, because several bugs were invisible in the logs and obvious on screen.
4. Run `guest` to check what actually happened inside Linux.
5. Read `logs` for the app's own view.

Screenshots land in `tools/shots/`, which is git-ignored. They are debugging output rather than artefacts.

## Building

Building Kern needs JDK 17 and the Android SDK with the NDK, because there is a small JNI PTY in `app/src/main/cpp`.

```
./gradlew assembleDebug
```

`useLegacyPackaging = true` is required so the PRoot native libraries are extracted as real files rather than left compressed inside the APK. See [09 — Building](09-building.md).

## Working on the guest

The rootfs sits at `files/linux` inside app storage, where `run-as` can read it. Because it is ordinary app-private storage, Kotlin can read anything the guest writes directly, which is how command output, the setup log and the GitHub device code all reach the UI without pipes.

## Code conventions

- Comments explain **why** something is there. If a line looks strange, the comment says what breaks without it.
- Comments quote misleading failure modes verbatim. `dpkg: error setting ownership of '/usr/bin/perl5.38.2.dpkg-new': No such file or directory` is about a file that is plainly there, and the comment says so.
- Anything described as verified has a device behind it.

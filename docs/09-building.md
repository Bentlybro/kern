# 09 — Building, Installing, Running

## Build

Requirements (all present on the dev machine as of 2026-08-06): JDK 17, Android SDK with
platform 36, Gradle wrapper (checked in).

```powershell
$env:JAVA_HOME = "C:\Program Files\Java\jdk-17"
cd "F:\Desktop\desktop misc\python coding\Mobile-IDE"
.\gradlew.bat assembleDebug
```

Output: `app\build\outputs\apk\debug\app-debug.apk` (~28 MB).

## Install

**On the Fold8 (or any phone) by hand:** copy the APK over and tap it (allow install from
unknown sources once).

**Over adb:**
```powershell
C:\adb\adb.exe install -r "app\build\outputs\apk\debug\app-debug.apk"
```

**Lab Pixel over wireless adb** (Conduit pins adb to 5555):
```powershell
C:\adb\adb.exe connect 192.168.0.233:5555
C:\adb\adb.exe -s 192.168.0.233:5555 install -r "...\app-debug.apk"
```

## First run (companion mode, per D4 stage 1)

The in-app setup screen walks through this, but for reference:

1. **Termux installed** — F-Droid, GitHub releases, or the Play build.
2. **Termux configured + code-server installed.** The app's "Copy command" button copies:
   ```sh
   mkdir -p ~/.termux && { grep -q '^allow-external-apps' ~/.termux/termux.properties 2>/dev/null && sed -i 's/^allow-external-apps.*/allow-external-apps = true/' ~/.termux/termux.properties || echo 'allow-external-apps = true' >> ~/.termux/termux.properties; } && termux-reload-settings; pkg install -y tur-repo && pkg install -y code-server
   ```
   Note a fresh Termux ships `allow-external-apps` **commented out**, so the append branch
   is the normal path.
3. **Grant `com.termux.permission.RUN_COMMAND`** (in-app button; over adb:
   `adb shell pm grant dev.kern.app com.termux.permission.RUN_COMMAND`).
4. **Battery exemption**, plus once in Developer options: *Disable child process
   restrictions*.
5. **Start IDE.**

## What happens when you press Start IDE

1. `SessionService` starts as a `specialUse` foreground service and takes a partial
   wakelock.
2. It sends Termux a `RUN_COMMAND` intent running an idempotent script that launches
   `code-server --auth none --bind-addr 127.0.0.1:13337` under `nohup` (skipped if
   already listening).
3. It polls `http://127.0.0.1:13337/healthz` until healthy (60 s budget), then every
   15 s. Two consecutive failures → restart the server, showing a "reconnecting" overlay.
4. The UI switches to `IdeScreen`: a WebView on
   `http://127.0.0.1:13337/?folder=/data/data/com.termux/files/home` with the key row
   docked below it.

Server log on the device: `~/.kern/server.log` in Termux.

## Debugging

```powershell
# App logs
C:\adb\adb.exe logcat -s Kern:* AndroidRuntime:E

# Inspect the workbench WebView from desktop Chrome (debug builds only):
#   chrome://inspect  →  the device appears when the IDE screen is open

# Is the server actually up, from the phone's own shell?
C:\adb\adb.exe shell curl -sS -o /dev/null -w "%{http_code}" http://127.0.0.1:13337/healthz
```

## Validated environment (lab Pixel, 2026-08-06)

A fresh Termux (0.119.0-beta.3, targetSdk 28 — confirming the exec-exemption model from
docs/03) plus the setup command above produced a working stack: **code-server 4.131.0,
Node 24.18, Python 3.14.6, clang 21.1.8, ripgrep 15.2, llvm, make**. Page size 4096.

Note a fresh Termux ships `allow-external-apps` **commented out**, and `RUN_COMMAND` is
rejected without it — the app's setup command handles this, but it is the first thing to
check if Start IDE reports a failure.

## Known limitations in M1

- `--auth none` on `127.0.0.1:13337` means *any app on the device* can reach the IDE
  server while a session runs. Acceptable for a personal sideloaded build; M2 replaces it
  with a generated password auto-submitted through the WebView.
- Leaving and re-entering the app recreates the WebView, so the workbench reloads
  (a few seconds). WebView retention across activity recreation is an M2 task.
- The key row's symbol strip is fixed; user-configurable layouts come in M2.
- No project picker yet — the workbench opens Termux's home directory; use its own
  File → Open Folder.

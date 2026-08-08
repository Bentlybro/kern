# 09 — Building, Installing, Running

## Build

The build needs JDK 17, an Android SDK with platform 36, and the Gradle wrapper, which is checked in. All three were present on the dev machine as of 2026-08-06.

```powershell
$env:JAVA_HOME = "C:\Program Files\Java\jdk-17"
cd "F:\Desktop\desktop misc\python coding\Mobile-IDE"
.\gradlew.bat assembleDebug
```

The build writes `app\build\outputs\apk\debug\app-debug.apk`, which is about 28 MB.

## Install

**By hand on the Fold8, or on any other phone:** copy the APK across and tap it, and allow installation from unknown sources the one time Android asks.

**Over adb,** a single command installs it.
```powershell
C:\adb\adb.exe install -r "app\build\outputs\apk\debug\app-debug.apk"
```

**The lab Pixel is reached over wireless adb,** and Conduit pins adb to port 5555.
```powershell
C:\adb\adb.exe connect 192.168.0.233:5555
C:\adb\adb.exe -s 192.168.0.233:5555 install -r "...\app-debug.apk"
```

## First run (companion mode, per D4 stage 1)

The in-app setup screen walks the user through all of this, but the steps are recorded here for reference.

1. **Install Termux.** It comes from F-Droid, from the GitHub releases, or as the Play build.
2. **Configure Termux and install code-server.** The app's "Copy command" button copies this:
   ```sh
   mkdir -p ~/.termux && { grep -q '^allow-external-apps' ~/.termux/termux.properties 2>/dev/null && sed -i 's/^allow-external-apps.*/allow-external-apps = true/' ~/.termux/termux.properties || echo 'allow-external-apps = true' >> ~/.termux/termux.properties; } && termux-reload-settings; pkg install -y tur-repo && pkg install -y code-server
   ```
   A fresh Termux ships `allow-external-apps` **commented out**, so the append branch is the one that normally runs.
3. **Grant `com.termux.permission.RUN_COMMAND`.** There is a button in the app for this, and over adb the command is `adb shell pm grant dev.kern.app com.termux.permission.RUN_COMMAND`.
4. **Grant the battery exemption**, and turn on *Disable child process restrictions* once in Developer options.
5. **Press Start IDE.**

## What happens when you press Start IDE

1. `SessionService` starts as a `specialUse` foreground service and takes a partial wakelock.
2. It sends Termux a `RUN_COMMAND` intent that runs an idempotent script, and that script launches `code-server --auth none --bind-addr 127.0.0.1:13337` under `nohup`, skipping the launch if the server is already listening.
3. It polls `http://127.0.0.1:13337/healthz` until the server is healthy, within a budget of 60 s, and then every 15 s afterwards. Two consecutive failures make it restart the server and show a "reconnecting" overlay.
4. The UI switches to `IdeScreen`, which is a WebView on `http://127.0.0.1:13337/?folder=/data/data/com.termux/files/home` with the key row docked below it.

The server writes its log on the device to `~/.kern/server.log` inside Termux.

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

A fresh Termux (0.119.0-beta.3, targetSdk 28, which confirms the exec-exemption model described in docs/03) plus the setup command above produced a working stack: **code-server 4.131.0, Node 24.18, Python 3.14.6, clang 21.1.8, ripgrep 15.2, llvm, make**. The page size was 4096.

A fresh Termux ships `allow-external-apps` **commented out**, and Termux rejects `RUN_COMMAND` without it. The app's setup command handles this, but it is the first thing to check if Start IDE reports a failure.

## Known limitations in M1

- Running with `--auth none` on `127.0.0.1:13337` means that *any app on the device* can reach the IDE server while a session runs. That is acceptable for a personal sideloaded build, and M2 replaces it with a generated password that the WebView submits automatically.
- Leaving and re-entering the app recreates the WebView, so the workbench reloads, which takes a few seconds. Retaining the WebView across activity recreation is an M2 task.
- The key row's symbol strip is fixed, and user-configurable layouts come in M2.
- There is no project picker yet, so the workbench opens Termux's home directory and you move elsewhere through the workbench's own File → Open Folder.

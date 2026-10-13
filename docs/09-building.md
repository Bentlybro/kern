# 09 — Building, Installing, Running

## Build

The build needs JDK 17 and an Android SDK with platform 37; the Gradle wrapper (Gradle 9.7.1, Android Gradle Plugin 9.4) is checked in and fetches the rest. AGP 9 compiles Kotlin itself, so there is no `kotlin-android` plugin.

```powershell
$env:JAVA_HOME = "C:\Program Files\Java\jdk-17"
cd "F:\Desktop\desktop misc\python coding\Mobile-IDE"
.\gradlew.bat assembleDebug
```

The build writes `app\build\outputs\apk\debug\app-debug.apk`. CI runs `assembleDebug`, `lintDebug` and `testDebugUnitTest`; run the same three before pushing.

## Install

**By hand on the Fold8, or on any other phone:** copy the APK across and tap it, and allow installation from unknown sources the one time Android asks.

**Over adb,** a single command installs it.
```powershell
C:\adb\adb.exe install -r "app\build\outputs\apk\debug\app-debug.apk"
```

**The lab Pixel is reached over wireless adb** on port 5555.
```powershell
C:\adb\adb.exe connect 192.168.0.233:5555
C:\adb\adb.exe -s 192.168.0.233:5555 install -r "app\build\outputs\apk\debug\app-debug.apk"
```

A debug and a release build are signed with different keys, so switching between them means uninstalling, which deletes the guest.

## First run

Kern needs nothing else installed. The setup screen downloads an Ubuntu base image, unpacks it under PRoot inside the app's own storage, and configures apt; the native editor opens as soon as that is done, and the toolchain and code-server (for the optional VS Code workbench) install behind it. [12](12-setup.md) describes the stages and their timings.

Two phone settings are worth granting, and the setup screen offers both: the battery-optimisation exemption, so Android does not stop the guest in the background, and *Disable child process restrictions* in Developer options, so long builds are not killed by the phantom-process limit.

## Debugging

```powershell
# App logs
C:\adb\adb.exe logcat -s Kern:* AndroidRuntime:E

# A shell in the guest, through the debug build
.\tools\device.ps1 guest 'uname -a; ls /root/projects'

# Every screen, checked end to end on the phone (about four minutes)
.\tools\smoke.ps1
```

In debug builds the VS Code workbench's WebView can be inspected from desktop Chrome at `chrome://inspect` while it is open. [20](20-testing.md) covers the unit tests and the smoke test.

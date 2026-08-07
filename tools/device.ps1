<#
.SYNOPSIS
  FoldCode device harness - one tested entry point for talking to the test phone.

.DESCRIPTION
  Replaces ad-hoc adb one-liners. Every command here is quoted correctly, resolves the
  app's native library directory dynamically (it changes on every reinstall), and never
  touches project source files.

  RULE: this script only ever reads from the device and writes to the scratch folder.
  It must never write into app/src. Source edits belong in the editor, not in a shell.

.EXAMPLE
  .\tools\device.ps1 build
  .\tools\device.ps1 deploy          # build + install + restart
  .\tools\device.ps1 shot setup      # screenshot -> tools/shots/setup.png
  .\tools\device.ps1 logs
  .\tools\device.ps1 status
  .\tools\device.ps1 guest "ls -la /root"
  .\tools\device.ps1 tap 540 2140
#>
[CmdletBinding()]
param(
    [Parameter(Mandatory = $true, Position = 0)]
    [ValidateSet('build', 'install', 'deploy', 'launch', 'restart', 'stop', 'shot',
                 'logs', 'crash', 'status', 'guest', 'proot', 'tap', 'swipe', 'text',
                 'key', 'reset', 'shell')]
    [string]$Command,

    [Parameter(Position = 1, ValueFromRemainingArguments = $true)]
    [string[]]$Args
)

$ErrorActionPreference = 'Stop'

# ---- configuration ---------------------------------------------------------

$Adb      = 'C:\adb\adb.exe'
$Device   = '192.168.0.233:5555'
$Pkg      = 'dev.foldcode.app'
$Activity = "$Pkg/.MainActivity"
$Root     = Split-Path -Parent $PSScriptRoot
$Apk      = Join-Path $Root 'app\build\outputs\apk\debug\app-debug.apk'
$ShotDir  = Join-Path $PSScriptRoot 'shots'
$JavaHome = 'C:\Program Files\Java\jdk-17'

# code-server port, as hex, for /proc/net/tcp lookups
$PortHex  = '3419'

# ---- helpers ---------------------------------------------------------------

function Connect-Device {
    & $Adb connect $Device *> $null
    $devices = & $Adb devices
    if (-not ($devices -match [regex]::Escape($Device))) {
        throw "Device $Device not reachable. Is the phone awake and on Wi-Fi?"
    }
}

function Invoke-Adb {
    param([Parameter(ValueFromRemainingArguments = $true)][string[]]$AdbArgs)
    $all = @('-s', $Device) + $AdbArgs
    & $Adb @all
}

<#
  The app's native library directory contains a per-install hash, so it must be looked up
  every time rather than hard-coded.
#>
function Get-NativeLibDir {
    $line = Invoke-Adb shell "dumpsys package $Pkg | grep -m1 legacyNativeLibraryDir"
    if ($line -match 'legacyNativeLibraryDir=(\S+)') {
        return "$($Matches[1])/arm64"
    }
    throw "Could not resolve nativeLibraryDir - is $Pkg installed?"
}

function Get-ListenCount {
    param([string]$HexPort)
    $rows = Invoke-Adb shell "cat /proc/net/tcp"
    $count = 0
    foreach ($row in $rows) {
        if ($row -match $HexPort) {
            $fields = ($row -split '\s+') | Where-Object { $_ }
            if ($fields.Count -gt 3 -and $fields[3] -eq '0A') { $count++ }
        }
    }
    return $count
}

# ---- commands --------------------------------------------------------------

switch ($Command) {

    'build' {
        $env:JAVA_HOME = $JavaHome
        & (Join-Path $Root 'gradlew.bat') -p $Root assembleDebug 2>&1 |
            Select-String -Pattern '^e: |error:|BUILD SUCCESSFUL|BUILD FAILED'
    }

    'install' {
        Connect-Device
        if (-not (Test-Path $Apk)) { throw "No APK at $Apk - run 'build' first." }
        Invoke-Adb install -r $Apk
        Invoke-Adb shell pm grant $Pkg android.permission.POST_NOTIFICATIONS
    }

    'deploy' {
        & $PSCommandPath build
        & $PSCommandPath install
        & $PSCommandPath restart
    }

    'launch' {
        Connect-Device
        Invoke-Adb shell am start -n $Activity | Out-Null
        "launched"
    }

    'restart' {
        Connect-Device
        Invoke-Adb shell am force-stop $Pkg
        Start-Sleep -Seconds 2
        Invoke-Adb shell am start -n $Activity | Out-Null
        "restarted"
    }

    'stop' {
        Connect-Device
        Invoke-Adb shell am force-stop $Pkg
        "stopped"
    }

    'shot' {
        Connect-Device
        $name = if ($Args -and $Args[0]) { $Args[0] } else { 'shot' }
        New-Item -ItemType Directory -Force $ShotDir | Out-Null
        $dest = Join-Path $ShotDir "$name.png"
        # Quoted as one string on purpose: passed as separate tokens, PowerShell claims
        # the leading -p as one of its own parameters and screencap writes nothing.
        Invoke-Adb shell "screencap -p /sdcard/_fc_shot.png"
        Invoke-Adb pull /sdcard/_fc_shot.png $dest | Out-Null
        Invoke-Adb shell "rm -f /sdcard/_fc_shot.png"
        $dest
    }

    'logs' {
        Connect-Device
        $n = if ($Args -and $Args[0]) { [int]$Args[0] } else { 20 }
        Invoke-Adb logcat -d -s 'FoldCode:*' 'FoldCodePty:*' | Select-Object -Last $n
    }

    'crash' {
        Connect-Device
        Invoke-Adb logcat -d -b crash | Select-Object -Last 40
    }

    'status' {
        Connect-Device
        $installed = Invoke-Adb shell "pm list packages $Pkg"
        $running   = Invoke-Adb shell "pidof $Pkg"
        $listen    = Get-ListenCount -HexPort $PortHex
        $rootfs    = Invoke-Adb shell "run-as $Pkg sh -c 'test -f files/linux/etc/os-release && echo yes || echo no'"
        $server    = Invoke-Adb shell "run-as $Pkg sh -c 'test -x files/linux/usr/bin/code-server && echo yes || echo no'"
        [PSCustomObject]@{
            Installed    = [bool]$installed
            Pid          = ($running | Out-String).Trim()
            CodeServerUp = ($listen -gt 0)
            RootfsReady  = ($rootfs | Out-String).Trim()
            CodeServer   = ($server | Out-String).Trim()
        } | Format-List
    }

    # Run a command INSIDE the Linux guest, using the app's own PRoot and rootfs.
    #
    # The rootfs lives in app-private storage, so this goes through `run-as` to borrow
    # the app's uid. The command travels as base64 and is decoded into the guest's own
    # /tmp on the device: written literally it would cross five levels of quoting
    # (PowerShell, adb, sh, run-as, bash) and any quote or $ would be mangled on the way.
    #
    # -l is --link2symlink and is mandatory: SELinux forbids hard links in app storage,
    # and dpkg depends on them.
    'guest' {
        Connect-Device
        if (-not $Args) { throw "Usage: device.ps1 guest '<command>'" }
        $lib   = Get-NativeLibDir
        $data  = "/data/data/$Pkg/files"
        # Optional override so a candidate rootfs can be exercised side by side with the
        # installed one, e.g. $env:FOLDCODE_ROOTFS = 'linux26' before a distro upgrade.
        $guestDir = if ($env:FOLDCODE_ROOTFS) { $env:FOLDCODE_ROOTFS } else { 'linux' }
        $b64   = [Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes(($Args -join ' ')))
        $vars  = "LD_LIBRARY_PATH=$lib PROOT_LOADER=$lib/libproot_loader.so " +
                 "PROOT_LOADER32=$lib/libproot_loader32.so " +
                 "PROOT_TMP_DIR=$data/tmp PROOT_L2S_DIR=$data/$guestDir/.l2s " +
                 "PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin " +
                 "HOME=/root USER=root TERM=xterm-256color LANG=C.UTF-8 TMPDIR=/tmp"
        # Only base64 and fixed paths end up in this string, so it never contains a
        # quote of its own and can be single-quoted for the device shell safely.
        $remote = "mkdir -p $data/tmp $data/$guestDir/.l2s $data/$guestDir/tmp; " +
                  "echo $b64 | base64 -d > $data/$guestDir/tmp/fc-guest.sh; " +
                  "$vars exec $lib/libproot.so -0 -l -r $data/$guestDir " +
                  "-b /proc -b /sys -b /dev -b /dev/pts -w /root " +
                  "/bin/bash /tmp/fc-guest.sh"
        Invoke-Adb shell "run-as $Pkg sh -c '$remote'"
    }

    'proot' {
        Connect-Device
        $lib = Get-NativeLibDir
        Invoke-Adb shell "LD_LIBRARY_PATH=$lib PROOT_LOADER=$lib/libproot_loader.so $lib/libproot.so --version"
    }

    'tap' {
        Connect-Device
        if ($Args.Count -lt 2) { throw "Usage: device.ps1 tap <x> <y>" }
        Invoke-Adb shell input tap $Args[0] $Args[1]
    }

    'swipe' {
        Connect-Device
        if ($Args.Count -lt 4) { throw "Usage: device.ps1 swipe <x1> <y1> <x2> <y2> [ms]" }
        $ms = if ($Args.Count -ge 5) { $Args[4] } else { 300 }
        Invoke-Adb shell input swipe $Args[0] $Args[1] $Args[2] $Args[3] $ms
    }

    # Types text safely: `input text` needs %s for spaces and chokes on shell
    # metacharacters, so send it one word at a time with explicit space keyevents.
    'text' {
        Connect-Device
        if (-not $Args) { throw "Usage: device.ps1 text '<string>'" }
        $words = ($Args -join ' ') -split ' '
        for ($i = 0; $i -lt $words.Count; $i++) {
            if ($words[$i]) {
                $escaped = $words[$i] -replace '([()<>|;&*\\~"`$])', '\$1'
                Invoke-Adb shell input text "$escaped" | Out-Null
            }
            if ($i -lt $words.Count - 1) { Invoke-Adb shell input keyevent 62 | Out-Null }
        }
        "typed"
    }

    'key' {
        Connect-Device
        if (-not $Args) { throw "Usage: device.ps1 key <keycode>" }
        Invoke-Adb shell input keyevent $Args[0]
    }

    'shell' {
        Connect-Device
        if (-not $Args) { throw "Usage: device.ps1 shell '<command>'" }
        Invoke-Adb shell ($Args -join ' ')
    }

    # Full wipe: uninstall clears app data including the whole Linux guest.
    'reset' {
        Connect-Device
        Invoke-Adb uninstall $Pkg
        "uninstalled - run 'deploy' for a clean first-run test"
    }
}

<#
.SYNOPSIS
  Walk every screen of Kern on the test phone and check each one shows what it should.

.DESCRIPTION
  Navigates by visible text (uiautomator dumps), not by coordinates, so it survives layout
  changes. Each step taps something and asserts that expected text appears; the run ends
  with a pass/fail count and a non-zero exit code on any failure. Needs a set-up guest
  with a project called "demo" containing hello.py.

.EXAMPLE
  .\tools\smoke.ps1
#>
$ErrorActionPreference = 'Continue'
$Adb = 'C:\adb\adb.exe'
$Device = '192.168.0.233:5555'
$Pkg = 'dev.kern.app'
$script:pass = 0
$script:fail = @()

function Dump {
    & $Adb -s $Device shell uiautomator dump /sdcard/kern-ui.xml 2>$null | Out-Null
    [xml](& $Adb -s $Device shell cat /sdcard/kern-ui.xml 2>$null)
}

function Find([string]$text) {
    $xml = Dump
    $xml.SelectNodes('//node') | Where-Object {
        $_.text -eq $text -or $_.'content-desc' -eq $text
    } | Select-Object -First 1
}

function Tap([string]$text) {
    for ($i = 0; $i -lt 4; $i++) {
        $n = Find $text
        if ($n) {
            $b = [regex]::Matches($n.bounds, '\d+') | ForEach-Object { [int]$_.Value }
            & $Adb -s $Device shell input tap ([int](($b[0] + $b[2]) / 2)) ([int](($b[1] + $b[3]) / 2)) 2>$null
            Start-Sleep -Milliseconds 1500
            return $true
        }
        Start-Sleep 1
    }
    return $false
}

function Expect([string]$step, [string[]]$texts) {
    foreach ($t in $texts) {
        $ok = $false
        for ($i = 0; $i -lt 8 -and -not $ok; $i++) {
            $xml = Dump
            $ok = [bool]($xml.SelectNodes('//node') | Where-Object { $_.text -like "*$t*" -or $_.'content-desc' -like "*$t*" })
            if (-not $ok) { Start-Sleep 1 }
        }
        if ($ok) { $script:pass++ } else {
            $script:fail += "$step - missing '$t'"
            # What the screen actually showed, for the failure list to be checked against.
            $name = ($step -replace '[^a-z]', '-')
            & $Adb -s $Device shell screencap -p /sdcard/kern-fail.png 2>$null
            & $Adb -s $Device pull /sdcard/kern-fail.png "$PSScriptRoot\shots\smoke-$name.png" 2>$null | Out-Null
        }
    }
}

function Step([string]$name, [scriptblock]$body) {
    Write-Host "- $name"
    & $body
}

function Back { & $Adb -s $Device shell input keyevent KEYCODE_BACK 2>$null; Start-Sleep 1 }

& $Adb connect $Device *> $null
& $Adb -s $Device shell am force-stop $Pkg 2>$null
& $Adb -s $Device shell monkey -p $Pkg -c android.intent.category.LAUNCHER 1 2>$null | Out-Null
Start-Sleep 6

Step 'top bar' { Expect 'top bar' @('Files', 'Source control', 'Undo', 'More') }

Step 'projects' {
    $null = Tap 'Projects'; if (-not (Find 'New project')) { $null = Tap 'More'; $null = Tap 'Projects' }
    Expect 'projects' @('New project', 'Clone', '~/PROJECTS', 'Open folder')
    $null = Tap 'New project'; Expect 'new project dialog' @('Start a git repository', 'Create'); $null = Tap 'Cancel'
    $null = Tap 'Clone'; Expect 'clone dialog' @('Clone a repository', 'Latest commit only'); $null = Tap 'Cancel'
    & $Adb -s $Device shell input swipe 540 1500 540 600 2>$null; Start-Sleep 1
    $null = Tap 'Open folder…'; Expect 'folder browser' @('Open here', '~/projects'); Back
    Expect 'back closes the browser' @('New project')
    $null = Tap 'demo'
}

Step 'file tree and editor' {
    if (-not (Find 'hello.py')) { $null = Tap 'Files' }
    $null = Tap 'hello.py'
    Expect 'editor' @('hello.py', 'Run')
}

Step 'go to file' { $null = Tap 'More'; $null = Tap 'Go to file'; Expect 'go to file' @('Go to file'); Back }

Step 'find' { $null = Tap 'More'; $null = Tap 'Find'; Expect 'find bar' @('⇄'); $null = Tap '✕' }

Step 'run' {
    $null = Tap 'Run'
    # The terminal draws its text rather than exposing it, so read the shell through tmux.
    Start-Sleep 6
    $out = & powershell -NoProfile -Command "& '$PSScriptRoot\device.ps1' guest 'tmux capture-pane -p -t kern-1 2>/dev/null; tmux capture-pane -p -t kern-2 2>/dev/null'" 2>$null
    if ("$out" -like '*5.0 True None 31*') { $script:pass++ } else { $script:fail += "run output - not in terminal" }
    $null = Tap 'Show editor'
}

Step 'terminal' {
    $null = Tap 'Show terminal'; Expect 'terminal' @('Show editor'); $null = Tap 'Show editor'
}

Step 'source control' {
    $null = Tap 'Source control'; Expect 'cockpit' @('Changes', 'Agent'); Back
}

Step 'settings' {
    $null = Tap 'More'; $null = Tap 'Settings'
    # Compose reports only what is on screen, so each group is checked where it is visible.
    Expect 'settings' @('EDITOR', 'Kern editor', 'VS Code', 'Text size')
    & $Adb -s $Device shell input swipe 540 1800 540 700 2>$null; Start-Sleep 1
    Expect 'settings middle' @('GITHUB')
    1..8 | ForEach-Object { & $Adb -s $Device shell input swipe 540 2000 540 400 2>$null }
    Start-Sleep 1
    Expect 'settings lower' @('BACKUP', 'Export projects', 'Delete Linux')
    Back
}

Step 'status' {
    $null = Tap 'More'; $null = Tap 'Status'
    Expect 'status' @('Linux', 'Toolchain', 'PRoot', 'WHERE YOUR TIME GOES')
    Back
}

Step 'quit confirm' {
    $null = Tap 'More'; $null = Tap 'Quit'; Expect 'quit dialog' @('Quit Kern?'); $null = Tap 'Cancel'
}

Write-Host ""
Write-Host "passed: $($script:pass)  failed: $($script:fail.Count)"
$script:fail | ForEach-Object { Write-Host "  FAIL $_" }
if ($script:fail.Count -gt 0) { exit 1 }

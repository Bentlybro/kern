# 20 — Testing

Kern has a small unit suite and a written device checklist, and the split between them is deliberate. The suite covers the handful of pure decisions that are genuinely wrong-able on a laptop. Everything else — which is most of the app — is covered by a human with a phone, because there is nowhere else it can be covered.

The tests live in `app/src/test/java/dev/kern/app/`, under `runtime/` and `session/`.

## Running them

```
./gradlew testDebugUnitTest
```

There are 105 tests. They take six seconds on a warm build and twenty-five from cold, and most of that is compiling the app. They need no network, no device and no emulator. The HTML report lands in `app/build/reports/tests/testDebugUnitTest/index.html`, and the machine-readable results land in `app/build/test-results/testDebugUnitTest/`.

CI runs the same task on every push to `dev` and every pull request into `dev` or `main`. Unlike lint it has no `continue-on-error`, so a red test is a red build. When the tests themselves fail, CI uploads both reports as a `unit-test-report-<sha>` artifact — the console only prints a count, and the artifact is what says which assertion went and why.

There is one extra CI step, `Check the unit tests actually ran`, and it is there because of how this document came to exist. From the first commit until now, `testDebugUnitTest` ran against a source set containing only `main`. Gradle reports a task with nothing to do as success, so CI displayed a green tick next to the words "Unit tests" for the entire life of the project while running none. That is worse than having no step, because a green tick is trusted. The step reads the result XML, sums the test counts, and fails the build if the total is zero.

## What they cover

| File | Under test | Why it is worth pinning |
|---|---|---|
| `ShellQuotingTest` | `LinuxRuntime.sq()` | Every value the app pastes into a guest script — clone URLs, project names, branch names, commit messages — passes through here. A value that escapes its quotes is arbitrary code execution as fake root over the whole rootfs, with the user's `gh` token sitting in `/root/.config/gh/hosts.yml`. This is the most valuable test in the repository. |
| `ProjectNameTest` | `ProjectRepository.sanitise()` / `deriveName()` | These decide which directory a clone lands in, from a URL nobody here wrote. The result is concatenated onto the projects path, handed to `mkdir` and `git clone`, and later comes back to `delete()` as the argument to an `rm -rf`. |
| `RootfsResolveTest` | `RootfsInstaller.selectRootfs()` | This is step one of setup, and there is no way past it. Picking a pruned name kills every new install; picking the wrong series gives a guest whose apt sources name a codename that no longer describes it; pairing a name with a neighbour's digest fails every download as corrupt. |
| `ResultTest` | `LinuxRuntime.Result.lastLine()` / `lines` | This is what the user is shown when a guest command fails, and what the project list and the health check parse as data. |
| `AgentPromptTest` | `AgentPrompt.awaitingInput()` / `lastLine()` | This drives a notification. A miss parks an agent for an hour in a pocket; a false alarm teaches the user to ignore the one that matters. |
| `GuestCommandTest` | `LinuxRuntime.parseExitCode()` / `prootArgv()` / `bindCandidates()` | Both halves of this file pin something whose failure is silent. A guest command's exit code is read from a file the shell creates by truncation before writing, so an early read used to come back as exit 0 — success — for a command that had failed. And a PRoot invocation with a bind missing or an option after the guest command does not error; it produces a guest that breaks minutes later, on the first package with a hard link, a long way from the argv that caused it. |
| `RestartPolicyTest` | `RestartPolicy.next()` / `backoffMs()` | This decides whether a phone with a broken guest reports it or quietly flattens its battery, and the version it replaced did the latter: the miss counter reset only on a *successful* restart, so a server that could not come back was stopped and restarted every fifteen seconds forever, wakelock held, UI stuck on "Reconnecting". The test that pins it runs an hour of failing ticks and asserts the restart count. |
| `DeviceFlowReasonTest` | `GitHubDeviceFlow.reasonFor()` | This string is the entire failure the sign-in screen shows. Each case points the reader somewhere different — back to GitHub, at the Status screen, or at trying again — and the wrong one costs them the time it takes to rule it out. It also has to keep gh's answered prompts and the one-time code itself out of the message, both of which sit on the pane and both of which read as explanations. |
| `UpdateVersionTest` | `Updates.isNewer()` / `digestOf()` | `isNewer` decides on every launch whether the user is shown an update. Backwards, and the app either offers a downgrade or silently never offers anything again — invisible until someone notices they are several versions behind. It had been `internal` since it was written, widened for a test that did not exist. |
| `PackagesForTest` | `HealthCheck.packagesFor()` | The app probes binary names and installs package names, and where the two differ an unmapped list produces `E: Unable to locate package node`, which is a Fix button that can only fail. |

Two conventions run through all ten, and both exist so the suite cannot quietly stop testing anything:

- **Test the contract, not the source.** `ShellQuotingTest` checks `sq()` against a small model of bash's word parsing rather than against the escaping it happens to emit, so a rewrite has to satisfy the shell rather than resemble the version that was there when the test was written. `ProjectNameTest` spells out the allowed character set rather than reusing the implementation's regex.
- **Test the fixtures too.** Several files end with a test asserting that their own fixtures still have the property that makes the rest of the file meaningful — that `AgentPromptTest`'s screens are really padded with blank rows, that `RootfsResolveTest` gives every image a distinct digest, that the bash model still rejects the quoting mistakes `sq()` exists to avoid. Without these, a fixture that drifts turns the file into passing tests of nothing, which is the exact failure this suite was written to end.

A handful of functions were widened from `private` to `internal` to be reached from tests, each with a one-line comment saying so: `HealthCheck.packagesFor`, `ProjectRepository.sanitise` and `deriveName`, `RootfsInstaller.selectRootfs`, `GuestConfig.resolvConf`, `Updates.digestOf`, `GitHubDeviceFlow.reasonFor`, and `LinuxRuntime.parseExitCode`, `prootArgv` and `bindCandidates`. `RestartPolicy` is a whole `internal object` split out of `SessionService` for the same reason.

Several were also *split out* of something larger so the decision inside could be reached without a device: `selectRootfs` out of `resolveRootfs`, so the choice can be made from SHA256SUMS text without reaching cdimage; `prootArgv` and `bindCandidates` out of `prootArgs`, so the argv can be built from paths rather than from a `Context`; and `RestartPolicy` out of `SessionService.supervise`, which was four lines of arithmetic tangled into a loop with a wakelock, a notification and two network calls - untestable in place, and wrong. The split is the only change in each case — `prootArgs` still filters the candidate binds against the host filesystem, because whether `/sdcard` exists is a question only a real device can answer. No behaviour was changed to suit a test.

## Why the suite stops there

There is no Robolectric, no instrumentation source set, and no Compose UI test. That is a decision, not a gap nobody got round to.

**CI has no arm64 device, and cannot have one.** Kern is a real Ubuntu rootfs unpacked into app-private storage and executed under PRoot with a loader stub, on unrooted arm64. GitHub's runners are x86_64 containers. The parts of Kern that actually break — PRoot's syscall interception, `dpkg` unpacking under a fake root, the JNI PTY, `tmux` inside that PRoot, `code-server` binding a port inside the guest, Android's phantom process killer capping children at 32 against a guest that runs about seventy — do not exist off the phone. An emulator would not help either: an arm64 image under emulation is slow enough that timing-shaped bugs, which is most of them, either never appear or appear spuriously.

**Robolectric would test the mock, not Android.** Almost every Android-facing class here is a thin shell over something Robolectric has no model of: `WebView` running the VS Code workbench, a `Service` whose whole job is surviving Doze, `SharedPreferences` used as plumbing rather than logic. A Robolectric test of `WorkbenchWebView` would assert that a fake `WebView` was told to load a URL. The bug it needs to catch is that the real WebView takes an input connection during a scroll, which the fake one has no opinion about.

**Compose UI tests rot faster than the UI they describe.** The layouts here change shape with the fold, the keyboard, and the posture. A test that finds a node by text and taps it locks in the wording, not the behaviour, and it is the wording that changes weekly.

**Nothing in the suite touches a clock, a socket, or a file.** Every test is a pure function of its input. That is what makes six seconds on every push affordable and, more to the point, what makes a red build believable — a flaky suite would be back to a green tick nobody reads.

The result is that a large majority of the app has no automated coverage at all, and it is better to say that plainly than to inflate the number with tests that would pass whatever happened. What the suite protects is the code where being wrong is silent and expensive. The rest is below.

## Device checklist

Run this on a real phone before tagging a release. Every item here is derived from something that actually broke, and several of them broke *after* the code that caused them looked obviously correct on a laptop. `tools/device.ps1` drives most of it — see [16 — Development](16-development.md) — but read the screen rather than the logs, because several of these were invisible in `logcat` and immediately obvious in a screenshot.

Record the result. "Verified on device" is the standard the rest of these documents claim, and it only means something if somebody actually looked.

### 1. Fresh install on a wiped app

Run `.\tools\device.ps1 reset` first, so that this is a genuine first run and not a resume.

1. Install and open. Setup should name the download size and the free space it needs before it starts, not fail from inside `tar`.
2. Watch it through the download, the extract, and the toolchain apt.
3. **Expected:** the editor opens. The workbench draws a real VS Code, not a blank or a spinner.

There is a specific failure to watch for. The first `code-server` start after a fresh setup dies about a hundred milliseconds in, reproducibly, while the toolchain apt is still running behind the opening editor. Kern retries it three times now, so this should read as a delay of a couple of seconds. If the app instead sits on "starting Linux" for ninety seconds and then blames a log file, the retry is broken and the cause is still unexplained (see `ecca234`).

Also confirm that setup did not report success after failing: if it says done, `git`, `tmux` and `gh` must actually be present. Check that with `.\tools\device.ps1 guest "command -v git gh tmux"`.

### 2. Setup surviving a backgrounded app

1. Start a fresh setup.
2. During the download or the apt, press home. Wait a minute. Open another app. Come back.
3. **Expected:** setup carried on and finished, or it reports honestly what it was doing.

Then try the harder version: during setup, navigate away inside the app, to Settings, to Status, and back. Guest work runs on an application scope now precisely so that leaving a screen abandons the reporting and not the work. Coming back to Status must not offer a Fix button on top of a live apt, because the second one dies on dpkg's lock.

### 3. Delete everything, then reinstall

1. With a working guest and a project open, go to Settings and delete the guest.
2. **Expected:** the workbench is gone, not still showing the old page. Every terminal is gone, not left with a `cwd` pointing at an unlinked directory while new absolute paths resolve into a rootfs that does not exist yet. The download cache is cleared too.
3. Run setup again from the same app session, without force-stopping it.
4. **Expected:** the install completes, and the editor opens.

This one produced a hybrid state handed to the user as if it worked, and the second install is where a stale `.part` file or a stale "installed" marker shows up.

### 4. Clone, commit, and leave the screen immediately

This is the single most productive item on this list.

1. Clone a repository of real size, big enough that it takes a minute.
2. While the clone is running, hit back, or open the terminal, or let a call arrive.
3. **Expected:** the clone finishes. Come back and the project is there and complete.

Then do the same for a commit:

1. Make a change, commit, and navigate away the instant you tap.
2. **Expected:** the commit lands.
3. **Then check for wreckage:** `.\tools\device.ps1 guest "ls /root/projects/<name>/.git/index.lock"` must find nothing. A `.git/index.lock` left behind breaks every later git operation in that repository, including the workbench's own SCM panel, and nothing in the UI says why.
4. Retry a clone of the same URL. A half-checkout left behind used to block that URL permanently.

Also check that the Push button is still enabled after a successful commit, and that Repair and Delete everything are still enabled after Free up space. One `busy` string doing duty as both spinner and result disabled them forever.

### 5. Close a terminal tab and reattach

1. Open a terminal in a project and start something long, such as a build, `top`, or anything else with state.
2. Close the tab. The close control is a real touch target and asks first.
3. **Expected:** closing *detaches*. Reopen a terminal for that project and the tmux session comes back with the work still running.
4. Type `exit` in a terminal. **Expected:** the tab goes with it, and if it was the last one a fresh shell opens on a new session with a live prompt, rather than a dead pane with no way out.
5. Open the agent cockpit while a terminal is running. **Expected:** the terminal is still there when you come back, and the agent's own screen is not blanked.

### 6. The keyboard while scrolling the editor

1. Open a file in the editor and tap in it to place a caret. The keyboard comes up.
2. Dismiss the keyboard and scroll the file with a drag.
3. **Expected:** the keyboard stays down for the whole scroll and afterwards. There is no flicker and no open or close on every swipe.
4. Tap once. **Expected:** the keyboard comes back immediately.
5. Type with the key row and with a hardware keyboard while scrolled. **Expected:** both still work. They are dispatched as key events and must not be affected by the refusal.

Monaco does not reliably raise the soft keyboard inside a WebView, so the host drives the IME; the fix for the scroll case is a refused input connection rather than a hide, and both hiding and blurring were tried and were worse.

### 7. Back from every screen

Walk back out of each of these screens and confirm that back does the obvious thing exactly once:

- Press back in the editor. The app stays and nothing is quit.
- Press back with the terminal pane open. **The keyboard closes, and whatever is running keeps running.** Back must never reach the terminal as ESC, because anything interactive reads ESC as "quit", which is how dismissing a keyboard used to kill a build.
- Press back on the projects screen.
- Press back in Settings, and press back in Status. These two were broken twice, most recently by screen precedence being written down in three notations kept in step by hand.
- Press back in the agent cockpit.

### 8. An extension, and a force-stop

This is the least verified claim in the project, because `19-next.md` says that extensions have not been checked end to end. Until they have been, this item tests the claim rather than checking for a regression.

1. Install an extension from Open VSX through the workbench's own UI.
2. **Expected:** it installs and activates.
3. Force-stop Kern from Android settings. This is not a graceful quit — it takes the guest and every process in it with it.
4. Reopen. **Expected:** the extension is still installed and still activates. It lives on the guest filesystem, so it should survive; what will not survive is anything unsaved or mid-build, because tmux's server is a child of Kern's own PRoot.
5. While you are here, confirm autosave did its job: the file you were editing before the force-stop still has its changes.

### 9. Recovery paths

These are the buttons that exist for when something has already gone wrong, and they are only ever exercised deliberately.

1. Interrupt an apt mid-unpack (force-stop during the toolchain install), then try to install anything. A dpkg killed mid-unpack refuses every later install until `dpkg --configure -a` runs. **Expected:** the app runs it itself and the install succeeds.
2. Press Repair on a working guest. **Expected:** it reconfigures rather than skipping configuration, and costs nothing.
3. Trigger a setup failure (turn off the network mid-download). **Expected:** the app shows a specific message, a Retry that works, and a copy button that puts the real output on the clipboard. On a phone with no adb and no second machine, an error you cannot copy is a bug that never reaches anyone.
4. Turn the network off and on mid-download. **Expected:** the download resumes rather than restarting 218 MB from zero.

### 10. The fold

This is the least-tested surface in the app, and `19-next.md` honestly records it as such. Open and close the device on every screen, and check tabletop mode, the crease split and the cover display. Nothing here has automated coverage of any kind.

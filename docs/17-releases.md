# 17 — Releases, CI and updates

## Branches

| Branch | What it is | What CI does |
|---|---|---|
| `dev` | Where work happens | Builds a debug APK, lints, runs tests. No secrets. |
| `main` | What ships | Builds a **signed** APK and attaches it to a **draft** release. |

Merging to `main` does not release anything. It prepares a draft; publishing it is a
separate, deliberate act — and until it is published, the in-app updater cannot see it.

## The signing key is the whole security model

Android refuses to install an update signed with a different key than the installed app.
That single rule is what makes over-the-air updates safe, and it has two consequences
worth being blunt about:

- **Leak it** and someone else can ship an "update" to every install of Kern.
- **Lose it** and nobody can ever update again. Not "inconvenient" — the only fix is for
  every user to uninstall and lose their Linux environment.

So the key lives in exactly two places: a password manager, and GitHub encrypted secrets.
Never the repository — `.gitignore` refuses `*.jks`, `*.keystore`, `*.p12` and friends so
it cannot be committed by accident.

### Creating it, once

```
keytool -genkeypair -v -keystore release.jks -alias kern \
        -keyalg RSA -keysize 4096 -validity 10000
```

Back up `release.jks` and both passwords somewhere you will still have in ten years.

### Putting it in CI

The secrets live in a GitHub **environment** called `release`, not at repository level.
That distinction is the point: repository secrets are readable by any workflow that asks
for them, so a pull request that adds one line to a workflow can print the key. An
environment secret is only readable by a job that declares `environment: release`, and
with a required reviewer that job pauses for a human before it runs at all.

PowerShell, from the repository root:

```powershell
# 1. Create the environment and require your own approval to use it
gh api -X PUT repos/Bentlybro/kern/environments/release

# 2. Create the key (keytool ships with the JDK)
& "$env:JAVA_HOME\bin\keytool" -genkeypair -v `
    -keystore release.jks -alias kern `
    -keyalg RSA -keysize 4096 -validity 10000 -storetype PKCS12

# 3. Base64 it, without a trailing newline
[Convert]::ToBase64String([IO.File]::ReadAllBytes("release.jks")) |
    Set-Content -NoNewline keystore.b64

# 4. Set the four secrets. Piped rather than passed as arguments, so none of them
#    ends up in PowerShell history or in the process list.
Get-Content -Raw keystore.b64 | gh secret set KEYSTORE_BASE64 --env release
Read-Host "keystore password" -AsSecureString |
    ConvertFrom-SecureString -AsPlainText | gh secret set KEYSTORE_PASSWORD --env release
"kern" | gh secret set KEY_ALIAS --env release
Read-Host "key password" -AsSecureString |
    ConvertFrom-SecureString -AsPlainText | gh secret set KEY_PASSWORD --env release

# 5. Destroy the base64 copy. It is the key wearing a different coat.
Remove-Item keystore.b64
```

Then, in the browser once: **Settings → Environments → release → Required reviewers →
add yourself**. Without that, the environment is only a namespace; with it, nothing can
use the signing key without your explicit approval.

Move `release.jks` itself somewhere durable and offline. Do not leave it in the
repository directory — `.gitignore` stops it being committed, but not being deleted.

| Secret | Value |
|---|---|
| `KEYSTORE_BASE64` | base64 of `release.jks`, no trailing newline |
| `KEYSTORE_PASSWORD` | The password |
| `KEY_ALIAS` | `kern` |
| `KEY_PASSWORD` | **The same password again** |

`KEY_PASSWORD` is not a second password. PKCS12 cannot store the key under a different
one from the store, and keytool does not refuse — it warns and silently ignores:

    Warning: Different store and key passwords not supported for PKCS12 KeyStores.
             Ignoring user-specified -keypass value.

Set them to different values and everything appears to work until the build fails with

    Get Key failed: Tag number over 30 at 0 is not supported

which is what a wrong key password looks like once the encrypted key is parsed as ASN.1.
Nothing in that message mentions passwords.

## Why the workflows are shaped the way they are

These are the decisions that matter once the repository is public:

- **CI uses `pull_request`, not `pull_request_target`.** `pull_request` builds a fork's
  code with the fork's permissions and *no access to secrets*. `pull_request_target` runs
  with ours and secrets available, which is exactly how public Android repositories leak
  signing keys. If a fork's build ever fails for want of a secret, that is the protection
  working — do not swap the trigger to "fix" it.
- **Only `main` can reach the key.** Pushing to `main` requires write access, so the
  release workflow is not reachable by a pull request, a fork, or a dev push.
- **`permissions:` is least privilege.** CI gets `contents: read`; only the release job
  gets `contents: write`, and only to create the draft.
- **The key is written to `RUNNER_TEMP`**, never the workspace, so no artifact step can
  archive it — and it is shredded explicitly rather than trusting the runner to vanish.
- **`if: github.repository == …`** stops a fork running the release workflow at all.
- **Third-party actions are pinned to commit SHAs, not tags.** A tag is mutable: whoever
  controls an action's repository can repoint `v4` at new code, and that code runs on the
  runner with the signing secrets in scope. A SHA cannot be moved. Re-pin deliberately
  when updating, and read what changed.
- **The secrets are environment-scoped with a required reviewer.** Repository secrets are
  readable by any workflow that asks; this way a workflow change that starts using them
  has to be approved by a human first.
- Releases are **drafts**, and the updater ignores drafts and pre-releases, so an
  accidental merge cannot ship anything to anyone.

## The leak paths, and what covers each

Encryption at rest is not the interesting part — GitHub does that already. These are the
ways signing keys actually escape:

| How it leaks | Covered by |
|---|---|
| Untrusted fork code runs with secrets | `pull_request`, never `pull_request_target` |
| A workflow change starts printing the key | Environment secret + required reviewer |
| A hijacked action tag runs new code | Actions pinned to commit SHAs |
| The key is archived as a build artifact | Written to `RUNNER_TEMP`, never the workspace |
| The key survives the job | `shred` in an `always()` step |
| A fork runs the release workflow | `if: github.repository == …` |
| Committed by accident | `.gitignore`, plus GitHub push protection |
| Echoed into logs | No step prints it; GitHub also masks known values |

Two things worth turning on in the browser that no workflow can do for you: **branch
protection on `main`** so releases only come from reviewed merges, and **secret scanning
with push protection**, which is free on public repositories.

What none of this covers: anyone with write access to the repository, and your own
machine. The key is only ever as safe as those.

## Cutting a release

1. Bump `appVersionName` in `app/build.gradle.kts`. It is the single source of truth —
   the workflow reads it for the tag, and the updater compares against it.
2. Merge `dev` into `main`.
3. CI builds, signs, verifies the signature, and drafts `vX.Y.Z` with the APK attached.
4. Read the generated notes, then publish.

`versionCode` comes from the workflow run number, so it always increases without anyone
having to remember. Android rejects a downgrade, so this matters.

## Updates in the app

Settings → Updates. It checks the latest release when the screen opens, and that is the
only time it touches the network.

An available update can be installed or **skipped**, and a skipped version stays skipped
until "Check for updates" is pressed deliberately. Nothing installs on its own: the
download goes to app-private cache — where nothing else on the device can rewrite it —
and is handed to Android's `PackageInstaller`, which shows its own confirmation. So the
user says yes twice, and the platform still verifies the signature before it does
anything.

Implementation: `runtime/Updates.kt`, `runtime/InstallReceiver.kt`, `ui/UpdateSection.kt`.

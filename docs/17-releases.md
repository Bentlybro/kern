# 17 — Releases, CI and updates

## Branches

| Branch | What it is | What CI does |
|---|---|---|
| `dev` | This is where work happens. | CI builds a debug APK, lints it and runs the tests. It has no access to secrets. |
| `main` | This is what ships. | CI builds a **signed** APK and attaches it to a **draft** release. |

Merging to `main` does not release anything. It prepares a draft, and publishing that draft is a separate and deliberate act; until somebody publishes it, the in-app updater cannot see it.

## Verifying a download

Every release is signed with the same key. Anyone can check that an APK really came from this project:

```
apksigner verify --print-certs kern-0.1.0.apk
```

```
Signer #1 certificate DN: CN=Kern, O=Kern, C=GB
Signer #1 certificate SHA-256 digest:
  9f27b62ed2e3fee3fe27e8c2f260a9e0a41bfa23dab9fa6f5acdf4045af83308
```

A different fingerprint means a different key, and Android will refuse to install it over an existing Kern regardless — that refusal is what makes in-app updates safe:

```
INSTALL_FAILED_UPDATE_INCOMPATIBLE:
  Existing package dev.kern.app signatures do not match newer version
```

## The signing key is the whole security model

Android refuses to install an update signed with a different key than the installed app. That single rule is what makes over-the-air updates safe, and it has two consequences worth being blunt about:

- If you **leak it**, someone else can ship an "update" to every install of Kern.
- If you **lose it**, nobody can ever update again. That is not merely inconvenient: the only fix is for every user to uninstall and lose their Linux environment.

So the key lives in exactly two places, which are a password manager and GitHub encrypted secrets. It never lives in the repository, and `.gitignore` refuses `*.jks`, `*.keystore`, `*.p12` and friends so that nobody can commit it by accident.

### Creating it, once

```
keytool -genkeypair -v -keystore release.jks -alias kern \
        -keyalg RSA -keysize 4096 -validity 10000
```

Back up `release.jks` and both passwords somewhere you will still have in ten years.

### Putting it in CI

The secrets live in a GitHub **environment** called `release`, not at repository level. That distinction is the point: repository secrets are readable by any workflow that asks for them, so a pull request that adds one line to a workflow can print the key. An environment secret is only readable by a job that declares `environment: release`, and with a required reviewer that job pauses for a human before it runs at all.

Run the following in PowerShell, from the repository root:

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

Then do one thing in the browser: go to **Settings → Environments → release → Required reviewers** and add yourself. Without that the environment is only a namespace; with it, nothing can use the signing key without your explicit approval.

Move `release.jks` itself somewhere durable and offline. Do not leave it in the repository directory, because `.gitignore` stops it being committed but does not stop it being deleted.

| Secret | Value |
|---|---|
| `KEYSTORE_BASE64` | This is the base64 of `release.jks`, with no trailing newline. |
| `KEYSTORE_PASSWORD` | This is the keystore password. |
| `KEY_ALIAS` | The value is `kern`. |
| `KEY_PASSWORD` | This is **the same password again**. |

`KEY_PASSWORD` is not a second password. PKCS12 cannot store the key under a password different from the store's, and keytool does not refuse the attempt; it warns and then silently ignores the value:

    Warning: Different store and key passwords not supported for PKCS12 KeyStores. Ignoring user-specified -keypass value.

If you set them to different values, everything appears to work until the build fails with this:

    Get Key failed: Tag number over 30 at 0 is not supported

That is what a wrong key password looks like once the encrypted key is parsed as ASN.1, and nothing in the message mentions passwords.

## Why the workflows are shaped the way they are

These are the decisions that matter once the repository is public:

- **CI uses `pull_request`, not `pull_request_target`.** `pull_request` builds a fork's code with the fork's permissions and with *no access to secrets*. `pull_request_target` builds it with our permissions and with the secrets available, which is exactly how public Android repositories leak signing keys. If a fork's build ever fails for want of a secret, that is the protection working, so do not swap the trigger to "fix" it.
- **Only `main` can reach the key.** Pushing to `main` requires write access, so a pull request, a fork or a dev push cannot reach the release workflow.
- **The `permissions:` blocks grant least privilege.** CI gets `contents: read`, and only the release job gets `contents: write`, which it needs only to create the draft.
- **The release job writes the key to `RUNNER_TEMP`**, never to the workspace, so no artifact step can archive it, and the job shreds the key explicitly rather than trusting the runner to vanish.
- **`if: github.repository == …`** stops a fork running the release workflow at all.
- **Third-party actions are pinned to commit SHAs, not tags.** A tag is mutable, because whoever controls an action's repository can repoint `v4` at new code, and that code runs on the runner with the signing secrets in scope. A SHA cannot be moved. Re-pin deliberately when updating, and read what changed.
- **The secrets are environment-scoped with a required reviewer.** Any workflow that asks can read a repository secret, so scoping them this way means a human has to approve a workflow change that starts using them.
- Releases are **drafts**, and the updater ignores drafts and pre-releases, so an accidental merge cannot ship anything to anyone.

## The leak paths, and what covers each

Encryption at rest is not the interesting part — GitHub does that already. These are the ways signing keys actually escape:

| How it leaks | Covered by |
|---|---|
| Untrusted fork code runs with the secrets in scope. | CI triggers on `pull_request` and never on `pull_request_target`. |
| A workflow change starts printing the key. | The secrets are environment secrets, and the environment has a required reviewer. |
| A hijacked action tag runs new code. | Actions are pinned to commit SHAs. |
| The key is archived as a build artifact. | The job writes it to `RUNNER_TEMP` and never to the workspace. |
| The key survives the job. | An `always()` step runs `shred` on it. |
| A fork runs the release workflow. | The `if: github.repository == …` guard stops it. |
| Somebody commits it by accident. | `.gitignore` blocks it, and GitHub push protection catches it. |
| It is echoed into the logs. | No step prints it, and GitHub also masks known values. |

Two more things are worth turning on in the browser, because no workflow can do them for you. The first is **branch protection on `main`**, so that releases only come from reviewed merges, and the second is **secret scanning with push protection**, which is free on public repositories.

None of this covers anyone who has write access to the repository, and none of it covers your own machine. The key is only ever as safe as those two things.

## Cutting a release

1. Bump `appVersionName` in `app/build.gradle.kts`. It is the single source of truth, because the workflow reads it for the tag and the updater compares against it.
2. Merge `dev` into `main`.
3. CI builds the APK, signs it, verifies the signature, and drafts `vX.Y.Z` with the APK attached.
4. Read the generated notes, then publish the draft.

`versionCode` comes from the workflow run number, so it always increases without anyone having to remember. That matters, because Android rejects a downgrade.

## Updates in the app

The updater lives under Settings → Updates. It checks the latest release when the screen opens, and that is the only time it touches the network.

The user can install an available update or **skip** it, and a skipped version stays skipped until somebody presses "Check for updates" deliberately. Nothing installs on its own. The download goes to app-private cache, where nothing else on the device can rewrite it, and Kern hands it to Android's `PackageInstaller`, which shows its own confirmation. So the user says yes twice, and the platform still verifies the signature before it does anything.

The implementation is in `runtime/Updates.kt`, `runtime/InstallReceiver.kt` and `ui/UpdateSection.kt`.

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

```
base64 -w0 release.jks        # the value for KEYSTORE_BASE64
```

Four repository secrets, under Settings → Secrets and variables → Actions:

| Secret | Value |
|---|---|
| `KEYSTORE_BASE64` | The base64 of `release.jks` |
| `KEYSTORE_PASSWORD` | Keystore password |
| `KEY_ALIAS` | `kern` |
| `KEY_PASSWORD` | Key password |

Then delete the local base64 — it is the key in another coat.

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
- Releases are **drafts**, and the updater ignores drafts and pre-releases, so an
  accidental merge cannot ship anything to anyone.

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

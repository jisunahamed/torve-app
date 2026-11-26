# Torve AI Android releases

## Signing setup completed for this repository

The owner confirmed this is the first production signing key. A permanent RSA
3072-bit JKS key with alias `upload` was generated outside the repository at
`%USERPROFILE%\TorveSigning\my-upload-key.jks`. All three repository secrets below
were configured and their names were verified through GitHub CLI. Do not generate
another key for future releases.

The public certificate SHA256 fingerprint is:

```text
E5B0406C0F92A7923F92437DF63C4F9A063A6D446BB4301CCD69E65604B8838F
```

The generated password is stored as a Windows DPAPI-encrypted SecureString in
`%USERPROFILE%\TorveSigning\password.dpapi.xml`. Directory access is restricted to
the current Windows account. DPAPI recovery depends on that Windows account and
machine; copying this XML file alone is not a portable password backup. Store the
password in your password manager and keep an encrypted offline keystore backup
before depending on this key for production updates.

To transfer the password privately to a password manager, run this in a private
PowerShell session on the same Windows account and machine. It copies the value
to the clipboard without printing it; clear the clipboard after pasting:

```powershell
$keyDir = Join-Path $env:USERPROFILE 'TorveSigning'
$secure = Import-Clixml "$keyDir\password.dpapi.xml"
$credential = [Net.NetworkCredential]::new('', $secure)
Set-Clipboard -Value $credential.Password
# Paste into the password manager, then run:
Set-Clipboard -Value ''
Remove-Variable credential, secure
```

Clipboard history or synchronization may retain copies; use a private password
manager import method if those features are enabled. Both configured signing
passwords currently use this saved password. Keep backups separate from the
repository and verify that you can restore the keystore and certificate.

## One-time setup

Merge the release automation changes into `main` before making a new release tag.
Tags must include the complete wrapper and project fixes. Historical tags do not
gain files when main changes. Release names use `vMAJOR.MINOR.PATCH` with an
optional prerelease suffix (for example `v1.0.0-beta.1`).

Repository administrators must enable GitHub Actions and allow the pinned official
actions. The release job requests `contents: write` using the automatic
`GITHUB_TOKEN`; no personal access token is required. A maintainer with permission
to publish releases starts releases. An administrator configures repository secrets.

Keep the existing production keystore if this app has ever been distributed.
Replacing that key prevents existing users from installing updates over their app.
Use alias `upload`. Do not use a debug key. Back up the keystore and passwords in
separate secure locations, including an offline backup; test restoration.

Only after confirming there is no previous production key, generate a permanent
key **outside the repository**. With JDK 17 on PATH, PowerShell:

```powershell
$keyDir = Join-Path $env:USERPROFILE 'TorveSigning'
New-Item -ItemType Directory -Force $keyDir
keytool -genkeypair -keystore "$keyDir\my-upload-key.jks" -storetype JKS `
  -alias upload -keyalg RSA -keysize 3072 -validity 10000
# Answer password and certificate prompts interactively.
keytool -list -v -keystore "$keyDir\my-upload-key.jks" -alias upload
```

Record the certificate SHA256 fingerprint (public information). Never commit the
keystore, passwords, Base64 text, or environment files. A Play upload key may differ
from the Play app-signing key: direct APK updates must use the installed app's key.

Open repository **Settings → Secrets and variables → Actions → New repository
secret**, and configure these exact names:

| Name | Value |
| --- | --- |
| `ANDROID_KEYSTORE_BASE64` | Base64 encoding of the permanent binary keystore |
| `ANDROID_KEYSTORE_PASSWORD` | Keystore password |
| `ANDROID_KEY_PASSWORD` | Private key password for alias `upload` |

Encode without printing the value in your terminal:

```powershell
$keyPath = Join-Path $env:USERPROFILE 'TorveSigning\my-upload-key.jks'
$encoded = [Convert]::ToBase64String([IO.File]::ReadAllBytes($keyPath))
Set-Clipboard -Value $encoded
# Paste into ANDROID_KEYSTORE_BASE64, then clear the clipboard.
Set-Clipboard -Value ''
Remove-Variable encoded
```

Alternatively, after `gh auth login`, pipe the encoding directly to GitHub:

```powershell
[Convert]::ToBase64String([IO.File]::ReadAllBytes($keyPath)) |
  gh secret set ANDROID_KEYSTORE_BASE64 --repo jisunahamed/torve-app
gh secret set ANDROID_KEYSTORE_PASSWORD --repo jisunahamed/torve-app
gh secret set ANDROID_KEY_PASSWORD --repo jisunahamed/torve-app
```

The workflow exports a certificate from this keystore before building and compares
the signed APK's SHA256 certificate digest against it. Review that logged public
fingerprint against your backed-up production certificate when setting up secrets.
Temporary runner key files are deleted with an `always()` cleanup step. Hosted
runners are ephemeral; abrupt runner termination can prevent cleanup execution.

## Publish a release

1. Review and increment `versionCode` and `versionName` in `app/build.gradle.kts`
   as appropriate, then merge. Current values are `1` and `1.0.0`. Tags only control
   asset names; they do not change Android version metadata. Every app update
   needs a larger versionCode, within Android's 2,100,000,000 limit.
2. Open **Releases → Draft a new release** in GitHub. Choose a new version tag
   such as `v1.0.0`, targeting the reviewed commit on main.
3. Enter the title and release notes, then select **Publish release**.
4. Watch **Actions → Release signed Android APK**. The `release: published`
   event builds the exact tag, using JDK 17, Gradle 9.3.1, Android platform 36.1,
   and build tools 36.0.0. It runs debug unit tests and builds `assembleRelease`.
5. After successful verification, the same release receives
   `Torve-AI-v1.0.0.apk` and `Torve-AI-v1.0.0.apk.sha256` under **Assets**.
   Download both. The workflow does not change the release title or description.

The job verifies package `com.aistudio.torveai.kxtrva`, min SDK 24, target SDK 36,
non-debuggable status, signing certificate, and signature. It verifies checksums
locally and again after downloading the uploaded release assets.

Verify a download in PowerShell (run in its download directory):

```powershell
$apk = 'Torve-AI-v1.0.0.apk'
$expected = (Get-Content "$apk.sha256").Split(' ')[0]
$actual = (Get-FileHash $apk -Algorithm SHA256).Hash
if ($actual -ine $expected) { throw 'APK checksum mismatch' }
# With Android SDK build tools on PATH:
apksigner verify --verbose --print-certs $apk
aapt dump badging $apk
```

## Troubleshooting and reruns

### AI Studio cannot push workflow files

The error "Insufficient permissions to push workflow files" means the AI Studio
GitHub integration lacks permission to edit `.github/workflows/`. This is separate
from `permissions: contents: write`, which lets a running Actions job upload assets.
Changing that YAML permission cannot fix the integration's push authorization.

Open GitHub **Settings → Applications**, find Google AI Studio under installed
GitHub Apps or authorized applications, and review its repository access and any
pending permission update. Re-authorize it from AI Studio and approve the requested
workflow permission for this repository, then retry the push. An organization
owner may need to approve the updated installation permissions.

If the integration cannot request that permission, push app source changes with
workflow files unchanged, then add the reviewed workflow through GitHub's web
editor or an independently authorized Git client and open a pull request. Include
the complete wrapper and supporting fixes; moving a YAML file outside
`.github/workflows/` disables it. Avoid having AI Studio overwrite the reviewed
workflow with an older copy on the next sync.

The previously suggested `assembleDebug`/Actions-artifact YAML is useful only for
debug builds. It does not sign a production release, react to a published release,
or attach APK/checksum assets to GitHub Releases. The production entry point is
`.github/workflows/release-apk.yml` described above. Updating an action's Node
runtime does not grant the AI Studio app additional GitHub permissions.

References: [GitHub App workflow permission](https://docs.github.com/en/apps/creating-github-apps/registering-a-github-app/choosing-permissions-for-a-github-app)
and [workflow token permissions](https://docs.github.com/en/actions/security-for-github-actions/security-guides/automatic-token-authentication).

### Build and upload failures

- Missing signing secrets: configure all three secrets above. Invalid Base64,
  a wrong password, missing alias, or an incorrect key produces a failed job;
  inspect the first failing step. Never paste secrets into logs or issues.
- Dependency or source failures: read the complete Gradle error. Reproduce with
  `./gradlew --no-configuration-cache :app:testDebugUnitTest :app:assembleRelease`
  (PowerShell: ` .\gradlew.bat`), setting `KEYSTORE_PATH`, `STORE_PASSWORD`, and
  `KEY_PASSWORD` privately in the process environment. Do not put values in commands
  saved to shell history. The existing Gradle signing configuration is preserved.
- SDK errors: check `sdkmanager --list` for `platforms;android-36.1` and
  `build-tools;36.0.0`, accept licenses, and check JDK 17. CI uses the Android SDK
  command-line tools supplied by the GitHub-hosted ubuntu-24.04 runner image.
- Debug builds separately require the ignored root `debug.keystore` because of
  the existing AI Studio configuration. This file is never used for release signing.
- Google Services currently warns when `google-services.json` is missing;
  Firebase libraries are commented out. `.env.example` supplies the Secrets plugin
  defaults; the current source does not require a build-time API secret. User API
  credentials are entered at runtime. Never embed a private service API key.
- Upload failures: check Actions permissions, release existence, token policy,
  immutable release settings, and network availability. Immutable releases may
  prohibit uploads/replacements; this workflow requires editable release assets.
- Rerun a failed run from **Actions → run → Re-run failed jobs**. Alternatively
  choose **Run workflow** and enter the exact existing published release tag.
  Manual dispatch requires the workflow on the default branch. It never creates
  a release. Per-tag concurrency serializes runs; `--clobber` replaces only the
  two named assets on that release. Replacement is not atomic: rerun if interrupted
  between uploads. Other historical releases and assets are untouched.
- Source fixes require a new reviewed commit and release tag. Do not move an
  already published production tag to hide a failed build.

No public release should be created just to test the pipeline. Use an explicitly
approved existing test release for an end-to-end upload check.

For a build/signature check without publishing, manually dispatch the workflow
with `validation_only: true`, select the implementation branch/ref, and enter
`v0.0.0-ci` as the asset label. This mode builds the selected workflow commit,
checks the permanent signing fingerprint and APK metadata, and skips release
lookup/upload. The signed APK and checksum are retained as the Actions artifact
`torve-release-validation-<run_id>` for seven days. It does not prove that GitHub
Release asset upload works. Normal published-release runs always check out the
exact release tag.

The unit-test task is `:app:testDebugUnitTest`: the baseline AGP configuration did
not expose `:app:testReleaseUnitTest`. These tests do not turn the final APK into
a debug build; the distributed APK is still produced by `:app:assembleRelease`
and signed with the permanent production key.

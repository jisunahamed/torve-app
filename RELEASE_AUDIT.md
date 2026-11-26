# Android release automation audit

Audit baseline: `3f2c3c8` (`main` at initial checkout). Audit date: 2026-11-27.
Implementation branch: `jisun/android-release-automation`.

## Repository findings

| Area | Baseline finding and implementation |
| --- | --- |
| Workflow location | `.github/workflows/android-apk.yml` already existed in the correct directory. There was no root-level `android-apk.yml` to migrate. The existing workflow built only debug APKs and retained Actions artifacts; it is replaced by the release workflow. |
| Gradle wrapper | Properties existed, but scripts and wrapper JAR were absent. Official Gradle 9.3.1 was downloaded and verified against its published SHA256, then used to generate the wrapper. Distribution checksum and executable bit are included. |
| Build versions | Baseline AGP 9.1.1, Gradle 9.3.1, Kotlin catalog 2.4.21, KSP 2.3.5, Room 2.7.0. Compatibility must be confirmed by the actual project build, not inferred from version numbers alone. |
| Java | Java source/target compatibility remains 11; the build runtime is JDK 17. These settings serve different purposes. |
| Android SDK | Compile SDK is `release(36)` with minor API 1; target 36 and minimum 24. Google's live package catalog contained `platforms;android-36.1` and build tools 36.0.0. |
| Signing | Release already used `KEYSTORE_PATH`, `STORE_PASSWORD`, `KEY_PASSWORD`, and alias `upload`. That configuration is preserved. Debug separately references an ignored root `debug.keystore`; it is never the production key. |
| App identity/version | `com.aistudio.torveai.kxtrva`, versionCode 1, versionName 1.0.0. Tag names affect asset names only. |
| Environment | `.env.example` contains only commented example API-key instructions; `.env` is ignored. Source search found no required `BuildConfig` API key or build-time service credential. Runtime authentication and network integrations are retained. |
| Firebase | Google Services plugin is configured to WARN if `google-services.json` is missing. Firebase dependency entries are commented out. No Google Services JSON was present. |
| Manifest/resources | Manifest, Compose activity/application, foreground service, icons, fonts, themes, backup XML, and network-security XML are present. Cleartext exceptions cover localhost preview; external network defaults use system trust and disallow cleartext. No app resources or features were removed. |
| ProGuard | Rule file is present and release minification remains disabled. |
| Tests | Unit tests cover arithmetic, Robolectric resource access, PKCE/SSE parsing, sandbox path containment, and local-server/tool execution. One stale resource assertion expected "My Application" although the app name is "Torve AI"; corrected. Instrumentation has an application-ID check; no emulator run is claimed. |
| Secret exclusion | Ignore rules now cover JKS/keystore/PKCS12 files and distribution output. No private key or signing password is added to Git. |

## Release behavior implemented

Publishing a GitHub Release triggers the new workflow. Manual dispatch accepts an
existing published tag, or supports an explicit validation-only build of the
selected workflow commit without creating/uploading a release. Normal release
runs check out the exact tag, use the permanent signing secrets,
runs debug unit tests and `assembleRelease`, checks signature/certificate and APK
metadata, generates SHA256, and uploads the APK and checksum to the same release.
It downloads uploaded assets and rechecks the checksum. Per-tag concurrency and
named-asset replacement permit reruns. No title/body modification or release
creation is part of the workflow.

Missing secrets fail early. Signing files are temporary and cleaned with
`always()`. The release job alone has `contents: write`; checkout credentials are
not persisted. Official setup actions are pinned to immutable commits.

## Signing setup

The owner explicitly confirmed this is the first production key. A permanent
RSA3072 JKS key with alias `upload` was generated outside the checkout under
`%USERPROFILE%\TorveSigning`. A DPAPI-protected password file is local to that
Windows account/machine. Repository secrets `ANDROID_KEYSTORE_BASE64`,
`ANDROID_KEYSTORE_PASSWORD`, and `ANDROID_KEY_PASSWORD` were configured, and their
presence was verified with `gh secret list` without disclosing values.

Public signing certificate SHA256:

```text
E5B0406C0F92A7923F92437DF63C4F9A063A6D446BB4301CCD69E65604B8838F
```

The owner must preserve a portable password-manager backup and an encrypted
offline keystore backup. See [RELEASE_GUIDE.md](RELEASE_GUIDE.md).

## Validation status

- Official Gradle 9.3.1 executed successfully under JDK 17.
- Wrapper generation completed successfully from that distribution.
- `actionlint` found an invalid job-level `runner` context; it was corrected.
  A subsequent workflow lint check passed at that stage.
- Project configuration, debug/release compilation, unit tests, final APK inspection,
  and final workflow verification are being handled by the build/review work.
  Their results must be recorded before calling this production-ready.
- No public release was created or published by this implementation work. No
  successful release upload or downloadable release asset is claimed here.

## References checked

- [AGP 9.1.1 compatibility](https://developer.android.com/build/releases/agp-9-1-0-release-notes): Gradle 9.3.1, JDK 17, default build tools 36.0.0.
- [Android package catalog](https://dl.google.com/android/repository/repository2-3.xml): exact platform/build-tools package availability.
- [Kotlin releases](https://kotlinlang.org/docs/releases.html): Kotlin 2.4.21 release availability.
- [GitHub App permissions](https://docs.github.com/en/apps/creating-github-apps/registering-a-github-app/choosing-permissions-for-a-github-app): Workflows permission is required to edit workflow files.

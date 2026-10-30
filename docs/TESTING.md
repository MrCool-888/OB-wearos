# Testing and release

## Local checks

Use JDK 17 and Android SDK platform 35. Set `ANDROID_HOME` (or `ANDROID_SDK_ROOT`) to the installed SDK, and create a machine-local `local.properties` only if Gradle does not already find it.

```powershell
.\gradlew.bat :protocol:test :wear:testDebugUnitTest
.\gradlew.bat :wear:lintRelease :wear:assembleDebug
```

The watch debug artifact (`wear-debug-unsigned.apk`) is unsigned and does not require phone, Morphe, stock APK, or signing-key inputs. CI runs these checks on pushes, pull requests, and manual workflow runs, then uploads the unsigned debug APK.

## Signed watch release

The signed build is a separate manual workflow action. Enable the `signed_release` input only when preparing a watch release and only with the existing `KEYSTORE_PROPERTIES` and `RELEASE_KEYSTORE` GitHub secrets. They must contain the same release key used for the phone patch. The workflow produces an artifact; it does not create or publish a GitHub Release. Do not rotate or replace the key for an update.

## Protocol changes

The protocol module is duplicated in the private phone patch repository. Any v4 wire-contract change must be applied to both copies and pass both repositories' protocol tests.

## Historical validation

[`HISTORICAL-VALIDATION-3.0.0.md`](HISTORICAL-VALIDATION-3.0.0.md) records a prior integrated watch/phone/Morphe validation. It is retained as historical evidence only; this watch-only checkout cannot reproduce its phone-side, Morphe, or stock-APK checks. Physical-device messaging and Data Layer behavior still require explicit device validation for the release being prepared.

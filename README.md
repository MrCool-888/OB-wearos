# OpenBubbles Wear

Wear OS companion for OpenBubbles. This repository builds the watch app and its shared protocol only; it does not build or patch a phone APK. The phone-side Morphe patch lives in the private [mrcool-patches repository](https://github.com/MrCool-888/mrcool-patches).

The watch package identity is `com.openbubbles.messaging`. Keep it and its release signing key aligned with the phone patch so the Wear Data Layer can authenticate the companion.

## Build and test

Use JDK 17 and an Android SDK with platform 35 installed. `local.properties` is machine-specific and is not checked in.

```powershell
.\gradlew.bat :protocol:test :wear:testDebugUnitTest
.\gradlew.bat :wear:lintRelease :wear:assembleDebug
```

The default CI builds and uploads an unsigned debug APK. A signed release build is available only through a manual workflow run using the existing `KEYSTORE_PROPERTIES` and `RELEASE_KEYSTORE` repository secrets. Those secrets must represent the same key already used by the phone patch; do not generate or substitute a signing key for an update.

The `testnotifier` module is a local test utility for producing sample OpenBubbles notifications. It is not part of the watch APK or CI release artifact.

See [architecture and split maintenance](docs/ARCHITECTURE.md), [testing and release](docs/TESTING.md), and [historical 3.0.0 validation](docs/HISTORICAL-VALIDATION-3.0.0.md).

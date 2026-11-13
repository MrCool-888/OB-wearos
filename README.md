# OpenBubbles Wear

Wear OS companion for OpenBubbles. This repository builds the watch app and its shared protocol only; it does not build or patch a phone APK. The phone-side Morphe patch lives in the [mrcool-patches repository](https://github.com/MrCool-888/mrcool-patches).


The watch package identity is `com.openbubbles.messaging`. Keep it and its release signing key aligned with the phone patch so the Wear Data Layer can authenticate the companion.

## Message actions

With a matching phone bridge build, hold an iMessage bubble for quick reactions or Reply. Your reactions are highlighted; select the same reaction again to remove it. Swipe an incoming bubble left or an outgoing bubble right to reply. Replies show their quoted original and reply count.

With phone patches **v1.3.3-dev.1** or newer, the hold menu also offers **Unsend** for your own iMessages within two minutes. The phone validates the target and confirms the action before the watch replaces the bubble with “Message unsent.” Older recipient software may still retain a message. Unsend needs a connected phone and an enabled Wear bridge.

Groups without a custom picture use a cluster of member contact or shared profile photos, with individual initials when a photo is unavailable. The home screen keeps the Messages heading’s text size with less surrounding space.

In watch Settings, use **Swipe to reply** to choose between swiping toward the edge or the center. The choice applies immediately.

The bottom **Send message** button starts an ordinary message. Reaction notifications follow the existing phone and watch notification settings.

## Build and test

Use JDK 17 and an Android SDK with platform 35 installed. `local.properties` is machine-specific and is not checked in.

```powershell
.\gradlew.bat :protocol:test :wear:testDebugUnitTest
.\gradlew.bat :wear:lintRelease :wear:assembleDebug
```

The default CI builds and uploads an unsigned debug APK. A signed release build is available only through a manual workflow run using the existing `KEYSTORE_PROPERTIES` and `RELEASE_KEYSTORE` repository secrets. Those secrets must represent the same key already used by the phone patch; do not generate or substitute a signing key for an update.

The `testnotifier` module is a local test utility for producing sample OpenBubbles notifications. It is not part of the watch APK or CI release artifact.

See [architecture and split maintenance](docs/ARCHITECTURE.md), [testing and release](docs/TESTING.md), and [historical 3.0.0 validation](docs/HISTORICAL-VALIDATION-3.0.0.md).

OpenBubbles Wear is an independent project and is not affiliated with OpenBubbles.

## License

OpenBubbles Wear is licensed under the [GNU General Public License v3.0](LICENSE). Third-party files retain their existing license notices.

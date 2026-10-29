# Morphe Wear bridge (companion 2.1.1 / patch 1.1.0 testing build)

Experimental, local build. **Not yet validated against a signed-in OpenBubbles backend on a
physical phone/watch.** The notification-only integration remains available without this patch.

For the ready-to-test signed APK set and device setup, follow [Install 2.1.1](INSTALL-2.1.1.md).

## What the patch adds

- Read the existing chat directory, including quiet chats and groups, without Shizuku.
- Load the latest 80 messages in an existing chat from OpenBubbles, not just its notifications.
- Send text and mark read through OpenBubbles' backend while the phone is locked.
- Choose a contact or enter a number/email on the watch. Selecting it does not send anything;
  OpenBubbles creates a one-to-one chat on the first send. Creating groups is not supported.
- Refresh a visible chat/list while the watch UI is resumed, without permanent background polling.
- View images/GIFs already downloaded in OpenBubbles through an authenticated read-only provider.
- Journal send IDs inside OpenBubbles as well as the companion, failing closed on uncertain replays.

OpenBubbles still owns sign-in, encryption and delivery. The patch injects one bound Android
service; it does not export existing private components, add accessibility automation, or copy
account credentials to the companion.

**Privacy change:** direct history includes message text even when notification previews are hidden.
Live incoming alerts still use the notification listener. Quiet chats refresh while visible or on
request, not through a permanent database subscription. Direct media reads only already-downloaded
files inside OpenBubbles' attachments directory; it cannot fetch missing attachments from Apple.
Backend history is authoritative: notification-derived message hashes are removed rather than
duplicating the backend's GUID messages. Distinct repeated messages keep their distinct real IDs.

The phone requests at most 100 directory entries and retains up to 60 history-free chats alongside
40 chats with history. There is no directory/history paging yet. Large histories are trimmed for
the Wear Data Layer. Send text remains capped at 800 characters.

## Supported input

Only the supplied **native OpenBubbles 2.0.0, versionCode 20002289**, package
`com.openbubbles.messaging`, is supported. It uses `com.localob.messaging` backend classes; this
patch is not for an older Flutter APK with the same version label.

Base APK SHA-256 (not the XAPK or its architecture split):

```text
a382a9cbef6a83288f11ed8c4763b56c5fedb24ee6505cd21b6bbb545cdc6cc0
```

`patchApk` checks that hash. The patch itself also checks the package/version, application class,
required backend signatures, and absence of an existing bridge before adding its extension.
Do not force it onto another release.

## Build

Build the matching phone/watch apps from the repository root with JDK 17:

```powershell
.\gradlew.bat :protocol:test :phone:testDebugUnitTest :wear:testDebugUnitTest
.\gradlew.bat :phone:assembleRelease :wear:assembleRelease
```

Configure local release signing as described in [README.md](../README.md#release-builds).
The patch authenticates `com.openbubbles.wear` by the **companion signing certificate**, not by
its package name alone. `CallerTrust.java` currently pins the local release certificate:

```text
e5d2b8f7a0df6015f4ace1ab06c600797557fd9fb52e04930786b792c6dd42ae
```

For your own signing key, replace `COMPANION_CERT_SHA256` before building the patch. Do not remove
the certificate check. Ordinary debug-signed companions are intentionally rejected. Phone and
watch must share the same certificate/application ID for Google's Data Layer as well.

The separate build under `morphe/` needs:

- JDK 17 for Gradle and Java 21+ for the Morphe desktop CLI (`-PmorpheJava=...`).
- `ANDROID_HOME` or `ANDROID_SDK_ROOT`, with `platforms/android-35` and build tools `35.0.0` for signing.
- The original base APK at `work/com.openbubbles.messaging.apk`, or `-PinputApk=<absolute path>`.
- A compile-only classes JAR derived from that exact APK with dex2jar 2.4, at
  `work/openbubbles-classes-v52.jar`, or `-PappClassesJar=<absolute path>`. This is never bundled.

To prepare the local classes JAR, run dex2jar on the base APK:

```powershell
work\tools\dex-tools-v2.4\d2j-dex2jar.bat -f -o work\openbubbles-classes.jar work\com.openbubbles.messaging.apk
```

dex2jar emits some Java interfaces with an old class-file version. Run this Python snippet from
the repository root to normalize the **compile-only** JAR (not the APK):

```python
import zipfile
with zipfile.ZipFile('work/openbubbles-classes.jar') as source:
    with zipfile.ZipFile('work/openbubbles-classes-v52.jar', 'w') as target:
        for entry in source.infolist():
            data = source.read(entry.filename)
            if entry.filename.endswith('.class') and len(data) >= 8:
                if int.from_bytes(data[6:8], 'big') < 52:
                    data = data[:6] + (52).to_bytes(2, 'big') + data[8:]
            target.writestr(entry, data)
```

Then:

```powershell
morphe\gradlew.bat -p morphe test assemble
morphe\gradlew.bat -p morphe packageInstallableApks verifyInstallableApks '-PmorpheJava=C:\path\to\jdk-21'
```

The build downloads official Morphe Desktop 1.19.1 and verifies its pinned SHA-256. It supplies
Patcher 2.0.1 as a compile-only dependency; no GitHub Packages credentials are required.
DEX compilation uses pinned R8/D8 9.1.31, which supports the patch's Kotlin 2.4 metadata; the old
D8 bundled with SDK build tools 35 is not used to compile the patch bundle.

Outputs:

- `morphe/build/libs/openbubbles-wear-patches-1.1.0.mpp`: JVM patch classes, Android patch DEX,
  injected extension DEX and patch metadata in one bundle.
- `morphe/build/patched/openbubbles-wear-unsigned.apk`: patched **base APK**, aligned but unsigned.
- `morphe/build/installable/`: complete release-signed four-APK ARM64 set and checksums.
- `phone/build/outputs/apk/release/phone-release.apk`: companion 2.1.1.
- `wear/build/outputs/apk/release/wear-release.apk`: companion 2.1.1.

The packaging task explicitly signs the patched base and matching splits with the existing release
key; it never installs/uninstalls or changes app data. Morphe Desktop application has been
tested locally; importing/applying the bundle in Morphe Manager on Android has not been tested.

## Installation precautions

**Do not uninstall your working OpenBubbles installation just to try this preview.** A patched
APK needs a new signing key, so Android normally refuses an update over the official app.
Uninstalling erases local messages, account state and keys. Native OpenBubbles 2.0 removed the
legacy local Messages Backups option; this project has no verified complete backup/restore path
for replacing that installation. Keep stock intact and use a spare device for this patch.
The supplied XAPK also has an architecture split: preserve/sign the matching required splits
consistently; the unsigned base APK is not a complete one-file installation recipe.

After preparing the complete signed APK set on a test device:

1. Install the matching release-signed companion 2.1.1 apps on phone and watch.
2. Open patched OpenBubbles and complete its normal setup; leave its backend running.
3. Open the phone companion. **Wear patch** should show **Connected** in diagnostics.
4. Keep **Relay to watch** on. Tap **Find chats in OpenBubbles**, then sync the watch.
5. Open a quiet chat on the watch and refresh it. Try sending and mark-read while locked.
6. To test a new conversation, choose a contact or enter an address; only an explicit send creates it.
7. Grant notification access for incoming alerts; direct media uses the authenticated provider.
8. Open the phone companion **Wear patches** setting to enable or disable direct bridge use. Disabling it falls back to notification actions while keeping the patched OpenBubbles installation intact.

If the patch is absent the companion uses existing notification actions, with optional Shizuku
discovery as before. A backend send failure/timeout is **not** retried through another send path:
it may already have been accepted. Check OpenBubbles before manually retrying an uncertain send.

## Verification and remaining checks

Verification includes protocol/phone/watch tests, tests of the injected service helpers and media
provider, manifest safety, and fail-closed packaging checks. Build results are recorded with the
2.1.1 testing artifacts. These remain distinct from a signed-in physical-device delivery test.

Automated coverage includes wire compatibility, directory retention/metadata, alias and outgoing
bubble migration, Messenger response validation/timeouts, private-history relay gating, direct
sending, manifest safety and version rejection. Companion integration tests use a fake Messenger
backend; injected-code tests use Robolectric and the stock compile-only interfaces. They do **not**
prove Apple delivery or physical Wear Data Layer transport.

The latest local testing set is copied into ignored `dist/OpenBubblesWear-2.1.1/`:

- `phone/` (four signed OpenBubbles APKs, plus an optional one-file universal build)
- `OpenBubblesWear-Phone-2.1.1.apk`
- `OpenBubblesWear-Watch-2.1.1.apk`
- `OpenBubblesWear-Morphe-1.1.0.mpp`
- `SHA256SUMS.txt` and installation instructions

Before a public release, test on a spare phone/watch:

- Matching release certificate accepted; debug/wrong-certificate callers rejected.
- App launch, backend startup, process death/rebind and secure-lock sends with real OpenBubbles.
- Incoming/outgoing/group history, unread counts and mark-read against the real database.
- Exact-once send behavior when a service disconnects or a backend operation times out.
- Required APK splits, signing, account recovery and Morphe Manager import/application.
- R8 release apps across the physical Wear Data Layer.

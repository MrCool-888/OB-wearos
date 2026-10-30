# OpenBubbles Wear 2.1.8 installation

This is a coordinated update for native OpenBubbles 2.0.0 (versionCode 20002289). Install the
patched OpenBubbles APK containing Morphe patch **1.2.5**, and both companion apps **2.1.8**. The root
OpenBubbles version remains 2.0.0; use only the matching patched set supplied for this release.

## Update an existing patched installation

Keep the installed APK format. If OpenBubbles is installed as a universal APK, update that APK. If
it is installed as a base plus configuration splits, install the complete matching set together.
Do not mix a universal APK and splits. The release bundle's `phone/` directory contains the patched
base and required splits; follow its `INSTALL.txt` and verify against `SHA256SUMS`.

Install the patched OpenBubbles set in place, signed with the same key as the currently installed
patched app. Use the command matching your existing installation:

For an existing universal installation:

```bash
adb -s PHONE_SERIAL install -r phone/openbubbles-wear-patched-universal.apk
```

For an existing split installation:

```bash
adb -s PHONE_SERIAL install-multiple -r \
  phone/openbubbles-wear-patched-base.apk \
  phone/config.arm64_v8a.apk phone/config.en.apk phone/config.mdpi.apk
```

Then install both companions in place using the same signing key as their existing installs:

```bash
adb -s PHONE_SERIAL install -r OpenBubblesWear-Phone-2.1.8.apk
adb -s WATCH_SERIAL install -r OpenBubblesWear-Watch-2.1.8.apk
```

The supplied OB APKs already contain patch 1.2.5; do not apply the MPP again. The MPP is an
alternative for building from the supported original APK. Keep the package and signing identity
expected by your existing setup. Do not
uninstall OpenBubbles or either companion, clear app data, or bypass a signature mismatch. A
signature mismatch means the supplied APK is not signed with the installed app's key; stop and use
the matching signed release set.

## Setup and refresh

1. Open the phone companion and confirm **Connected · Ready** when the patched service is reachable.
2. Keep **Relay to watch** enabled and grant notification access for live alerts.
3. Open the watch companion and choose **Find chats in OpenBubbles** to load the paginated directory.
   Native UUID and exact 72-character hexadecimal chat IDs are supported; group status is sent
   explicitly in the directory metadata.
4. Open a chat with a photo or GIF. If the patch had not downloaded the image yet, the first tap
   fetches it from OpenBubbles and then shows it; the phone may take several seconds on a slow
   connection. If OpenBubbles still marks the attachment as downloaded but its cached file is gone,
   the patch re-downloads it automatically. A second tap opens the full-screen viewer.
5. Unnamed groups show their participants' saved contact names. If a group still shows a raw
   `chat<number>` title, refresh the chat list so the phone sends the resolved title.
6. If names are missing, confirm OpenBubbles has Contacts access, then refresh the chat list. Without
   that permission the watch keeps phone numbers and email addresses.
7. Open a chat and refresh its history. If refresh fails, the watch reports the error; cached history
   may still be shown as fallback and is not evidence of a successful refresh.

Sending and Apple delivery while rate limited remain unverified; do not resend old attempts just to
test history sync. The patch does not change Apple's throttling or automatically resend uncertain
messages.

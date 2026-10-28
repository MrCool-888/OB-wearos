# OpenBubbles Wear 2.1.1 — patched phone + watch testing build

This build targets the core Google Messages Wear-style workflow, **using OpenBubbles on the
phone for all transport**: conversations, recent history, existing groups, new one-to-one chats,
voice/keyboard/quick replies, read state, alerts, downloaded photos/GIFs and an offline outbox.
It is not a Google Messages client and does not independently connect the watch to Apple.

## Before removing the Play Store app

**Uninstalling OpenBubbles erases its local data.** Export/back up using OpenBubbles' supported
tools and confirm how you will restore messages and set up your account/Mac registration again.
This project cannot recover app-private keys or data after uninstalling. Do not proceed if you
cannot complete normal OpenBubbles setup again.

The patched app uses a different signing key. It cannot update over Play Store OpenBubbles.
Once you choose to remove stock and install this build, future patched updates must use the same
local release key. Switching back to the Play Store version can require another uninstall.
No build or verification task uninstalls, clears data, bypasses signatures, or changes your account.

## Files and requirements

Use the **entire** `dist/OpenBubblesWear-2.1.1/` testing directory:

| File | Install on |
| --- | --- |
| `phone/openbubbles-wear-patched-base.apk` | Phone, together with all three splits below |
| `phone/config.arm64_v8a.apk` | Phone |
| `phone/config.en.apk` | Phone |
| `phone/config.mdpi.apk` | Phone |
| `OpenBubblesWear-Phone-2.1.1.apk` | Phone, separate Wear bridge app |
| `OpenBubblesWear-Watch-2.1.1.apk` | Watch |

The `.mpp` file is the rebuildable Morphe patch bundle, **not** an Android app to install.
Do not use the older unsigned base-only preview in `dist/`.

- ARM64 phone running Android 12L / API 32 or newer; the supplied APK set is English/MDPI.
- Wear OS 3 / API 30 or newer, paired normally with that phone; Google Play services on both.
- Normal OpenBubbles setup/sign-in and its messaging backend must work on the phone first.
- No root, Shizuku or accessibility service is required for the direct patched path.
- The watch still needs its paired phone/backend reachable; this is not standalone watch iMessage.

## Install using ADB

Enable USB/wireless debugging as appropriate, connect both devices, and identify their exact
serials using `adb devices -l`. Replace `PHONE_SERIAL` and `WATCH_SERIAL` below. Never send the
phone APKs to the watch or vice versa.

After you have backed up and handled removal of the stock installation yourself, run these commands
from `dist/OpenBubblesWear-2.1.1/`:

```powershell
adb -s PHONE_SERIAL install-multiple -r phone/openbubbles-wear-patched-base.apk phone/config.arm64_v8a.apk phone/config.en.apk phone/config.mdpi.apk
adb -s PHONE_SERIAL install -r OpenBubblesWear-Phone-2.1.1.apk
adb -s WATCH_SERIAL install -r OpenBubblesWear-Watch-2.1.1.apk
```

Install all four OpenBubbles APKs in one transaction. Do not install just the base or mix in any
stock-signed splits. `INSTALL_FAILED_UPDATE_INCOMPATIBLE` means a differently signed installation
is still present; it is not a reason to disable Android security or delete unrelated app data.
An old debug-signed Wear companion may also conflict with its release-signed replacement.

## Setup

1. Open **OpenBubbles** on the phone and complete its normal setup. First send and receive a
   message in the phone app; the Wear bridge cannot fix a disconnected OpenBubbles account.
2. Open **OpenBubbles Wear** on the phone. Keep **Relay to watch** on. Diagnostics should show
   **Wear patch: Connected · Ready**. `NeedsSetup`/`Starting` means the backend is not ready yet.
3. Grant **Notification access** for incoming alerts. This is special notification-listener access,
   not the ordinary App info → Notifications switch. Allow battery/background operation if your
   phone's OEM restricts either OpenBubbles or the companion.
4. Optionally allow **Contacts** to browse the Android address book from the watch.
5. Open **OpenBubbles Wear** → **Wear patches** on the phone to enable or disable direct bridge use. It is on by default; turning it off falls back to notification actions while keeping the patched OpenBubbles installation intact.
6. Open **OpenBubbles Wear** on the watch, tap **Sync now**, then **Find chats in OpenBubbles**.
6. Open a chat. Recent backend history loads even if the chat has never posted a notification.
7. **Reply** offers quick replies and voice/keyboard input. **Find a chat** lets you choose a contact
   or enter a phone number/email. Selecting a new recipient sends nothing; the first explicit send
   creates the one-to-one conversation.
8. Allow watch notifications for extra alerts, or disable **Extra alerts** to avoid duplicates with
   Android's mirrored OpenBubbles alerts.

## What to test

- Existing direct chat and group: correct title, sender labels, message order, no repeated bubbles.
- New contact/typed address: send once; the temporary chat changes to its real OpenBubbles ID.
- Lock the phone, turn its screen off, and send two different replies from the watch.
- Send from the phone while the watch chat is visible: recent history should refresh automatically.
- Receive a message with the watch app closed: alert, tap into chat, voice reply, then mark-read.
- Open a downloaded photo/GIF from an old quiet chat. Its actual bytes should arrive through the
  phone, not an unprotected file share.
- Disconnect the phone: cached chats remain readable and new messages queue. Reconnect and verify
  each queued message sends once. An uncertain send must not be retried automatically; check the
  phone before manually resending.
- Restart the companion and OpenBubbles, then reconnect/sync. Android/OEM background restrictions
  may still require opening OpenBubbles once.

## Scope and privacy

Direct history can reveal text hidden by notification preview settings, and direct media can read
already-downloaded images/GIFs from OpenBubbles. Only the release-signed companion package is
authorized. Messages, credentials and encryption keys are not uploaded to a third-party service.

The directory/history caches are bounded; this is recent history, not an unlimited database browser.
Creating groups, file/video/audio sending, calls, editing/reactions and autonomous watch-side Apple
transport are outside this build. Missing attachments must first be downloaded by OpenBubbles.
The watch/phone applications and the patch must be kept together as a matching signed set.

Automated and packaging checks do not prove Apple delivery or physical Wear Data Layer behavior.
Those require your signed-in phone/watch test; keep the stock app's backup until that succeeds.

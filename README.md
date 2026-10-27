# OpenBubbles Wear

An unofficial Wear OS companion for [OpenBubbles](https://openbubbles.app/). The watch shows
OpenBubbles conversations and can reply, mark them read, or open them on the phone. All message
sending stays in the official phone app; this project never talks to Apple's servers directly.

> This project is not affiliated with OpenBubbles or Apple. It works with the official
> OpenBubbles 2.0.x APK without modifying it.

## How it works

OpenBubbles already builds a `MessagingStyle` notification for each active conversation. That
notification contains recent messages, sender names, attachment flags, and the mutable reply and
mark-read actions. The bridge reads those notifications through Android's notification listener,
caches a copy, and sends it over the Wear OS Data Layer.

```
OpenBubbles (phone)
  └─ MessagingStyle notification
       └─ Phone bridge ── Wear Data Layer ──> Watch app
              ^                                  │
              └──── reply / mark-read ───────────┘
                    (notification PendingIntent)
```

This gives a real companion experience without reimplementing OpenBubbles' encryption, account
setup, or message transport. The trade-off is that the notification is the only data source, so
the feature set is limited to what OpenBubbles puts there.

## Features

- Conversation list with unread state, previews, and a phone connection indicator.
- Scrollable conversation view with sender labels in group chats.
- Quick replies, plus voice or keyboard reply input through Wear's remote input UI.
- Mark as read and open on phone, using OpenBubbles' own notification actions.
- Optimistic reply bubbles (`Sending…` / `Not sent`) that resolve when the phone updates.
- Small offline cache on the watch, so recent chats remain readable without the phone.
- Sync status, relay state, and cache information in the watch Settings screen.
- Photo messages are marked as `Photo`; media itself is not transferred.

## Requirements

- Android phone with API 32 (Android 12L) or newer.
- OpenBubbles 2.0.x installed and signed in on the phone.
- Wear OS 3.0 (API 30) or newer watch, paired to the phone through the Wear OS app.
- Google Play services available on both devices.
- Notification access granted to the phone bridge.

## Install

Release APKs are built and copied to `dist/`:

- `dist/OpenBubblesWear-Phone-1.0.0.apk`
- `dist/OpenBubblesWear-Watch-1.0.0.apk`
- `dist/SHA256SUMS.txt`

Install each APK on the matching device:

```bash
adb install -r dist/OpenBubblesWear-Phone-1.0.0.apk
adb -s <watch-serial> install -r dist/OpenBubblesWear-Watch-1.0.0.apk
```

Both APKs are signed with the same local release key. They use application IDs that are different
from the official app:

| App | Application ID |
| --- | --- |
| Phone bridge | `com.openbubbles.wear.bridge` |
| Watch app | `com.openbubbles.wear` |
| Official OpenBubbles | `com.openbubbles.messaging` |

### Phone setup

1. Open **OpenBubbles Wear** on the phone.
2. Tap **Notification settings** and allow notification access. You can also do this with:
   ```bash
   adb shell cmd notification allow_listener com.openbubbles.wear.bridge/.NotificationRelayService
   ```
3. Confirm the Setup card shows **OpenBubbles installed** and **Notification access**.
4. Leave **Relay to watch** enabled.
5. Keep the bridge app installed. Android may stop background work if the app is force-stopped.

### Watch setup

1. Open **OpenBubbles Wear** on the watch.
2. Wait for the status card to report the phone as connected.
3. If it still says **Phone offline**, tap **Sync now** and confirm the watch is paired in the
   Wear OS phone app. Opening the phone bridge app also helps re-establish the connection.
4. Conversations appear after OpenBubbles posts a notification for them.

### First conversation

Send a message to the phone from another device, or open an existing chat in OpenBubbles. As soon
as OpenBubbles posts or updates a chat notification, the conversation appears on the watch. Old
chats that have never produced a notification on this phone cannot be discovered.

## Using the watch app

- Tap a conversation to open it.
- **Reply** opens the composer with quick replies and **Speak or type a reply**.
- **Mark as read** invokes OpenBubbles' mark-read notification action.
- **Open on phone** launches the matching OpenBubbles conversation when the notification is live.
- **Refresh chat** requests the latest cached thread from the phone.
- Swipe right or use the back gesture to return.
- Settings shows phone/bridge status, notification-access state, relay state, last sync, and cache
  counts.

## Important limitations

- **Notifications only.** A chat is visible only after OpenBubbles posts a notification for it.
  Muted chats, suppressed notifications, and chats that never notify may never appear.
- **Notification-sized history.** The bridge sees only the messages OpenBubbles includes in the
  notification. Older messages are retained if the bridge observed them earlier, but full message
  history is not available from OpenBubbles.
- **Actions need a live notification.** If a notification is dismissed, cancelled by OpenBubbles,
  or lost after a reboot, the cached conversation is still readable but reply and mark-read become
  unavailable until the chat notifies again.
- **No media transfer.** Photos are shown as a placeholder. Attachments, reactions, stickers, and
  rich content are not copied to the watch.
- **No new conversations.** You can reply to conversations that notified, but not start a new chat
  or send an attachment from the watch.
- **Notification format dependency.** The parser relies on OpenBubbles' notification structure. A
  future OpenBubbles release may change it and require a bridge update.
- **Unofficial.** Use at your own risk, and do not install the debug test notifier on a daily-driver
  phone: it intentionally uses the real OpenBubbles package name.

Known emulator issues are documented in [docs/TESTING.md](docs/TESTING.md). In particular, the
Google APIs phone emulator image used during development reports `Wearable.API is not available on
this device`, so the complete Data Layer flow could not be validated there. A physical phone and
watch are recommended.

## Privacy and security

- The bridge requests notification-listener access, which is a sensitive capability. Its parser
  filters for `com.openbubbles.messaging` and `com.bluebubbles.messaging` only, but Android grants
  the listener access to all notifications.
- Messages stay on the phone and watch. Nothing is sent to a third-party server by this project.
- The Wear Data Layer is provided and encrypted in transit by Google Play services.
- Reply text is passed to OpenBubbles' own notification `PendingIntent`; the bridge does not handle
  account credentials or encryption keys.
- Release builds do not include the exported debug receiver. Debug builds do, so keep them off
  production devices.

## Build from source

### Toolchain

- JDK 17
- Android SDK with compile SDK 35 and build tools
- Gradle 8.13 (the checked-in wrapper works)
- Kotlin 2.1.20, AGP 8.9.2

### Debug builds

```bash
./gradlew :protocol:test
./gradlew :phone:assembleDebug :wear:assembleDebug :testnotifier:assembleDebug
```

Debug APKs:

- `phone/build/outputs/apk/debug/phone-debug.apk`
- `wear/build/outputs/apk/debug/wear-debug.apk`
- `testnotifier/build/outputs/apk/debug/testnotifier-debug.apk`

### Release builds

Release builds enable R8 and resource shrinking. The protocol models and their generated
serializers are kept by `phone/proguard-rules.pro` and `wear/proguard-rules.pro`.

Create a local signing key and `keystore.properties` in the repository root:

```bash
keytool -genkeypair -v -keystore release.keystore -alias openbubbles-wear \
  -keyalg RSA -keysize 4096 -validity 10000 -storetype PKCS12
```

```properties
storeFile=release.keystore
storePassword=your-store-password
keyAlias=openbubbles-wear
keyPassword=your-key-password
```

Then build:

```bash
./gradlew :phone:assembleRelease :wear:assembleRelease
```

Outputs:

- `phone/build/outputs/apk/release/phone-release.apk`
- `wear/build/outputs/apk/release/wear-release.apk`

If `keystore.properties` is absent, Gradle produces unsigned release APKs. `keystore.properties`,
`*.keystore`, and `*.jks` are ignored by git. Never use the development key from `dist/` for a
public release; generate your own and keep it backed up.

### Project layout

| Path | Purpose |
| --- | --- |
| `protocol/` | Shared JVM library: wire models, serialization, paths, IDs. |
| `phone/` | Phone bridge, notification listener, cache, Data Layer publisher, setup UI. |
| `wear/` | Wear OS Compose app, Data Layer listener, cache, chat UI. |
| `testnotifier/` | Debug-only fake OpenBubbles notification source. Never ship it. |
| `docs/` | Architecture and testing notes. |
| `work/` | Local reference/decompilation artifacts, not part of the app. |

## More documentation

- [Architecture](docs/ARCHITECTURE.md) — notification parsing, caching, protocol, and action flow.
- [Testing](docs/TESTING.md) — fake notifications, adb commands, debug builds, and known emulator
  behavior.

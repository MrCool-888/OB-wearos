# OpenBubbles Wear

An unofficial Wear OS companion for [OpenBubbles](https://openbubbles.app/). The watch shows
OpenBubbles conversations, replies to notifications, starts new chats, loads older messages, and
views photos and GIFs. All message sending still happens inside the official phone app; this project
never talks to Apple's servers directly and never handles your account or encryption keys.

> This project is not affiliated with OpenBubbles or Apple. It works with the official
> OpenBubbles 2.0.x APK without modifying it.

## How it works

OpenBubbles already builds a `MessagingStyle` notification for each active conversation. That
notification contains recent messages, sender names, attachment URIs, reply actions, and the
handle of the other person. The bridge reads those notifications through Android's notification
listener, copies attachments into app-private storage, caches a copy, and sends it over the Wear OS
Data Layer.

```
OpenBubbles (phone)
  ├─ MessagingStyle notification
  │    └─ Phone bridge ── Wear Data Layer ──> Watch app
  │           ^                                  │
  │           └──── reply / mark-read ───────────┘
  │                 (notification PendingIntent)
  │
  └─ Compose chat UI (optional automation)
       └─ Accessibility service ──> new chat, quiet-chat send, older history
```

The notification path is the default. An **optional accessibility service** drives OpenBubbles'
own screens for the cases a notification cannot cover: sending in a chat whose notification is
gone, starting a brand-new conversation, and scrolling back through older bubbles. The service only
operates OpenBubbles' UI when the watch asks it to; it never reads OpenBubbles' private database.

This gives a real companion experience without reimplementing OpenBubbles' encryption, account
setup, or message transport.

## Features

- Conversation list with unread state, previews, and a phone connection indicator.
- Scrollable conversation view with sender labels in group chats.
- Quick replies, plus voice or keyboard input through Wear's remote input UI.
- **New message**: start a chat from a phone number or email address, or pick a recent contact.
- **Send in quiet chats**: send even after the notification has been dismissed, through
  OpenBubbles' composer.
- **Load older messages**: scroll OpenBubbles' chat screen and prepend older history to the watch.
- **Reply when live**: replies to active notifications use OpenBubbles' own reply action, so they
  work even when automation is off.
- Mark as read and open on phone, using OpenBubbles' notification actions and saved content
  intents.
- Optimistic send bubbles (`Sending…` / `Not sent`) that resolve when the phone confirms.
- Small offline cache on the watch, so recent chats remain readable without the phone.
- Sync status, relay state, automation state, and cache information in the watch Settings screen.
- **Photos and GIFs**: tap an attachment on the watch to fetch its cached copy from the phone, then
  tap again for a full-screen view. Animated GIFs play automatically; pinch to zoom or double-tap.

## Requirements

- Android phone with API 32 (Android 12L) or newer.
- OpenBubbles 2.0.x installed and signed in on the phone.
- Wear OS 3.0 (API 30) or newer watch, paired to the phone through the Wear OS app.
- Google Play services available on both devices.
- Notification access granted to the phone bridge.
- Optional: accessibility access granted to the bridge's automation service. This is only needed
  for new chats, sending without a live notification, and loading older messages.

No root access, Shizuku, custom OpenBubbles build, or OpenBubbles-side changes are required.

## Install

Release APKs are built and copied to `dist/`:

- `dist/OpenBubblesWear-Phone-1.2.0.apk`
- `dist/OpenBubblesWear-Watch-1.2.0.apk`
- `dist/SHA256SUMS.txt`

Install each APK on the matching device:

```bash
adb install -r dist/OpenBubblesWear-Phone-1.2.0.apk
adb -s <watch-serial> install -r dist/OpenBubblesWear-Watch-1.2.0.apk
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
2. Tap **Notification access** and allow access. You can also do this with:
   ```bash
   adb shell cmd notification allow_listener com.openbubbles.wear.bridge/.NotificationRelayService
   ```
3. Confirm the Setup card shows **OpenBubbles installed** and **Notification access**.
4. Leave **Relay to watch** enabled.
5. Optional: tap **Enable automation** and turn on **OpenBubbles Wear automation** in Android's
   accessibility settings. From adb:
   ```bash
   adb shell settings put secure enabled_accessibility_services \
     com.openbubbles.wear.bridge/com.openbubbles.wear.bridge.OpenBubblesAutomationService
   adb shell settings put secure accessibility_enabled 1
   ```
   On Android 13 and newer, a sideloaded app may first need **Allow restricted settings** from
   Android's App info screen. The phone must be unlocked with OpenBubbles able to open when
   automation runs. New chats, quiet-chat sends, and history loading are unavailable while
   automation is off.
6. Keep the bridge app installed. Android may stop background work if the app is force-stopped.

### Watch setup

1. Open **OpenBubbles Wear** on the watch.
2. Wait for the status card to report the phone as connected.
3. If it still says **Phone offline**, tap **Sync now** and confirm the watch is paired in the
   Wear OS phone app. Opening the phone bridge app also helps re-establish the connection.
4. Conversations appear after OpenBubbles posts a notification for them. Use **New message** to
   start a chat that has never notified.

### First conversation

Send a message to the phone from another device, or open an existing chat in OpenBubbles. As soon
as OpenBubbles posts or updates a chat notification, the conversation appears on the watch. Old
chats that have never produced a notification on this phone cannot be discovered by the
notification path, but with automation on you can start a new chat by address or search for an
existing one by title when loading history.

## Using the watch app

- Tap a conversation to open it.
- **New message** opens a recipient screen. Enter a phone number or email address, or choose a
  recent one-to-one contact. The first send needs automation; afterwards OpenBubbles' notification
  and normal shortcut take over.
- **Reply** opens the composer with quick replies and **Speak or type a reply**. For a quiet chat
  the button reads **Send message** and the phone performs the send through OpenBubbles' composer.
- **Load older messages** (with automation on) asks the phone to scroll OpenBubbles' chat screen
  and prepend the older bubbles it can read.
- Tap a photo or GIF bubble to copy its cached copy from the phone, then tap the thumbnail to open
  the full-screen viewer. GIFs animate in both the thumbnail and the viewer.
- **Mark as read** invokes OpenBubbles' mark-read notification action when available.
- **Open on phone** reopens the matching OpenBubbles conversation, using a saved content intent
  when the notification is gone.
- **Refresh chat** requests the latest cached thread from the phone.
- Swipe right or use the back gesture to return.
- Settings shows phone/bridge status, notification-access state, relay state, automation state,
  last sync, and cache counts.

## Important limitations

- **Notifications are the main source.** A chat is visible after OpenBubbles posts a notification
  for it. Muted chats, suppressed notifications, and chats that never notify may be missing until
  you send in them or load history with automation.
- **History is best effort.** The bridge sees only the messages OpenBubbles includes in the
  notification, plus the bubbles automation can scroll into view. Full message history is not
  available, and a chat with no cached messages cannot load older history because there is no
  anchor to scroll from.
- **History timestamps are approximate.** Accessibility exposes bubble text and position, not
  message GUIDs or exact timestamps. Loaded history is ordered correctly but timestamped in
  one-minute steps before the oldest cached message. Very long conversations and messages with
  identical text can be ambiguous.
- **Automation is optional and selector-dependent.** New chats, quiet-chat sends, and history
  loading rely on OpenBubbles' Compose accessibility tree. The current selectors were written
  against OpenBubbles 2.0 and still need validation against a real device. A future OpenBubbles
  release can rename or restructure controls and require a bridge update.
- **Automation is not instant.** The phone may need to open OpenBubbles, navigate, type, and
  verify the send. A send can take up to about 90 seconds and history loading up to about two
  minutes. The phone should be unlocked and able to launch OpenBubbles.
- **Media comes from notifications.** Image bytes are copied only while OpenBubbles' notification
  still exposes its attachment URI. Photos are downscaled for the watch; GIFs and animated WebPs
  keep their animation when they fit the transfer budget, otherwise only the first frame is sent.
  OpenBubbles' **Hide text previews** setting also suppresses image data, so media will not appear
  while it is enabled. History loaded through accessibility contains text only.
- **Media caches are bounded.** The phone keeps roughly 80 attachments (80 MB), and the watch keeps
  the most recent 60 (40 MB). Older images may need to be re-sent by OpenBubbles before they can be
  viewed again.
- **Actions need a live notification, unless automation is on.** If a notification is dismissed,
  cancelled by OpenBubbles, or lost after a reboot, mark-read is unavailable and reply falls back
  to automation if it is enabled.
- **Long messages are capped.** Notification and automation text is capped at 800 characters.
- **Notification format dependency.** The parser relies on OpenBubbles' notification structure. A
  future OpenBubbles release may change it and require a bridge update.
- **Unofficial.** Use at your own risk, and do not install the debug test notifier on a daily-driver
  phone: it intentionally uses the real OpenBubbles package name.

Known emulator issues are documented in [docs/TESTING.md](docs/TESTING.md). In particular, the
Google APIs phone emulator image used during development reports `Wearable.API is not available on
this device`, so the complete Data Layer flow could not be validated there. A physical phone and
watch are recommended. Accessibility automation cannot be validated on an emulator without a real
OpenBubbles account and chat UI.

## Privacy and security

- The bridge requests notification-listener access, which is a sensitive capability. Its parser
  filters for `com.openbubbles.messaging` and `com.bluebubbles.messaging` only, but Android grants
  the listener access to all notifications.
- The optional accessibility service is scoped to OpenBubbles packages. It reads the OpenBubbles
  screen only while a watch-requested send or history job is running, and it types the message you
  asked to send. It does not read OpenBubbles' database, credentials, or encryption keys.
- Messages stay on the phone and watch. Nothing is sent to a third-party server by this project.
- The Wear Data Layer is provided and encrypted in transit by Google Play services.
- Reply text is passed to OpenBubbles' own notification `PendingIntent`; new-chat and quiet-chat
  sends are typed into OpenBubbles' own composer. The bridge does not handle account credentials
  or encryption keys.
- Photo and GIF bytes are copied from OpenBubbles' notification attachment URIs into the bridge's
  app-private cache, then transferred to the watch over the Wear Data Layer. They are not uploaded
  anywhere else. Clearing the bridge cache or uninstalling the app removes the phone copy.
- Release builds do not include the exported debug receivers. Debug builds do, so keep them off
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

The current release is phone bridge 1.2.0 and watch app 1.2.0, using protocol version 3. Update
both APKs together for the media, send, and history features. A 1.2.0 watch paired with a 1.1.0
phone falls back to text-only chats (media requests fail with a clear message), but a 1.0.0 watch
cannot use the newer actions.

### Project layout

| Path | Purpose |
| --- | --- |
| `protocol/` | Shared JVM library: wire models, serialization, paths, IDs. |
| `phone/` | Phone bridge, notification listener, accessibility automation, cache, Data Layer publisher, setup UI. |
| `wear/` | Wear OS Compose app, Data Layer listener, cache, chat/new-message UI. |
| `testnotifier/` | Debug-only fake OpenBubbles notification source. Never ship it. |
| `docs/` | Architecture and testing notes. |
| `work/` | Local reference/decompilation artifacts, not part of the app. |

## More documentation

- [Architecture](docs/ARCHITECTURE.md) — notification parsing, caching, automation, protocol, and
  action flow.
- [Testing](docs/TESTING.md) — fake notifications, adb commands, automation checks, debug builds,
  and known emulator behavior.

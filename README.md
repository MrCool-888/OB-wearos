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
- **Contact picker**: browse/search the phone address book used by OpenBubbles on Android; choose
  a saved number or email instead of typing it. Contacts permission is optional and granted on the phone.
- **Group chats**: an explicit Groups view plus a group section in New message. Existing group
  identities and notification sender labels are preserved. Optional Shizuku discovery imports chats
  from OpenBubbles' published Android shortcuts **and notification channels** without needing a
  notification (partial list only).
- **Send in quiet chats**: after the notification is dismissed, reuse OpenBubbles' saved reply
  action. If that token is no longer valid, fall back to OpenBubbles' composer.
- **History on open**: with automation ready and the phone online/unlocked, opening a contact or
  group automatically requests readable older text, including when the watch cache is empty.
  Load more with **Load older messages**; use **Latest messages** to leave the older viewing window.
- **Locked-phone behavior**: cached chats, notification replies (live or saved), incoming syncs,
  and Android-published chat discovery keep working while the phone is securely locked. Older
  history still waits for unlock. A chat that has posted a notification can send while locked
  through its saved reply action; if no action is available, the message is queued and retried.
- **Reply when live**: replies to active notifications use OpenBubbles' own reply action, so they
  work even when automation is off or the phone is locked. The bridge also keeps the most recent
  reply action per chat so sending still works after the notification is dismissed.
- Mark as read and open on phone, using OpenBubbles' notification actions and saved content
  intents.
- Optimistic send bubbles (`Sending…` / `Not sent`) that resolve when the phone confirms.
- Small offline cache on the watch, so recent chats remain readable without the phone.
- Sync status, relay state, automation state, and cache information in the watch Settings screen.
- **Photos and GIFs**: tap an attachment on the watch to fetch its cached copy from the phone, then
  tap again for a full-screen view. Animated GIFs play automatically; pinch to zoom or double-tap.
- **Watch alerts** with inline text/voice reply, mark-read, and a tap straight into the chat.
  Extra alerts can be disabled if you prefer Android's mirrored phone notifications.
- **Offline outbox**: messages are saved on the watch and retried when the phone reconnects.
- **Messages tile and unread complication** with shortcuts into cached conversations.
- **Optional Shizuku setup** for bridge permissions and battery exemption, plus phone diagnostics.

## Requirements

- Android phone with API 32 (Android 12L) or newer.
- OpenBubbles 2.0.x installed and signed in on the phone.
- Wear OS 3.0 (API 30) or newer watch, paired to the phone through the Wear OS app.
- Google Play services available on both devices.
- Notification access granted to the phone bridge.
- Optional: accessibility access granted to the bridge's automation service. This is only needed
  for new chats, sending without a live notification, and loading older messages.

No root access, custom OpenBubbles build, or OpenBubbles-side changes are required. Shizuku is
optional for setup and required only for discovering chats through OpenBubbles' published Android
shortcuts and notification channels.

## Install

Download the APKs from the [1.7.0 device-testing release](https://github.com/MrCool-888/OB-wearos/releases/tag/1.7.0).
Real phone/watch validation is still required; see [the testing checklist](docs/TESTING.md).

Release APKs are built and copied to `dist/`:

- `dist/OpenBubblesWear-Phone-1.7.0.apk`
- `dist/OpenBubblesWear-Watch-1.7.0.apk`
- `dist/SHA256SUMS.txt`

Install each APK on the matching device:

```bash
adb -s <phone-serial> install -r dist/OpenBubblesWear-Phone-1.7.0.apk
adb -s <watch-serial> install -r dist/OpenBubblesWear-Watch-1.7.0.apk
```

Both APKs are signed with the same local release key. They use application IDs that are different
from the official app:

| App | Application ID |
| --- | --- |
| Phone bridge | `com.openbubbles.wear` |
| Watch app | `com.openbubbles.wear` |
| Official OpenBubbles | `com.openbubbles.messaging` |

Phone and watch intentionally share an application ID and signing certificate, as required by
Google's Wear Data Layer. Their source-code namespaces remain different.

**Upgrading the phone from 1.2.0 or earlier:** the old bridge used
`com.openbubbles.wear.bridge`, which prevented Data Layer communication with the watch. Uninstall
that old bridge (not the official OpenBubbles app), install the new phone APK, and grant its
notification/accessibility permissions again. The bridge's old local cache is not migrated;
OpenBubbles' messages and account are untouched. The watch APK updates normally.

### Phone setup

1. Open **OpenBubbles Wear** on the phone.
2. Tap **Notification access** and allow access. You can also do this with:
   ```bash
    adb shell cmd notification allow_listener com.openbubbles.wear/com.openbubbles.wear.bridge.NotificationRelayService
   ```
3. Confirm the Setup card shows **OpenBubbles installed** and **Notification access**.
4. Leave **Relay to watch** enabled.
5. Optional: tap **Enable automation** and turn on **OpenBubbles Wear automation** in Android's
   accessibility settings. From adb:
   ```bash
   adb shell settings put secure enabled_accessibility_services \
      com.openbubbles.wear/com.openbubbles.wear.bridge.OpenBubblesAutomationService
   adb shell settings put secure accessibility_enabled 1
   ```
   On Android 13 and newer, a sideloaded app may first need **Allow restricted settings** from
   Android's App info screen. The phone must be unlocked with OpenBubbles able to open when
   automation runs. New chats, quiet-chat sends, and history loading are unavailable while
   automation is off.
6. Keep the bridge app installed. Android may stop background work if the app is force-stopped.
7. Optional: under **Watch contact picker**, tap **Allow contact picker** and approve Contacts.
   This lets the watch browse names and numbers/emails from the same Android address book used by
   OpenBubbles. It does not grant the bridge access to OpenBubbles' private database.

**Optional assisted setup:** install and start [Shizuku](https://shizuku.rikka.app/) using wireless
debugging (shell mode, not root). In the phone bridge, tap **Grant Shizuku access**, approve the
prompt, then **Finish setup**. Existing accessibility services are preserved. Check the status
cards afterward: some Android builds may still require manual permission approval. The bridge
works without Shizuku, and stopping Shizuku does not revoke grants that Android already accepted.

**Status and diagnostics** shows listener connection, last OpenBubbles alert, installed app
version, battery optimization, and watch connection. **Reconnect listener** asks Android to bind
the notification listener again. Battery exemption is optional and does not override every OEM's
background restrictions.

### Notification access says "Blocked" or "Restricted setting"

**First check which screen says Blocked.** Samsung's **App info → Notifications** controls alerts
posted by the phone bridge. The bridge does not post phone alerts, so that screen may say **Blocked**
with a grey switch. This does **not** mean access to OpenBubbles messages is blocked. Do not change
device security settings just to enable that unused switch. Use the bridge's **Notification access**
button, or Android Settings → Apps → Special access → Notification access (menu names vary).
Watch alerts are configured separately on the watch.

If the actual **Notification access** screen refuses to enable the listener, Android may be
restricting a sideloaded APK. This is separate from ordinary **Allow notifications** permission.
In that case, tap **Notification access blocked?** for guided recovery:

1. Open **Notification access** and try the bridge's switch once. Dismiss the restricted-settings
   warning if it appears.
2. Open **App info** for **OpenBubbles Wear**. Tap **⋮ → Allow restricted settings** and confirm.
   Some phones only show this menu after step 1.
3. Return to **Notification access** and enable the bridge. App info → Notifications is a different
   permission and does not let the bridge read messages.

Only approve access if you trust the bridge: Android grants notification listeners access to all
notifications, including sensitive content, even though this bridge only relays OpenBubbles.

If the menu is missing, optionally grant Shizuku access, return to **Notification access blocked?**,
and explicitly choose **Unblock with Shizuku**. This approves restricted settings only for the bridge
and grants its listener access; it does not enable accessibility automation or alter OpenBubbles.
The app verifies the actual listener grant before reporting success. Newer Android/OEM restrictions
may still require manual approval. Work-profile/administrator/parental-control restrictions cannot
be overridden by this app. Do not disable Play Protect or device-wide security settings.

ADB alternative for the phone's primary Android user (replace `0` if using another user):

```bash
adb shell cmd appops set --user 0 com.openbubbles.wear ACCESS_RESTRICTED_SETTINGS allow
adb shell cmd notification allow_listener com.openbubbles.wear/com.openbubbles.wear.bridge.NotificationRelayService 0
```

These steps follow [Android's restricted-settings guidance](https://support.google.com/android/answer/12623953).

### Accessibility is On in Samsung Settings but the bridge says Off

1.3.1 fixes detection of Android's shortened accessibility component names. The phone UI now
distinguishes **On in Android settings** from **Service connected**; a service that is enabled but
has not connected is not falsely advertised as ready to the watch. If it stays enabled but
disconnected, switch it off/on once under Accessibility → Installed apps, then reopen the bridge.
Shizuku is not required for this detection or for manually enabled accessibility.

If settings still disagree, tap **Copy permission diagnostics** in the phone bridge's Status and
diagnostics card and share the result. It includes Android release/API level, exact app components,
grant/connection state, and whether the old bridge is also installed, but no messages, contacts,
notification text, or other apps' enabled-service names. Real Samsung/One UI builds must still be
validated on hardware; the project does not claim Android 17 / One UI 9 device verification.

### Watch setup

1. Open **OpenBubbles Wear** on the watch.
2. Wait for the status card to report the phone as connected.
3. If it still says **Phone offline**, tap **Sync now** and confirm the watch is paired in the
   Wear OS phone app. Opening the phone bridge app also helps re-establish the connection.
4. Conversations appear after OpenBubbles posts a notification for them. Use **New message** to
   choose a contact/address, or **Find chats (Shizuku)** to import chats from OpenBubbles'
   published Android shortcuts and notification channels without waiting for a notification.
5. In watch Settings, allow notifications for the companion's extra alerts. If these duplicate
   mirrored OpenBubbles alerts, turn **Extra alerts** off.
6. Add **OpenBubbles Wear** from the watch's tile picker or as an unread complication in a compatible
   watch face. Supported complication types are short text, long text, and ranged value.

### First conversation

Send a message to the phone from another device, or open an existing chat in OpenBubbles. As soon
as OpenBubbles posts or updates a chat notification, the conversation appears on the watch. Old
chats that have never produced a notification or Android shortcut/channel on this phone cannot be
discovered by the bridge, but with automation on you can start a new chat by address or search for
an existing one by title when loading history.

### Pick a contact or group on the watch

1. Install both 1.7.0 APKs and optionally allow Contacts in the phone bridge.
2. On the watch, open **New message → Choose a contact**. Browse or **Search contacts** by name,
   phone number, or email; **Load more contacts** fetches the next page. If someone has multiple
   numbers/emails, choose one explicitly. Selecting a contact opens its chat/history screen—it
   does not send. Tap **Send message** or **Reply** when you want to compose.
3. Starting a brand-new chat still requires connected accessibility automation and an unlocked
   phone. Existing chats that have posted a notification keep sending while the phone is locked
   through OpenBubbles' saved reply action. No root or OpenBubbles changes.
4. Use **Groups** on the Messages screen or **Published chats** in New message to open an existing
   group. For chats without notifications: open OpenBubbles on the phone first, start Shizuku via
   wireless debugging, grant the bridge Shizuku access, then tap **Find chats (Shizuku)** on the
   watch or **Find chats with Shizuku** in the phone bridge. Sync after importing on the phone.
   Selecting a group reopens its stable chat ID, not a new recipient list.
5. With accessibility connected and the phone online/unlocked, opening a chat automatically loads
   readable history. **Load older messages** requests the next older page. **Latest messages**
   returns to the latest cache; syncs do not evict the older window while you are reading it.

The address book is not a directory of registered Apple/iMessage accounts, and OpenBubbles-only
private/imported contacts may be absent. The last browsed contacts page/search is cached for offline
use; it is not a complete offline phone-book mirror. Search/paging needs the phone. Shortcut/channel
discovery is a partial chat list: the inspected OpenBubbles source publishes up to four recent named
chats as shortcuts at startup and creates one Android notification channel per chat. Unpublished
groups still need a notification or a future supported integration; the bridge does not enumerate
OpenBubbles' private storage.

## Using the watch app

- Tap a conversation to open it.
- **New message** opens a recipient screen. Enter a phone number or email address, or choose a
  phone contact, recent chat, or existing group. The first send to a new recipient needs automation; afterwards OpenBubbles' notification
  and normal shortcut take over.
- **Reply** opens the composer with quick replies and **Speak or type a reply**. For a quiet chat
  the button reads **Send message**; the phone reuses OpenBubbles' saved reply action, then falls
  back to its composer.
- Opening a chat automatically requests history when automation and the phone are available.
  This opens/scrolls OpenBubbles on the phone. **Load older messages** reads another text page;
  **Latest messages** leaves the older viewing window. Empty-cache chats can load history too.
- Tap a photo or GIF bubble to copy its cached copy from the phone, then tap the thumbnail to open
  the full-screen viewer. GIFs animate in both the thumbnail and the viewer.
- **Mark as read** invokes OpenBubbles' mark-read notification action when available.
- **Open on phone** reopens the matching OpenBubbles conversation, using a saved content intent
  when the notification is gone.
- **Refresh chat** requests the latest cached thread from the phone.
- Swipe right or use the back gesture to return.
- Settings shows phone/bridge status, notification-access state, relay state, automation state,
  last sync, and cache counts.
- Write while offline to save a message to the **Outbox**. It is sent automatically on reconnect;
  Settings also has **Send queued now**. The phone still needs its normal permissions. A chat with
  no saved notification reply action and no automation needs an unlocked phone to send.
- Tap a tile chat row, complication, or watch alert to open the matching saved conversation.

## Important limitations

- **Notifications are the main source.** A chat is visible after OpenBubbles posts a notification
  for it. Muted chats and chats that never notified may be missing until you choose a
  contact/address or run Shizuku discovery, which also reads Android notification channels.
- **Chat discovery is partial.** Shizuku reads OpenBubbles' published Android shortcuts and
  notification channels for the current Android user, not its private database. It may expose no
  chats or only chats that have appeared to Android before. Android/OEM restrictions can prevent the
  query. Notification discovery still works, and Shizuku is optional.
- **Secure lock limits UI automation.** A locked phone cannot be unlocked by the bridge. Cached
  chats, live and saved notification replies, incoming syncs, and published chat discovery keep
  working. Chats that have posted a notification send through the saved reply action while locked.
  Older history, brand-new chats, and chats with no saved reply action need unlock or automation;
  those messages stay in the watch outbox and retry automatically. Reply tokens belong to
  OpenBubbles and may be cancelled when it updates or dismisses a notification, so a saved-action
  send is best effort rather than guaranteed.
- **History is best effort.** The bridge sees only the messages OpenBubbles includes in the
  notification, plus the bubbles automation can scroll into view. Full message history is not
  guaranteed. Empty-cache chats can load readable text, but inaccessible UI text produces an error,
  not a false claim that history is complete. Each job is bounded to 30 screen pages / two minutes.
- **History timestamps are approximate.** Accessibility exposes bubble text and position, not
  message GUIDs or exact timestamps. Loaded history uses visual bubble order and is timestamped in
  one-minute steps before the oldest cached message. Very long conversations and messages with
  identical text can be ambiguous. Missing or ambiguous oldest-text anchors fail rather than
  relabel newer messages as older. Accessibility history has no reliable group sender labels.
- **Automation is optional and selector-dependent.** New chats, quiet-chat sends, and history
  loading rely on OpenBubbles' Flutter accessibility tree. The current selectors were written
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
- **Queued does not mean delivered.** The outbox holds up to 100 unsent messages. Once the Data
  Layer accepts a request, the watch waits up to 100 seconds for confirmation. An uncertain send
  is marked failed, not automatically sent again: check OpenBubbles before manually retrying.
  The phone journals request IDs to suppress duplicate replays, retaining the latest 200 results.
  Force-stopping either app can delay reconnect processing until it is opened again.
- **Unread counts are conversations**, not individual messages. Tile/complication data comes from
  the local cache and can be stale while offline; watch-face support varies.
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
- Shizuku setup changes only this bridge's notification/accessibility grants and battery exemption.
  User-requested chat discovery additionally reads OpenBubbles' Android-published shortcut IDs and
  titles plus its notification channels for the current user. It does not access private files,
  notification-history databases, or root, and rejects root-mode Shizuku.
- Messages stay on the phone and watch. Nothing is sent to a third-party server by this project.
- Optional Contacts permission reads only names and phone/email addresses for watch-requested
  browsing/search. No contact photos, notes, account credentials, or third-party upload. Cached
  results are stored separately in the watch's private preferences. Revoking Contacts clears the
  cached list at the next phone metadata sync/contact response; offline copies cannot be remotely
  erased until the watch reconnects. Clearing watch app data/uninstalling removes its cache.
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
./gradlew :protocol:test :phone:testDebugUnitTest :wear:testDebugUnitTest
./gradlew :phone:lintDebug :wear:lintDebug
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

The current release is phone bridge 1.7.0 and watch app 1.7.0, using protocol version 3. Update
both APKs together for request deduplication and the latest features. A 1.2.0 watch paired with a 1.1.0
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
- [1.7.0 release notes](docs/RELEASE-1.7.0.md) — sending through OpenBubbles while the phone is
  locked.

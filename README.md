# OpenBubbles Wear

An unofficial Wear OS companion for [OpenBubbles](https://openbubbles.app/). The watch shows
OpenBubbles conversations, replies through the phone's notification actions, and views photos and
GIFs. All message sending still happens inside the official phone app; this project never talks to
Apple's servers directly and never handles your account or encryption keys.

> This project is not affiliated with OpenBubbles or Apple. It works with the official
> OpenBubbles 2.0.x APK without modifying it.

### Experimental Morphe version

The **2.1.6 release** fixes contact names on the watch and keeps Morphe chat discovery and history for native
OpenBubbles 2.0.0 (20002289), including UUID and exact 72-character hexadecimal conversation IDs with explicit
group metadata. It requires the patched OpenBubbles APK update, which contains Morphe patch 1.2.3, and both
companion apps 2.1.6. The patch adds paginated chat discovery/history, contact names for chats and group
senders, sending without a notification, and new one-to-one chats from the watch. OB Settings → Morphe patches
controls the runtime bridge. See [Install 2.1.6](docs/INSTALL-2.1.6.md), the
[2.1.6 release notes](docs/RELEASE-2.1.6.md), and the [Morphe build notes](docs/MORPHE.md).

## How it works

OpenBubbles builds a `MessagingStyle` notification for each active conversation. That notification
contains recent messages, sender names, attachment URIs, reply actions, and the handle of the other
person. The bridge reads those notifications through Android's notification listener, copies
attachments into app-private storage, caches a copy, and sends it over the Wear OS Data Layer.

```
OpenBubbles (phone)
  ├─ MessagingStyle notification
  │    └─ Phone bridge ── Wear Data Layer ──> Watch app
  │           ^                                  │
  │           └──── reply / mark-read ───────────┘
  │                 (notification PendingIntent)
  │
  └─ OpenBubbles receiver ──> real send / read
```

This gives a real companion experience without reimplementing OpenBubbles' encryption, account
setup, or message transport.

## Features

- Conversation list with unread state, previews, and a phone connection indicator.
- Scrollable conversation view with sender labels in group chats.
- Quick replies, plus voice or keyboard input through Wear's remote input UI.
- **Contact picker**: browse/search the phone address book used by OpenBubbles and open an
  existing saved chat. Contacts permission is optional and granted on the phone.
- **Group chats**: an explicit Groups view plus a group section in Find a chat. Existing group
  identities and notification sender labels are preserved. Optional Shizuku discovery imports chats
  from OpenBubbles' published Android shortcuts **and notification channels** without needing a
  notification (partial list only).
- **Send while locked**: replies use the live notification reply action, then the reply action saved
  from an earlier notification. The phone can stay securely locked. No accessibility service is
  involved and OpenBubbles does not need to open.
- Mark as read and open on phone, using OpenBubbles' notification actions and saved content
  intents.
- Optimistic send bubbles (`Sending…` / `Not sent`) that resolve when the phone confirms.
- Small offline cache on the watch, so recent chats remain readable without the phone.
- Sync status, relay state, and cache information in the watch Settings screen.
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

No root access, accessibility service, custom OpenBubbles build, or OpenBubbles-side changes are
required. Shizuku is optional for setup and required only for discovering chats through
OpenBubbles' published Android shortcuts and notification channels.

## Install

The notification-only testing build is **2.0.2**, with the locked-send fixes described in
[its build notes](docs/RELEASE-2.0.2.md). The previously published
[2.0.1 release](https://github.com/MrCool-888/OB-wearos/releases/tag/2.0.1) does not contain these fixes.
Real phone/watch validation is still required; see [the testing checklist](docs/TESTING.md).

Release APKs are built and copied to `dist/`:

- `dist/OpenBubblesWear-Phone-2.0.2.apk`
- `dist/OpenBubblesWear-Watch-2.0.2.apk`
- `dist/SHA256SUMS.txt`

Install each APK on the matching device:

```bash
adb -s <phone-serial> install -r dist/OpenBubblesWear-Phone-2.0.2.apk
adb -s <watch-serial> install -r dist/OpenBubblesWear-Watch-2.0.2.apk
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

**Upgrading:** install both 2.0.2 APKs over 2.0.1, 2.0.0, or 1.7.0. The phone bridge no longer uses an
accessibility service; if you enabled it for an older version, you can turn it off in Android's
accessibility settings. The bridge's cached conversations are kept. Application IDs, signing
certificate, and protocol v3 are unchanged, so matching 2.0.2 phone/watch apps are recommended.
Keep an OpenBubbles chat notification active during installation so the restarted bridge can
capture its reply action again. Tokens are kept in memory, not on disk.

### Phone setup

1. Open **OpenBubbles Wear** on the phone.
2. Tap **Notification access** and allow access. You can also do this with:
   ```bash
    adb shell cmd notification allow_listener com.openbubbles.wear/com.openbubbles.wear.bridge.NotificationRelayService
   ```
3. Confirm the Setup card shows **OpenBubbles installed** and **Notification access**.
4. Leave **Relay to watch** enabled.
5. Keep the bridge app installed. Android may stop background work if the app is force-stopped.
6. Optional: under **Watch contact picker**, tap **Allow contact picker** and approve Contacts.
   This lets the watch browse names and numbers/emails from the same Android address book used by
   OpenBubbles. It does not grant the bridge access to OpenBubbles' private database.

**Optional assisted setup:** install and start [Shizuku](https://shizuku.rikka.app/) using wireless
debugging (shell mode, not root). In the phone bridge, tap **Grant Shizuku access**, approve the
prompt, then **Finish setup**. Check the status cards afterward: some Android builds may still
require manual permission approval. The bridge works without Shizuku, and stopping Shizuku does not
revoke grants that Android already accepted.

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
and grants its listener access; it does not alter OpenBubbles. The app verifies the actual listener
grant before reporting success. Newer Android/OEM restrictions may still require manual approval.
Work-profile/administrator/parental-control restrictions cannot be overridden by this app. Do not
disable Play Protect or device-wide security settings.

ADB alternative for the phone's primary Android user (replace `0` if using another user):

```bash
adb shell cmd appops set --user 0 com.openbubbles.wear ACCESS_RESTRICTED_SETTINGS allow
adb shell cmd notification allow_listener com.openbubbles.wear/com.openbubbles.wear.bridge.NotificationRelayService 0
```

These steps follow [Android's restricted-settings guidance](https://support.google.com/android/answer/12623953).

### Watch setup

1. Open **OpenBubbles Wear** on the watch.
2. Wait for the status card to report the phone as connected.
3. If it still says **Phone offline**, tap **Sync now** and confirm the watch is paired in the
   Wear OS phone app. Opening the phone bridge app also helps re-establish the connection.
4. Conversations appear after OpenBubbles posts a notification for them. Use **Find a chat** to
   choose an existing contact/chat, or **Find chats (Shizuku)** to import chats from OpenBubbles'
   published Android shortcuts and notification channels without waiting for a notification.
5. In watch Settings, allow notifications for the companion's extra alerts. If these duplicate
   mirrored OpenBubbles alerts, turn **Extra alerts** off.
6. Add **OpenBubbles Wear** from the watch's tile picker or as an unread complication in a compatible
   watch face. Supported complication types are short text, long text, and ranged value.

### First conversation

Send a message to the phone from another device, or open an existing chat in OpenBubbles. As soon
as OpenBubbles posts or updates a chat notification, the conversation appears on the watch. Old
chats that have never produced a notification or Android shortcut/channel on this phone cannot be
discovered by the bridge and cannot be started from the watch.

### Pick a contact or group on the watch

1. Install both 2.0.2 APKs and optionally allow Contacts in the phone bridge.
2. On the watch, open **Find a chat → Choose a contact**. Browse or **Search contacts** by name,
   phone number, or email; **Load more contacts** fetches the next page. If someone has multiple
   numbers/emails, choose one explicitly. Selecting a contact opens its existing chat. It does not
   send or create a chat.
3. Only chats OpenBubbles has already published or notified can be opened. An unknown contact shows
   an explanation instead of creating a new conversation.
4. Use **Groups** on the Messages screen or **Published chats** in Find a chat to open an existing
   group. For chats without notifications: open OpenBubbles on the phone first, start Shizuku via
   wireless debugging, grant the bridge Shizuku access, then tap **Find chats (Shizuku)** on the
   watch or **Find chats with Shizuku** in the phone bridge. Sync after importing on the phone.
   Selecting a group reopens its stable chat ID, not a new recipient list.

The address book is not a directory of registered Apple/iMessage accounts, and OpenBubbles-only
private/imported contacts may be absent. The last browsed contacts page/search is cached for offline
use; it is not a complete offline phone-book mirror. Search/paging needs the phone. Shortcut/channel
discovery is a partial chat list: OpenBubbles publishes Android shortcuts for chats it has named
and creates one Android notification channel per chat. Chats published only under an Android
contact ID have no address, so the contact picker can match them after OpenBubbles posts a
notification for them. Unpublished groups still need a notification or a future supported
integration; the bridge does not enumerate OpenBubbles' private storage.

## Using the watch app

- Tap a conversation to open it.
- **Find a chat** opens a screen for existing contacts and published chats. It does not create a
  brand-new conversation; those must be started in OpenBubbles on the phone.
- **Reply** opens the composer with quick replies and **Speak or type a reply**. Sending uses the
  live notification action first, then the saved action from an earlier notification.
- A chat must have posted at least one notification for its reply action to exist. A chat that has
  never notified is read-only until OpenBubbles posts one.
- Tap a photo or GIF bubble to copy its cached copy from the phone, then tap the thumbnail to open
  the full-screen viewer. GIFs animate in both the thumbnail and the viewer.
- **Mark as read** invokes OpenBubbles' mark-read notification action when available.
- **Open on phone** reopens the matching OpenBubbles conversation, using a saved content intent
  when the notification is gone.
- **Refresh chat** requests the latest cached thread from the phone.
- Swipe right or use the back gesture to return.
- Settings shows phone/bridge status, notification-access state, relay state, last sync, and cache
  counts.
- Write while offline to save a message to the **Outbox**. It is sent automatically on reconnect;
  Settings also has **Send queued now**. The phone still needs notification access.
- Tap a tile chat row, complication, or watch alert to open the matching saved conversation.

## Important limitations

- **Notifications are the only message source.** A chat is visible after OpenBubbles posts a
  notification for it. Muted chats and chats that never notified may be missing until you open them
  in OpenBubbles or run Shizuku discovery, which also reads Android notification channels. The bridge
  cannot read OpenBubbles' private message database and does not request the notification-history
  database.
- **Chat discovery is partial.** Shizuku reads OpenBubbles' published Android shortcuts and
  notification channels for the current Android user, not its private database. It may expose no
  chats or only chats that have appeared to Android before. Android/OEM restrictions can prevent the
  query. Notification discovery still works, and Shizuku is optional.
- **No new chats from the watch.** Starting a conversation requires OpenBubbles on the phone. The
  watch can find and open only chats that already exist in the notification/shortcut cache.
- **Secure lock:** replies send through OpenBubbles' notification reply action, so the phone can
  stay locked. The reply token belongs to OpenBubbles and may be cancelled when it updates or
  dismisses a notification; a retained-action send is best effort rather than guaranteed. Tokens
  remain usable after notification removal only while the bridge process stays alive and
  OpenBubbles keeps them valid. Android cannot persist these tokens to disk. After process death,
  an app update, or reboot, an active/new notification is needed to capture them again. Missing or
  rejected actions produce an error, not a queue waiting for unlock. Offline transport still uses
  the outbox; queued messages without a current reply capability wait for one, not for unlocking.
- **No older-message history.** The bridge sees only the messages OpenBubbles includes in the
  notification. It cannot scroll OpenBubbles or load messages from its private database, so very
  old messages are not available on the watch.
- **Media comes from notifications.** Image bytes are copied only while OpenBubbles' notification
  still exposes its attachment URI. Photos are downscaled for the watch; GIFs and animated WebPs
  keep their animation when they fit the transfer budget, otherwise only the first frame is sent.
  OpenBubbles' **Hide text previews** setting also suppresses image data, so media will not appear
  while it is enabled.
- **Media caches are bounded.** The phone keeps roughly 80 attachments (80 MB), and the watch keeps
  the most recent 60 (40 MB). Older images may need to be re-sent by OpenBubbles before they can be
  viewed again.
- **Actions need a saved notification action.** If a notification is dismissed, cancelled by
  OpenBubbles, or lost after a reboot, mark-read is unavailable and reply works only while the saved
  reply action remains valid.
- **Long messages are capped.** Notification text is capped at 800 characters.
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
watch are recommended.

## Privacy and security

- The bridge requests notification-listener access, which is a sensitive capability. Its parser
  filters for `com.openbubbles.messaging` and `com.bluebubbles.messaging` only, but Android grants
  the listener access to all notifications.
- There is no accessibility service. The bridge does not read OpenBubbles' screen, database,
  credentials, or encryption keys.
- Shizuku setup changes only this bridge's notification grant and battery exemption. User-requested
  chat discovery additionally reads OpenBubbles' Android-published shortcut IDs and titles plus its
  notification channels for the current user. It does not access private files, notification-history
  databases, or root, and rejects root-mode Shizuku.
- Messages stay on the phone and watch. Nothing is sent to a third-party server by this project.
- Optional Contacts permission reads only names and phone/email addresses for watch-requested
  browsing/search. No contact photos, notes, account credentials, or third-party upload. Cached
  results are stored separately in the watch's private preferences. Revoking Contacts clears the
  cached list at the next phone metadata sync/contact response; offline copies cannot be remotely
  erased until the watch reconnects. Clearing watch app data/uninstalling removes its cache.
- The Wear Data Layer is provided and encrypted in transit by Google Play services.
- Reply text is passed to OpenBubbles' own notification `PendingIntent`, including the most recent
  saved reply action for a quiet chat. The bridge does not handle account credentials or encryption
  keys.
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

Source builds now produce phone/watch **2.1.6** (versionCode 19), using backward-compatible protocol
version 3. Update both apps together. Without the optional patch, sending still requires a live or
retained notification reply action. [Morphe notes](docs/MORPHE.md) describe the direct path.

### Project layout

| Path | Purpose |
| --- | --- |
| `protocol/` | Shared JVM library: wire models, serialization, paths, IDs. |
| `phone/` | Phone bridge, notification listener, cache, Data Layer publisher, setup UI. |
| `wear/` | Wear OS Compose app, Data Layer listener, cache, chat/find-chat UI. |
| `testnotifier/` | Debug-only fake OpenBubbles notification source. Never ship it. |
| `morphe/` | Separate Gradle build for the optional Wear bridge patch bundle. |
| `docs/` | Architecture and testing notes. |
| `work/` | Local reference/decompilation artifacts, not part of the app. |

## More documentation

- [Morphe preview](docs/MORPHE.md) — patch build, signing/certificate requirements, risks and limitations.
- [Architecture](docs/ARCHITECTURE.md) — notification parsing, caching, protocol, and action flow.
- [Testing](docs/TESTING.md) — fake notifications, adb commands, debug builds, and known emulator
  behavior.
- [2.0.2 build notes](docs/RELEASE-2.0.2.md) — retain real reply tokens for locked, quiet-chat sends.
- [2.0.1 release notes](docs/RELEASE-2.0.1.md) — OpenBubbles 2.x chat discovery and contact fixes.
- [2.0.0 release notes](docs/RELEASE-2.0.0.md) — accessibility removed; notification-only sending.

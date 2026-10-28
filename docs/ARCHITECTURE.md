# Architecture

## Goals

The project is a **companion**, not a second OpenBubbles client. It does not store Apple
credentials, does not implement iMessage encryption, does not read OpenBubbles' private database,
and does not open a network connection to Apple. The official OpenBubbles phone app remains
responsible for accounts, encryption, delivery, and read receipts.

There is one integration surface: **notifications**. OpenBubbles emits a `MessagingStyle`
notification per active conversation, including a reply `RemoteInput`, a mark-read action, recent
history, and the other person's handle. The bridge reads that notification, copies its data to the
watch, and invokes the notification's own `PendingIntent`s when the user acts. There is no
accessibility service and no screen scraping.

## Modules

| Module | Type | Responsibility |
| --- | --- | --- |
| `protocol` | JVM library | Shared `@Serializable` models, wire paths, constants, address normalizing, message-ID generation, transport trimming. |
| `phone` | Android app | Notification listener, parser, durable cache, Data Layer server, setup UI. |
| `wear` | Wear OS app | Data Layer client, watch cache, Compose UI, reply/find-chat/media requests. |
| `testnotifier` | Debug Android app | Fake `MessagingStyle` notifications with the real OpenBubbles package name. |

Application IDs:

- Phone: `com.openbubbles.wear` (source namespace `com.openbubbles.wear.bridge`)
- Watch: `com.openbubbles.wear`
- Fake test notifier: `com.openbubbles.messaging` (deliberately the official ID)

## High-level flow

```
┌──────────────────────────┐
│ OpenBubbles 2.0.x        │
│ MessagingStyle           │
│ notification             │
└───────────┬──────────────┘
            │ NotificationListenerService
            ▼
┌──────────────────────────┐       Data Layer        ┌──────────────────────┐
│ Phone bridge             │  messages + data items  │ Wear app             │
│ - parse notification     │ ──────────────────────> │ - cache + UI         │
│ - cache media + threads  │ <────────────────────── │ - sync/thread reqs   │
│ - send sync/updates      │   request/action msgs   │ - reply/media reqs   │
└───────────┬──────────────┘                         └──────────────────────┘
            │ action.actionIntent.send()
            ▼
┌──────────────────────────┐
│ OpenBubbles receiver     │
│ real send / read         │
└──────────────────────────┘
```

## Phone side

Phone and watch use the shared `companionApplicationId` from the root Gradle build, and must be
signed with the same key. Google's Data Layer requires both identities to match across devices.
The pre-1.3.0 phone ID `com.openbubbles.wear.bridge` was incompatible with watch transport; users
must remove that old phone bridge, install the corrected package, and grant permissions anew.

### Notification listener

`NotificationRelayService` is a `NotificationListenerService`. It attaches to `NotificationCenter`
on creation and connection. On `onListenerConnected` it rescans currently active notifications, so
a process restart or phone reboot can rebuild live state.

`NotificationCenter` keeps two in-memory maps:

- `liveByKey`: notification key → live notification.
- `liveByThread`: conversation ID → live notification.

The notification key can change even when the conversation does not. On an update, the old key is
removed and the new key becomes live. When a notification is removed, live state is dropped and the
thread is marked as history-only.

If the listener disconnects, live state is cleared and a sync is scheduled. The cache is retained,
so the watch can still read history. Sending remains possible only while a live or saved reply
action is available.

The content `PendingIntent` from each notification is persisted by `PendingIntentStore`, keyed by
thread ID, up to 40 entries. It is used to reopen the exact conversation later. OpenBubbles can
cancel the token at any time, so callers fall back to launching OpenBubbles normally.

The reply action's `PendingIntent` and free-form result key are persisted by `ReplyActionStore`,
also keyed by thread ID and bounded to 40 entries. The action is saved on every notification post
and is not removed when the notification disappears. This lets a quiet chat send through
OpenBubbles' own receiver without waking or unlocking the phone. The token still belongs to
OpenBubbles; it can be cancelled when OpenBubbles updates the notification, in which case the send
reports that the chat has no reply action and the watch keeps the message in the outbox.

### Notification parsing

`NotificationParser` uses `NotificationCompat.MessagingStyle.extractMessagingStyleFromNotification`
and handles the following:

- **Conversation ID**: `notification.shortcutId`, falling back to the notification key. The
  shortcut ID is stable across notification updates and identifies the same chat in both apps.
- **Title**: `MessagingStyle.conversationTitle` for groups, otherwise the notification title.
- **Outgoing messages**: OpenBubbles represents these with no sender person. A missing sender means
  `fromMe = true`. Incoming messages use a person/sender name.
- **Address**: for a one-to-one chat, the first message `Person.uri` is normalized to a phone
  number or email. Group chats leave this unset.
- **Group participants**: distinct incoming sender names, with a fallback to splitting the group
  title.
- **Attachments**: a non-null image `dataUri` sets `attachment = true`. The parser records the URI
  and MIME type, then `MediaCache` copies the bytes while the notification listener still holds the
  URI grant. OpenBubbles may send a caption and an image as separate messages with the same
  timestamp; they are merged into one `WireMessage` with the caption text and image metadata.
- **Text**: trimmed and capped at 800 characters.
- **Actions**: the first action with remote inputs is the reply action; an action containing
  "read" is mark-read. Availability is reflected in `canReply` / `canMarkRead`. `canSend` remains in
  the protocol as a capability field but is set equal to `canReply`, because notification actions
  are the only supported send path.

Message IDs are derived from `(threadId, timestamp, sender, text)` and hashed with SHA-1. Attachment
state is deliberately excluded. The same message can be republished with or without image data,
and the ID must not change in that case. If a notification contains two identical messages at the
same timestamp, a numeric suffix disambiguates them.

### Cache

`BridgeStore` persists notification-derived threads as JSON in `SharedPreferences`.

- Up to 40 threads.
- Up to 240 messages per thread.
- Merge by stable message ID, so older observed messages survive after OpenBubbles trims them from
  its notification.
- If a previously known message had an attachment and a refresh omits the image data, the
  attachment marker and any known media metadata are preserved.
- Successful sends are recorded immediately as `auto-<requestId>` outgoing messages. When the real
  message later arrives, a matching outgoing message with the same text within two minutes
  replaces the optimistic entry.
- `upsertIncoming(..., mergeFromId)` migrates a legacy `new:<address>` conversation into the real
  shortcut-based thread when its first notification arrives. New `new:` threads are no longer
  created.
- Sorted by latest activity and capped to keep the store small.

This is not a full message database. It is a best-effort cache of what notifications exposed.

### Media cache

`MediaCache` turns the `dataUri` on `MessagingStyle.Message` entries into watch-friendly files.

- The notification listener copies the URI bytes into `filesDir/media_cache` while Android's
  temporary notification URI grant is valid. The grant is scoped to the listener package and does
  not require OpenBubbles to be modified.
- The MIME type is normalized and cross-checked against the file's magic bytes. Non-image
  attachments are ignored.
- Static images are decoded with `ImageDecoder`, scaled to at most 960 px on the longest side, and
  re-encoded as lossy WebP. The encoder retries at 720 px and 560 px if the first result exceeds
  the transfer budget.
- GIFs and animated WebPs are preserved byte-for-byte when they are at most 6 MB, so the watch can
  animate them. Larger animations fall back to their first frame.
- A stable media ID is derived from `(message ID, normalized MIME type)`, so repeated notification
  updates resolve to the same cached file.
- The cache keeps at most 80 files / 80 MB, prunes anything no longer referenced by a cached thread,
  and deletes orphaned temporary files. Clearing the bridge cache also clears media and its data
  items.
- The phone reports the cache size in its status card. The setup UI warns that OpenBubbles' **Hide
  text previews** setting suppresses attachment URIs.

### Publish behavior

`NotificationCenter` publishes in two ways:

1. **Live messages** (`MessageClient`) for immediate UI updates: `sync`, `thread`, `update`, action
   results, and media results.
2. **Durable data items** (`DataApi`) so a watch app that was not running can restore a recent
   snapshot: a sync summary plus one item per thread. Attachment bytes are published separately as
   one data item per requested media ID.

`WatchLink` encodes thread IDs as base64url path segments because shortcut IDs may contain `/`,
spaces, or other unsafe characters. Media IDs are hashes, so they are used directly in
`/openbubbles_wear/media/<mediaId>`. It prunes data items for threads and media that no longer exist.

When the watch asks for a photo, the phone publishes a data item containing an `Asset` plus MIME
type, dimensions, size, and a fresh `published_at` value. The timestamp forces a change event even
when the same photo is requested twice. Assets are transferred separately from the data-item map,
so they are not subject to the 100 KB item-map limit. The bridge caps an individual asset at 8 MB.

The Wear Data Layer rejects items over 100 KB. `Wire.threadForTransport` drops oldest messages
until a thread encodes to 90 KB or less. The phone's cache is not trimmed by this; only the copy
sent to the watch is.

### Notification actions

`NotificationActions` invokes notification-owned `PendingIntent`s. It does not construct its own
OpenBubbles intents.

- **Reply / send**: find the action with `RemoteInput`, write the reply into the remote-input
  bundle, and send the action intent. OpenBubbles receives the text and does the real send. The same
  call works with a `SavedReply` restored from `ReplyActionStore`, which is why replies do not need
  the notification to still be visible.
- **Mark read**: find the mark-read action and send it. OpenBubbles commonly cancels the
  notification after this, so the bridge records that the live action is gone but keeps the cached
  history and saved reply action.
- **Open on phone**: use the live notification content intent, then the saved content intent, then
  launch the OpenBubbles package.

The phone applies a short read marker after a successful reply and republishes the thread.

### Optional assisted setup and diagnostics

`ShizukuSetup` uses Shizuku's shell process API for user-requested setup and chat discovery.
Notification access is granted through `cmd notification allow_listener`, not by editing Android's
legacy settings mirror. Battery exemption uses `cmd deviceidle whitelist` with a `dumpsys`
fallback. Commands are bounded by a ten-second shell timeout and 1 MB output limit; root-mode
Shizuku is rejected. Setup steps have manual Android Settings equivalents and OpenBubbles itself is
never modified.

`PublishedChatDiscovery` reads two public Android surfaces for the installed OpenBubbles package and
the bridge's current Android user:

- `cmd shortcut get-shortcuts --user USER 'PACKAGE'`: the platform default includes dynamic,
  pinned, cached, and manifest shortcuts. OpenBubbles 2.x publishes each conversation as
  `chat_<key>`, where the key is a chat GUID, `addr:<address>`, or `contact:<Android contact id>`.
  A bounded parser accepts those keys plus legacy bare GUIDs, requires a non-redacted title and a
  matching owner, and discards disabled, foreign, and malformed entries. Only GUID and `addr:`
  keys carry an address; a `contact:`-keyed chat is listed by title until it posts a notification.
- `dumpsys notification --noredact --package 'PACKAGE'`: OpenBubbles 2.x creates a conversation
  channel `conv_chat_<key>` under its `messages` parent, named for the chat. Legacy
  `com.bluebubbles.new_messages.<GUID>` channels and the `Notify Anyways: ` prefix remain
  supported. Deleted/redacted channels and non-chat channels are discarded.

Group status comes only from `;+;` GUIDs, never from a title. Neither surface is a complete chat
directory and neither reads notification-history databases or private storage. Imports preserve
existing cached messages, unread state, participants, and send ability. Newly discovered chats are
stored as empty placeholders so they cannot evict a conversation with real history. Because that
placeholder budget is bounded, discovery reports only the chats the store actually kept; otherwise
tapping an evicted entry would answer "Conversation not found". Discovery runs automatically at
most every ten minutes off the main thread and is also available manually.

The **Notification access blocked?** recovery dialog explains Android's sideload restrictions and
links separately to this package's App info and the exact listener's special-access page. Its
explicit **Unblock with Shizuku** action sets `ACCESS_RESTRICTED_SETTINGS` only for the bridge and
current Android user before requesting the listener grant. Normal assisted setup does not change
that app-op. `NotificationManager.isNotificationListenerAccessGranted` is checked for the exact
component, with a short propagation wait: command exit code zero alone is not success. OEM
enhanced-confirmation or administrator restrictions may still require manual approval and are not
silently bypassed.

The phone UI reports the actual notification-listener connected callback separately from service
creation, the last observed OpenBubbles notification, installed OpenBubbles version, battery
optimization, and Shizuku availability/permission. A reconnect control requests a listener rebind.

Notification components are compared as parsed `ComponentName`s, not raw strings: Android can
report `com.openbubbles.wear/.bridge.NotificationRelayService` while our code constructs the
equivalent fully qualified spelling. The phone bridge does not request `POST_NOTIFICATIONS` or post
its own phone alerts. Samsung's greyed-out App info → Notifications switch is therefore not a
listener-permission failure; the setup UI explains that special Notification access is separate. A
user-requested diagnostics copy contains only device/app versions and scoped permission/connection
flags, never message content, contacts, notification text, or other apps' enabled-service entries.

`ActionDedupe` atomically claims request IDs and commits a journal entry before calling
OpenBubbles. Completed results are persisted and replayed instead of repeating a send. A crash
between the side effect and saving its result leaves an uncertain failure marker: a replay must not
blindly send it again. The journal retains 200 IDs; it is duplicate protection, not an
exactly-once delivery promise.

## Watch side

### Contact directory and group browsing

OpenBubbles on Android sources contacts from Android's address book. `ContactDirectory` reads that
same public provider only after the bridge's optional runtime `READ_CONTACTS` grant; no private
OpenBubbles files, SMS provider, root, or new OpenBubbles API is involved. Watch requests specify a
bounded query and offset. Parameterized provider selection covers display name and address, and
returns only names, stable local IDs, and normalized phone/email choices. Multiple choices are
not silently reduced to one number. Non-address records, contact photos, notes, and account data
are excluded. Reads happen on request, not through background address-book scraping.

`ContactsPage` holds up to 40 contacts / 10 addresses per contact. JSON is trimmed to 60 KB; the
continuation advances by the number actually transmitted so trimming does not skip contacts.
Paging/cache is bounded at 5,000 contacts; users can search instead of loading a long list. The
watch correlates request ID, query and offset, ignores stale responses, and times out after 20 s.
Its contact cache uses separate private preferences, avoiding reserializing the address book
during each journaled message send. Revocation is signaled by nullable `contactsAccess` on metadata
or the contact response; explicit false clears the cache, while older metadata without the field
does not pretend to revoke permission. Offline copies remain until a revocation signal arrives.

The watch's find-chat picker includes **Choose a contact**, recent direct chats even when no
address was exposed, and **Published chats**. Contacts are matched to existing cached/published
chats by normalized address; an unknown contact is reported, not created. Group browsing filters
the same cached summaries rather than fabricating one-to-one recipients. Group notification shortcut
IDs and sender labels survive parsing/sync and are used for replies. Groups and direct chats may
also be discovered from Android-published shortcuts and notification channels through optional
Shizuku access without a notification. This is only a partial list; the integration does not
enumerate OpenBubbles' private chat database, notification-history database, or create new groups.

`WearListenerService` receives live messages and data-item changes. It forwards them to
`WatchRepository`, which owns all watch state and persistence.

### State

`WatchState` contains:

- Conversation summaries for the list.
- Full threads that the user has opened or that arrived live.
- Phone/bridge metadata, notification access, relay state, secure-lock and screen-awake state.
- Connectivity (`connected`, `reachable`), sync timestamps, quick replies.
- In-flight send/media requests.
- Cached media metadata (file path, MIME type, dimensions, size) keyed by media ID.
- A persisted outbox, alert preference, notification permission, and the currently visible thread.
- Transient UI notices (`error`, `info`).

The watch cache persists up to 40 threads with messages and up to 220 messages per thread. It is
designed for offline reading, not as an authoritative history. The phone store keeps 240 messages
per real conversation and separately budgets up to 40 message-bearing conversations and 60
discovery placeholders. Media is persisted separately: the watch keeps the most recent 60 files /
40 MB in `filesDir/watch_media` and records their metadata in SharedPreferences.

### Sync and thread aliasing

- On app start and resume, the watch checks reachability, pings the phone, and requests a sync if
  the last one is older than 15 seconds.
- Opening a chat sends a `thread_request`; the phone returns the cached thread.
- A watch-side conversation summary can exist before its full thread is loaded. The UI shows a
  loading state until the thread arrives.
- Cached data items are applied with `fromCache = true`, so they restore content without claiming
  the phone is online.
- When a real thread arrives with the same normalized address as a legacy temporary
  `new:<address>` thread, the watch merges their messages into the real ID and removes the
  temporary entry. Sync summaries do the same cleanup without needing the full thread.

### Addresses and existing chats

Older watch builds could start a chat with the temporary ID `new:<normalized address>`. That
migration path is kept so cached/queued items from those builds still resolve, but new chats are no
longer created on the watch. `findChatByAddress` only looks up an existing thread or summary by
normalized address and reports an error when none exists. The latest phone-sync summary wins over
a cached thread, so an ID from an older OpenBubbles or bridge version cannot override the current
one and produce "Conversation not found".

`Protocol.normalizeAddress` lowercases emails, strips `tel:`/`mailto:`/`sms:` prefixes and iMessage
`;-;` service prefixes, and reduces phone numbers to digits and `+`. This lets a typed address and
a notification's `Person.uri` resolve to the same contact.

### Optimistic sends

When the user sends, the watch immediately adds a local message with ID `local-<requestId>` and
status `queued`, and synchronously journals it before transport. The outbox rejects additional
messages at 100 entries instead of dropping old ones.

- All new sends use `/send` with a required thread ID and text. The phone chooses the current live
  reply action, then the saved reply action. There is no automation fallback.
- A chat without `canReply` is rejected on the watch before transport and shows an explanation.
- Queue replay is single-flight and stops while relay is paused. Entries remain queued on transport
  failure. After transport acceptance the journal entry is removed and the bubble becomes
  `sending`.
- Confirmation can arrive before transport acceptance; the callback must not overwrite a completed
  result. Unconfirmed accepted requests fail after 100 seconds and are not blindly requeued.
- The phone's notification refresh or action result later contains the real outgoing message. The
  watch merges it by matching text and timestamp within a five-minute window and removes the local
  placeholder.
- A failed action marks the local message `failed` and leaves the outbox.
- A send result is matched back to its thread through the request-ID map, so a temporary thread is
  updated even if the phone returns a different thread ID.
- Legacy temporary-thread migration also remaps queued requests and in-flight IDs. Summary syncs
  retain local conversations that still have pending messages. An unsent identical-text bubble is
  not removed just because an older matching message exists.

### Notifications and glanceable surfaces

`WatchNotifications` builds local-only `MessagingStyle` notifications on a high-importance channel,
with mutable explicit RemoteInput reply intents, immutable mark-read/open intents, and private
lock-screen visibility. Thread-specific URI identities and notification tags prevent hash
collisions from redirecting a reply. Per-thread notification timestamps suppress repeat alerts;
upgrade initializes a baseline from the existing cache.

`WearApp` tracks visibility only while the activity is resumed, so backgrounding a chat does not
keep suppressing alerts. Opening a thread marks it read locally. Extra alerts are opt-out in watch
Settings because the system can independently mirror OpenBubbles' phone notifications.

`WatchTileService` renders cached unread conversations with launch actions and supplies an empty
versioned resource bundle. `UnreadComplicationService` supports SHORT_TEXT, LONG_TEXT, and
RANGED_VALUE, with a tap intent into the newest unread chat. Counts represent unread conversations.
`WatchSurfaces` requests refreshes when rendered summaries, connectivity, or outbox counts change.

### Media viewing

- A media bubble shows **Load photo** or **Load GIF** until its bytes are present on the watch.
  Tapping it sends `/media_request` with the stable media ID.
- The phone republishes the cached file as a data-item asset. `WearListenerService` sees the
  `/media/<id>` change, calls `DataClient.getFdForAsset`, copies the bytes to `filesDir/watch_media`,
  and records the metadata. The repository clears the per-ID loading state and trims the cache.
- The chat bubble switches to a Coil `AsyncImage` thumbnail. Tapping the thumbnail opens the
  full-screen viewer, which also uses Coil so GIFs and animated WebPs keep playing. Pinch zooms and
  double-tap resets the image.
- Media requests time out after 45 seconds. A phone error, an unreachable phone, a pruned phone
  cache, or a failed asset read produces a notice and a **Try again** action.
- Media metadata on `WireMessage` is preserved across notification refreshes and thread merges so
  an already-known photo does not degrade to a text placeholder.
- The Watch UI never requests media automatically; only the bubble the user taps is transferred.

### UI

The Compose for Wear OS UI uses `AppScaffold` + `TimeText` and `ScalingLazyColumn` screens:

- Conversation list with **Find a chat** and empty/offline states.
- Find-chat screen with an address remote-input lookup and recent one-to-one contacts. It opens
  only existing chats.
- Chat screen with message bubbles, sender labels, status, reply, and mark-read actions, plus
  photo/GIF thumbnails and loading states.
- Full-screen media viewer with GIF playback, pinch zoom, and double-tap reset.
- Composer with quick replies and Wear remote input for voice/keyboard.
- Settings with connection, relay, cache, and limitation information.

Navigation is a small explicit stack with back and swipe-to-dismiss handling.

## Protocol

Protocol version is **3**. All message paths live under `/openbubbles_wear`. Payloads are UTF-8
JSON produced by kotlinx.serialization with unknown keys ignored.

| Path | Direction | Purpose |
| --- | --- | --- |
| `/ping` | watch → phone | Probe; includes watch name. |
| `/pong` | phone → watch | Phone name, bridge version, access/relay/lock state, quick replies. |
| `/sync_request` | watch → phone | Request a full summary sync. |
| `/sync` | phone → watch | Summaries, metadata, relay/notification state. |
| `/thread_request` | watch → phone | Request full messages for one thread. |
| `/thread` | phone → watch | Thread payload or error. |
| `/update` | phone → watch | Live thread update after a notification or action. |
| `/reply` | watch → phone | Reply through a live or saved notification action. |
| `/reply_result` | phone → watch | Reply outcome. |
| `/send` | watch → phone | Send through a live or saved reply action. |
| `/mark_read` | watch → phone | Mark-read request. |
| `/action_result` | phone → watch | Action result (reply, send, open-on-phone, errors). |
| `/open_phone` | watch → phone | Ask the phone to open OpenBubbles. |
| `/media_request` | watch → phone | Ask the phone to publish one cached attachment. |
| `/media_result` | phone → watch | Whether the media data item was published, or an error. |
| `/contacts_request` | watch → phone | Request a name/address-filtered page from the permitted Android address book. |
| `/contacts_result` | phone → watch | Correlated contact page, address choices, continuation, permission status, or error. |
| `/groups_request` | watch → phone | Explicit request to find OpenBubbles-published shortcuts/channels using Shizuku. |
| `/groups_result` | phone → watch | Correlated partial groups plus direct chats, note, or error. |

`SyncPayload` and `PongPayload` carry `phoneLocked` and `phoneAwake` for display. `ThreadSummary`
carries `address`, `canSend`, and reply/mark-read flags; `canSend` mirrors `canReply`. `SendRequest`
carries a required `threadId` and `text`. `ActionResult` carries an optional `retryable` flag for
backward compatibility. `WireMessage` carries optional `mediaId`, `mediaMime`, `mediaWidth`,
`mediaHeight`, and `mediaSize`. `MediaRequest` carries the media ID; `MediaPayload` echoes it with
the transfer result.

Durable Data API paths:

- `/openbubbles_wear/sync`
- `/openbubbles_wear/thread/<base64url(threadId)>`
- `/openbubbles_wear/media/<mediaId>`

Thread data items use the key `json` and the transport-trimmed copy. Media data items use `asset`,
`mime`, `width`, `height`, `size`, and `published_at`.

Capabilities are advertised through `res/values/wear.xml`:

- Phone: `openbubbles_wear_bridge`
- Watch: `openbubbles_wear`

Both services are registered with fine-grained Wear intent filters (`MESSAGE_RECEIVED`,
`DATA_CHANGED`, `CAPABILITY_CHANGED`) instead of the deprecated `BIND_LISTENER`.

## Why not use OpenBubbles directly?

The supplied 2.0.0 build has no Wear OS module, no public message API, and no exported content
provider for chat history. Notifications and Android-published conversation shortcuts provide
integration surfaces without modifying or repackaging the official app.

Non-root Shizuku cannot read OpenBubbles' private database or invoke its non-exported components.
It can query Android-published shortcuts and notification channels for partial group/direct chat
discovery, but it cannot supply complete history or a complete private chat list. Notification-history
databases are signature-protected and are not read. This project intentionally avoids root, a custom
OpenBubbles build, and private-file access.

### WearBubbles reference

The user-suggested [WearBubbles](https://github.com/mdunlap/WearBubbles) was reviewed for group and
history loading. Its `BlueBubblesApi`, `ChatRepository`, `MessageRepository` and
`MessageDetailViewModel` load chats/messages directly from a self-hosted BlueBubbles Mac server
using `/api/v1/chat/query` and `/api/v1/message/query`; older pages use the oldest server timestamp
as a `before` cursor. The phone companion primarily supplies server configuration. That backend
is not an Android-app integration API and cannot be assumed available in standalone OpenBubbles.
Cache-first display, refresh-on-open and explicit older-page loading are useful UX patterns, but
this bridge has no supported way to read that history from standalone OpenBubbles. No WearBubbles
source code or Mac-server dependency was incorporated.

## Failure modes

- **No notification access**: no notification-derived threads are tracked; the phone UI reports the
  missing grant.
- **OpenBubbles absent**: notifications cannot appear; the phone UI reports this.
- **Data Layer unavailable**: parsing and caching still work, but the watch cannot sync. This is the
  behavior seen on the Google APIs phone emulator image.
- **Notification removed**: cache remains; mark-read disappears, but the saved reply action usually
  still sends. If OpenBubbles cancelled it, the send reports no reply action and the message stays
  in the watch outbox.
- **Phone securely locked**: cached chats, live and saved notification replies, incoming syncs, and
  published discovery remain available. The keyguard is not bypassed; notification reply actions
  are the only send path.
- **Chat never notified**: it has no reply action, so the watch rejects the send with an explanation
  instead of queueing an impossible operation.
- **Media unavailable**: OpenBubbles may omit attachment URIs when **Hide text previews** is on, the
  bridge may have already pruned the file, or the notification URI grant may have expired before it
  could be copied. The watch shows **Load photo** with a phone-reported error and can retry while
  the phone still has the file.
- **Emulator SystemUI bug**: Android 15 SystemUI can fail to inflate updated long
  `MessagingStyle` notifications and cancel them. The bridge handles removal correctly; this is
  not representative of a physical device.

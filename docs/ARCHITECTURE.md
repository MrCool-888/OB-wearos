# Architecture

## Goals

The project is a **companion**, not a second OpenBubbles client. It does not store Apple
credentials, does not implement iMessage encryption, does not read OpenBubbles' private database,
and does not open a network connection to Apple. The official OpenBubbles phone app remains
responsible for accounts, encryption, delivery, and read receipts.

There are two integration surfaces:

1. **Notifications (default).** OpenBubbles emits a `MessagingStyle` notification per active
   conversation, including a reply `RemoteInput`, a mark-read action, recent history, and the
   other person's handle. The bridge reads that notification, copies its data to the watch, and
   invokes the notification's own `PendingIntent`s when the user acts.
2. **Optional accessibility automation.** When a notification is gone, or when a conversation does
   not exist yet, the bridge can drive OpenBubbles' own Compose UI to open a chat, type into its
   composer, press Send, and scroll back through older bubbles. The official APK is never
   modified.

The automation is deliberately a fallback. Live reply and mark-read still use notification
actions, which are faster and do not require OpenBubbles to be visible.

## Modules

| Module | Type | Responsibility |
| --- | --- | --- |
| `protocol` | JVM library | Shared `@Serializable` models, wire paths, constants, address normalizing, message-ID generation, transport trimming. |
| `phone` | Android app | Notification listener, parser, durable cache, optional accessibility automation, Data Layer server, setup UI. |
| `wear` | Wear OS app | Data Layer client, watch cache, Compose UI, reply/send/history requests. |
| `testnotifier` | Debug Android app | Fake `MessagingStyle` notifications with the real OpenBubbles package name. |

Application IDs:

- Phone: `com.openbubbles.wear.bridge`
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
│ - send sync/updates      │   request/action msgs   │ - reply/send/history │
└───────┬──────────┬───────┘                         └──────────────────────┘
        │          │
        │          │ optional accessibility job
        │          ▼
        │   ┌──────────────────────────┐
        │   │ OpenBubbles Compose UI   │
        │   │ new chat / composer /    │
        │   │ conversation scroll      │
        │   └───────────┬──────────────┘
        │               │ user-granted automation
        │               ▼
        │          real send / display
        │
        │ action.actionIntent.send()
        ▼
┌──────────────────────────┐
│ OpenBubbles receiver     │
│ real send / read         │
└──────────────────────────┘
```

## Phone side

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
so the watch can still read history. If automation is enabled, actions remain available even
without a live notification.

The content `PendingIntent` from each notification is persisted by `PendingIntentStore`, keyed by
thread ID, up to 40 entries. It is used to reopen the exact conversation later. OpenBubbles can
cancel the token at any time, so callers fall back to UI navigation.

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
  "read" is mark-read. Availability is reflected in `canReply` / `canMarkRead`, and `canSend` is
  true when either a reply action or automation is available.

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
- Automation sends are recorded immediately as `auto-<requestId>` outgoing messages. When the real
  message later arrives, a matching outgoing message with the same text within two minutes
  replaces the optimistic entry.
- `mergeHistory` folds accessibility-scraped messages into the cache, creating a fallback thread
  for a brand-new address that has never produced a notification.
- `upsertIncoming(..., mergeFromId)` migrates a temporary `new:<address>` conversation into the
  real shortcut-based thread when its first notification arrives.
- Sorted by latest activity and capped to keep the store small.

This is not a full message database. It is a best-effort cache of what notifications exposed and
what automation could read from the screen.

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
   results, history results, and media results.
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

- **Reply**: find the action with `RemoteInput`, write the reply into the remote-input bundle, and
  send the action intent. OpenBubbles receives the text and does the real send.
- **Mark read**: find the mark-read action and send it. OpenBubbles commonly cancels the
  notification after this, so the bridge records that the live action is gone but keeps the cached
  history.
- **Open on phone**: use the live notification content intent, then the saved content intent, then
  launch the OpenBubbles package.

The phone applies a short read marker after a successful reply and republishes the thread.

### Accessibility automation

`OpenBubblesAutomationService` is an optional `AccessibilityService` registered with
`BIND_ACCESSIBILITY_SERVICE`. Its configuration:

- Receives window state/content-change events and declares `canRetrieveWindowContent` and
  `canPerformGestures`.
- Declares `flagReportViewIds`, `flagRetrieveInteractiveWindows`, and
  `flagIncludeNotImportantViews`.
- Filters events to `com.openbubbles.messaging` and `com.bluebubbles.messaging`.
- Does no work on events. `OpenBubblesAutomation` polls the UI only while a watch-requested job is
  active.

`OpenBubblesAutomation` serializes jobs with a mutex. A send has a 90-second timeout; history has a
120-second timeout. Jobs run on the main dispatcher because accessibility node operations must run
there. The watch uses slightly longer client-side timeouts so it receives the phone's error instead
of timing out first.

#### Opening a conversation

1. Send the saved notification content `PendingIntent` and wait for a composer. This is the exact
   chat and avoids guessing a title.
2. If the target is a new `new:<address>` thread, launch OpenBubbles with `new_chat_address` and
   `new_chat_name` extras and wait for its composer.
3. Otherwise launch OpenBubbles' main activity and try, in order:
   - use the visible search field or search entry, type the title, and click the best result;
   - click a visible conversation row whose label contains the title;
   - scroll the conversation list and retry;
   - as a last resort, launch a new chat from the known address.

#### Sending

- Find an editable node near the bottom of the OpenBubbles window, excluding search fields.
- Set the text with `ACTION_SET_TEXT`; if that does not take, place it on the clipboard and use
  `ACTION_PASTE`.
- Find the Send control by content description/text (`send`, `send message`, `send iMessage`) or
  view ID, then by its position and size relative to the composer.
- Click it. A send is considered confirmed when the composer empties or the message text appears
  outside the composer. `ACTION_IME_ENTER` is a fallback if the button click does not confirm.
- OpenBubbles performs the actual encryption and transport.

#### Loading history

- Open the conversation and ensure its composer is visible.
- Scroll to the bottom, then repeatedly scrape visible leaf text nodes inside the main scrollable
  conversation view while scrolling backward.
- Filter out dates, timestamps, delivery/read labels, reactions, participant names, the chat title,
  and known UI labels.
- Infer `fromMe` from bubble alignment: right-aligned bubbles are outgoing.
- Merge overlapping pages and keep screen-order (top-to-bottom) within each page.
- Match the scraped sequence against the messages already cached and keep only bubbles before the
  oldest known one. This is a text/alignment match because accessibility exposes no message GUIDs.
- Cap at 30 pages. `hasMore` is true when the requested count is exceeded or the top was not
  reached.
- Assign synthetic timestamps 60 seconds apart before the oldest cached message. Ordering is
  preserved, but exact times are not available.

The selector tuning surface is `OpenBubblesAutomation.kt`. The selectors are best effort and have
not yet been tuned against a real OpenBubbles Compose accessibility tree, so real-device
validation is required.

#### Safety and limits

- The service only looks at windows whose package is an OpenBubbles package.
- It never reads or modifies OpenBubbles' database, files, credentials, or encryption state.
- It only runs when the watch requests a send or history job; there is no background scraping.
- It does not construct OpenBubbles' internal intents or call non-exported components.
- It does not use root or Shizuku.

## Watch side

`WearListenerService` receives live messages and data-item changes. It forwards them to
`WatchRepository`, which owns all watch state and persistence.

### State

`WatchState` contains:

- Conversation summaries for the list.
- Full threads that the user has opened or that arrived live.
- Phone/bridge metadata, notification access, relay state, automation availability.
- Connectivity (`connected`, `reachable`), sync timestamps, quick replies.
- In-flight send/history/media requests and per-thread history exhaustion state.
- Cached media metadata (file path, MIME type, dimensions, size) keyed by media ID.
- Transient UI notices (`error`, `info`).

The watch cache persists up to 40 threads and 220 messages per thread. It is designed for offline
reading, not as an authoritative history. Media is persisted separately: the watch keeps the most
recent 60 files / 40 MB in `filesDir/watch_media` and records their metadata in SharedPreferences.

### Sync and thread aliasing

- On app start and resume, the watch checks reachability, pings the phone, and requests a sync if
  the last one is older than 15 seconds.
- Opening a chat sends a `thread_request`; the phone returns the cached thread.
- A watch-side conversation summary can exist before its full thread is loaded. The UI shows a
  loading state until the thread arrives.
- Cached data items are applied with `fromCache = true`, so they restore content without claiming
  the phone is online.
- When a real thread arrives with the same normalized address as a temporary `new:<address>`
  thread, the watch merges their messages into the real ID and removes the temporary entry. Sync
  summaries do the same cleanup without needing the full thread.

### Addresses and new chats

A chat started from the watch gets the temporary ID `new:<normalized address>`. The phone creates
the same ID, so optimistic messages and the phone's automation result refer to one conversation.
Once OpenBubbles posts the first notification, its shortcut-based thread replaces the temporary one
on both sides.

`Protocol.normalizeAddress` lowercases emails, strips `tel:`/`mailto:`/`sms:` prefixes and iMessage
`;-;` service prefixes, and reduces phone numbers to digits and `+`. This lets a typed address and
a notification's `Person.uri` resolve to the same contact.

### Optimistic sends

When the user sends, the watch immediately adds a local message with ID `local-<requestId>` and
status `sending`.

- If the thread has a live reply action, the watch sends `/reply` and waits 15 seconds.
- Otherwise it sends `/send` with the thread ID, address, title, and text, and waits 100 seconds for
  automation.
- The phone's notification refresh or action result later contains the real outgoing message. The
  watch merges it by matching text and timestamp within a five-minute window and removes the local
  placeholder.
- A failed action marks the local message `failed`.
- A send result is matched back to its thread through the request-ID map, so a temporary thread is
  updated even if the phone returns a different thread ID.

### History loading

`loadOlder` sends `/history` with the thread ID, title, address, and a limit of 60. While the
request is in flight, the chat shows **Loading older…**. A result is merged by message ID, sorted
chronologically, and capped at 220. `hasMore = false` marks the thread exhausted and the UI shows
**No older messages**. Loading history prepends messages without changing the newest message ID, so
the chat only auto-scrolls when a genuinely new message arrives.

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
- Media metadata on `WireMessage` is preserved across notification refreshes, thread merges, and
  history merges so an already-known photo does not degrade to a text placeholder.
- The Watch UI never requests media automatically; only the bubble the user taps is transferred.

### UI

The Compose for Wear OS UI uses `AppScaffold` + `TimeText` and `ScalingLazyColumn` screens:

- Conversation list with **New message** and empty/offline states.
- New-message screen with an address remote-input action and recent one-to-one contacts.
- Chat screen with message bubbles, sender labels, status, send/reply, history, and mark-read
  actions, plus photo/GIF thumbnails and loading states.
- Full-screen media viewer with GIF playback, pinch zoom, and double-tap reset.
- Composer with quick replies and Wear remote input for voice/keyboard.
- Settings with connection, relay, automation, cache, and limitation information.

Navigation is a small explicit stack with back and swipe-to-dismiss handling.

## Protocol

Protocol version is **3**. All message paths live under `/openbubbles_wear`. Payloads are UTF-8
JSON produced by kotlinx.serialization with unknown keys ignored.

| Path | Direction | Purpose |
| --- | --- | --- |
| `/ping` | watch → phone | Probe; includes watch name. |
| `/pong` | phone → watch | Phone name, bridge version, access/relay/automation state, quick replies. |
| `/sync_request` | watch → phone | Request a full summary sync. |
| `/sync` | phone → watch | Summaries, metadata, relay/notification/automation state. |
| `/thread_request` | watch → phone | Request full messages for one thread. |
| `/thread` | phone → watch | Thread payload or error. |
| `/update` | phone → watch | Live thread update after a notification or action. |
| `/reply` | watch → phone | Reply through a live notification action. |
| `/reply_result` | phone → watch | Reply outcome. |
| `/send` | watch → phone | Send through OpenBubbles' composer when no live reply action exists. |
| `/mark_read` | watch → phone | Mark-read request. |
| `/action_result` | phone → watch | Action result (reply, send, open-on-phone, errors). |
| `/open_phone` | watch → phone | Ask the phone to open OpenBubbles. |
| `/history` | watch → phone | Ask the phone to scrape older messages from OpenBubbles' UI. |
| `/history_result` | phone → watch | Older messages, `hasMore`, or an error. |
| `/media_request` | watch → phone | Ask the phone to publish one cached attachment. |
| `/media_result` | phone → watch | Whether the media data item was published, or an error. |

`SyncPayload` and `PongPayload` carry `automationAvailable`. `ThreadSummary` carries `address`,
`canSend`, and the existing reply/mark-read flags. `SendRequest` carries an optional `threadId`,
`address`, `title`, and `text`. `HistoryRequest` carries `threadId`, optional `title`/`address`, and
a `limit`. `WireMessage` carries optional `mediaId`, `mediaMime`, `mediaWidth`, `mediaHeight`, and
`mediaSize`. `MediaRequest` carries the media ID; `MediaPayload` echoes it with the transfer result.

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
provider for chat history. The notification and accessibility UI are the supported integration
surfaces that do not require modifying or repackaging the official app.

Non-root Shizuku cannot read OpenBubbles' private database or invoke its non-exported components,
so it would not remove these limits. Root, a custom OpenBubbles build, or internal APIs would, but
this project intentionally avoids all three so it keeps working with the official APK.

## Failure modes

- **No notification access**: no threads are tracked; the phone UI reports this.
- **OpenBubbles absent**: notifications cannot appear; the phone UI reports this.
- **Data Layer unavailable**: parsing, caching, and automation still work, but the watch cannot
  sync. This is the behavior seen on the Google APIs phone emulator image.
- **Notification removed**: cache remains; mark-read disappears, and reply/send require automation.
- **Automation disabled**: new chats, quiet-chat sends, and history requests fail with a setup
  message; live notification replies still work.
- **Media unavailable**: OpenBubbles may omit attachment URIs when **Hide text previews** is on, the
  bridge may have already pruned the file, or the notification URI grant may have expired before it
  could be copied. The watch shows **Load photo** with a phone-reported error and can retry while
  the phone still has the file.
- **Automation blocked**: Android may refuse a background activity launch or OpenBubbles may not
  become visible. The send/history job returns an error to the watch after its timeout.
- **Selector change**: a future OpenBubbles UI can move the composer, Send button, or search field.
  The job reports that it could not find the control, and the selectors in
  `OpenBubblesAutomation.kt` need tuning.
- **Ambiguous history**: repeated text or heavy truncation can confuse the text-based anchor. The
  bridge avoids obvious duplicates but cannot guarantee a perfect match without message GUIDs.
- **Emulator SystemUI bug**: Android 15 SystemUI can fail to inflate updated long
  `MessagingStyle` notifications and cancel them. The bridge handles removal correctly; this is
  not representative of a physical device.

# Architecture

## Goals

The project is a **companion**, not a second OpenBubbles client. It does not store Apple
credentials, does not implement iMessage encryption, and does not open a network connection to
Apple. The official OpenBubbles phone app remains responsible for accounts, encryption, delivery,
and read receipts.

The only integration point is Android's notification system. OpenBubbles already emits a
`MessagingStyle` notification per active conversation, including a reply `RemoteInput` and a
mark-read action. The bridge reads that notification, copies its data to the watch, and calls the
notification's own `PendingIntent`s when the user acts on the watch.

## Modules

| Module | Type | Responsibility |
| --- | --- | --- |
| `protocol` | JVM library | Shared `@Serializable` models, wire paths, constants, message-ID generation. |
| `phone` | Android app | Notification listener, parser, durable cache, Data Layer server, setup UI. |
| `wear` | Wear OS app | Data Layer client, watch cache, Compose UI, reply/mark-read requests. |
| `testnotifier` | Debug Android app | Fake `MessagingStyle` notifications with the real OpenBubbles package name. |

Application IDs:

- Phone: `com.openbubbles.wear.bridge`
- Watch: `com.openbubbles.wear`
- Fake test notifier: `com.openbubbles.messaging` (deliberately the official ID)

## High-level flow

```
┌──────────────────────┐
│ OpenBubbles 2.0.x    │
│ MessagingStyle       │
│ notification         │
└──────────┬───────────┘
           │ NotificationListenerService
           ▼
┌──────────────────────┐       Data Layer        ┌──────────────────────┐
│ Phone bridge         │  messages + data items  │ Wear app             │
│ - parse notification │ ──────────────────────> │ - cache + UI         │
│ - merge with cache   │ <────────────────────── │ - sync/thread reqs   │
│ - send sync/updates  │   request/action msgs   │ - reply/mark-read    │
└──────────┬───────────┘                         └──────────────────────┘
           │ action.actionIntent.send()
           ▼
┌──────────────────────┐
│ OpenBubbles receiver │
│ real send / read     │
└──────────────────────┘
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
so the watch can still read history, but actions become unavailable until a notification appears
again.

### Notification parsing

`NotificationParser` uses `NotificationCompat.MessagingStyle.extractMessagingStyleFromNotification`
and handles the following:

- **Conversation ID**: `notification.shortcutId`, falling back to the notification key. The
  shortcut ID is stable across notification updates and identifies the same chat in both apps.
- **Title**: `MessagingStyle.conversationTitle` for groups, otherwise the notification title.
- **Outgoing messages**: OpenBubbles represents these with no sender person. A missing sender means
  `fromMe = true`. Incoming messages use a person/sender name.
- **Group participants**: distinct incoming sender names, with a fallback to splitting the group
  title.
- **Attachments**: a non-null image `dataUri` sets `attachment = true`. OpenBubbles may send a
  caption and an image as separate messages with the same timestamp; they are merged into one
  `WireMessage`.
- **Text**: trimmed and capped at 1200 characters.
- **Actions**: the first action with remote inputs is the reply action; an action containing
  "read" is mark-read. Availability is reflected in `canReply` / `canMarkRead`.

Message IDs are derived from `(threadId, timestamp, sender, text)` and hashed with SHA-1. Attachment
state is deliberately excluded. The same message can be republished with or without image data,
and the ID must not change in that case. If a notification contains two identical messages at the
same timestamp, a numeric suffix disambiguates them.

### Cache

`BridgeStore` persists notification-derived threads as JSON in `SharedPreferences`.

- Up to 40 threads.
- Up to 60 messages per thread.
- Merge by stable message ID, so older observed messages survive after OpenBubbles trims them from
  its notification.
- If a previously known message had an attachment and a refresh omits the image data, the
  attachment marker is preserved.
- Sorted by latest activity and capped to keep the store small.

This is not a full message database. It is a best-effort cache of what notifications exposed.

### Publish behavior

`NotificationCenter` publishes in two ways:

1. **Live messages** (`MessageClient`) for immediate UI updates: `sync`, `thread`, `update`, action
   results.
2. **Durable data items** (`DataApi`) so a watch app that was not running can restore a recent
   snapshot: a sync summary plus one item per thread.

`WatchLink` encodes thread IDs as base64url path segments because shortcut IDs may contain `/`,
spaces, or other unsafe characters. It prunes data items for threads that no longer exist.

### Actions

`NotificationActions` invokes notification-owned `PendingIntent`s. It does not construct its own
OpenBubbles intents.

- **Reply**: find the action with `RemoteInput`, write the reply into the remote-input bundle, and
  send the action intent. OpenBubbles receives the text and does the real send.
- **Mark read**: find the mark-read action and send it. OpenBubbles commonly cancels the
  notification after this, so the bridge records that the live action is gone but keeps the cached
  history.
- **Open on phone**: use the notification content intent when available, otherwise launch the
  OpenBubbles package.

The phone applies a short read marker after a successful reply and republishes the thread.

## Watch side

`WearListenerService` receives live messages and data-item changes. It forwards them to
`WatchRepository`, which owns all watch state and persistence.

### State

`WatchState` contains:

- Conversation summaries for the list.
- Full threads that the user has opened or that arrived live.
- Phone/bridge metadata, notification access, relay state.
- Connectivity (`connected`, `reachable`), sync timestamps, quick replies.
- Transient UI notices (`error`, `info`).

The watch cache persists up to 30 threads and 80 messages per thread. It is designed for offline
reading, not as an authoritative history.

### Sync

- On app start and resume, the watch checks reachability, pings the phone, and requests a sync if
  the last one is older than 15 seconds.
- Opening a chat sends a `thread_request`; the phone returns the cached thread.
- A watch-side conversation summary can exist before its full thread is loaded. The UI shows a
  loading state until the thread arrives.
- Cached data items are applied with `fromCache = true`, so they restore content without claiming
  the phone is online.

### Optimistic replies

When the user sends a reply, the watch immediately adds a local message with ID
`local-<requestId>` and status `sending`. The phone's notification refresh later contains the real
outgoing message. The watch merges it by matching text and timestamp within a five-minute window
and removes the local placeholder. A failed action marks the local message `failed`.

### UI

The Compose for Wear OS UI uses `AppScaffold` + `TimeText` and `ScalingLazyColumn` screens:

- Conversation list and empty/offline states.
- Chat screen with message bubbles, sender labels, status, and actions.
- Composer with quick replies and Wear remote input for voice/keyboard.
- Settings with connection, relay, cache, and limitation information.

Navigation is a small explicit stack with back and swipe-to-dismiss handling.

## Protocol

All message paths live under `/openbubbles_wear`. Payloads are UTF-8 JSON produced by
kotlinx.serialization with unknown keys ignored.

| Path | Direction | Purpose |
| --- | --- | --- |
| `/ping` | watch → phone | Probe; includes watch name. |
| `/pong` | phone → watch | Phone name, bridge version, access/relay state, quick replies. |
| `/sync_request` | watch → phone | Request a full summary sync. |
| `/sync` | phone → watch | Summaries, metadata, relay/notification state. |
| `/thread_request` | watch → phone | Request full messages for one thread. |
| `/thread` | phone → watch | Thread payload or error. |
| `/update` | phone → watch | Live thread update after a notification or action. |
| `/reply` | watch → phone | Reply request with request ID. |
| `/reply_result` | phone → watch | Reply outcome. |
| `/mark_read` | watch → phone | Mark-read request. |
| `/action_result` | phone → watch | Action result (also used for open-on-phone). |
| `/open_phone` | watch → phone | Ask the phone to open OpenBubbles. |

Durable Data API paths:

- `/openbubbles_wear/sync`
- `/openbubbles_wear/thread/<base64url(threadId)>`

Data item value key: `json`.

Capabilities are advertised through `res/values/wear.xml`:

- Phone: `openbubbles_wear_bridge`
- Watch: `openbubbles_wear`

Both services are registered with fine-grained Wear intent filters (`MESSAGE_RECEIVED`,
`DATA_CHANGED`, `CAPABILITY_CHANGED`) instead of the deprecated `BIND_LISTENER`.

## Why not use OpenBubbles directly?

The supplied 2.0.0 build has no Wear OS module, no public message API, and no exported content
provider for chat history. The notification is the supported integration surface that does not
require modifying or repackaging the official app. A future direct integration would need
OpenBubbles-side changes, which this project intentionally avoids so it keeps working with the
official APK.

## Failure modes

- **No notification access**: no threads are tracked; the phone UI reports this.
- **OpenBubbles absent**: notifications cannot appear; the phone UI reports this.
- **Data Layer unavailable**: parsing and caching still work, but the watch cannot sync. This is
  the behavior seen on the Google APIs phone emulator image.
- **Notification removed**: cache remains; reply/mark-read disappear.
- **Emulator SystemUI bug**: Android 15 SystemUI can fail to inflate updated long
  `MessagingStyle` notifications and cancel them. The bridge handles removal correctly; this is
  not representative of a physical device.

# 1.6.0 - discovery without notifications and locked-phone behavior (device-testing build)

## Chats without a notification

OpenBubbles publishes two Android-visible chat surfaces. The phone bridge can now read both
through optional Shizuku access:

- Android dynamic/pinned/cached shortcuts for the installed OpenBubbles package.
- Android notification channels named `com.bluebubbles.new_messages.<chat GUID>` (including the
  `Notify Anyways:` variant).

Direct chats are accepted only for exact `iMessage;-;…`, `SMS;-;…`, or `RCS;-;…` GUIDs and groups
only for exact `service;+;…` GUIDs. A group is never inferred from a title. Deleted/redacted
channels, disabled/foreign shortcuts, and malformed entries are ignored.

Use either:

- Watch: **New message → Published chats → Find chats (Shizuku)**. Opening an existing group uses
  its exact stable GUID, never a same-named group.
- Phone bridge: **Chats without notifications → Find chats with Shizuku**, then sync the watch.

Discovery is also attempted automatically at most every ten minutes while the bridge is running,
off the main thread. Only public Android metadata for the current Android user is queried. The
private database, notification-history database, account, and encryption state are not read.
OpenBubbles is not modified and Shizuku is never required for anything else.

**This is not a complete chat directory.** It only exposes chats that Android has already been
told about. Android/OEM restrictions may block the query, and OpenBubbles may publish only a
subset. Unpublished chats still need a notification or a contact/address chosen on the watch.

Discovery placeholders cannot evict real conversations. The phone store keeps up to 40
message-bearing threads plus up to 60 placeholders, and importing a chat preserves its cached
messages, unread state, participants, and send ability. A temporary `new:<address>` thread is
folded into the published direct GUID when discovery finds it.

## What works while the phone is locked

Android does not allow an app to bypass a secure PIN/pattern/password/fingerprint keyguard.
This release makes the bridge honest and useful within that boundary:

- **Still works while locked:** saved/cached chats and messages, live notification replies, new
  incoming messages over the Data Layer, mark-read when a live notification provides it, and
  Android-published chat discovery.
- **Waits for unlock:** accessibility/composer automation, including older-message history and
  quiet-chat sends after the notification is gone. Sends typed on the watch are saved durably in
  the outbox with a 30-second backoff and retried automatically when the phone reports unlock.
- **Retryable lock results:** the phone marks lock failures as `retryable`, and the watch allows
  the same request to run again after unlock instead of permanently treating it as a duplicate.
- The phone uses `KeyguardManager.isDeviceLocked` (secure lock), not the weaker
  `isKeyguardLocked`. A swipe-only keyguard is not treated as secure. An unlocked-but-asleep
  screen gets a short wake lock so automation can run.
- The watch conversation list, chat screen, composer, and phone diagnostics card show the lock
  state and explain which actions are waiting.

## History on open

Opening a contact or group chat automatically requests readable older text once the phone is
online, relay is enabled, automation is connected, and the phone is not securely locked. If the
phone is locked, the request waits and is issued after unlock. **Load older messages** requests
another page; **Latest messages** returns to the newest cache. History reading never creates a
phone conversation, and ambiguous or missing anchors fail clearly rather than guessing.

Accessibility history is text-only, uses approximate times, and is bounded. It is not a complete
transcript and cannot invent group sender names.

## Protocol and compatibility

Protocol remains v3. New fields are optional and additive: `phoneLocked`/`phoneAwake` on sync
and pong, `retryable` on history and action results, and direct chats in `groups_result`.
Matching 1.6.0 phone/watch apps are recommended for the full lock/discovery experience. Older
payloads still decode with safe defaults.

Install **both 1.6.0 APKs** over 1.5.0 (versionCode 8). Signed APKs and SHA-256 checksums are
attached.

This is a device-testing prerelease. Actual S25 / Galaxy Watch6 Classic, Android 17 / One UI 9,
Shizuku channel/shortcut output, Data Layer delivery, real OpenBubbles selectors, and locked-phone
behavior still require hardware validation; no physical devices were connected for this release.

Local validation: **117 automated tests passed** (24 protocol, 58 phone, 35 watch), zero
failures/errors. Both apps' debug and R8 release builds succeeded. Lint completed with no errors
(41 phone / 38 watch warnings). Signed APKs share the 1.5.0 application IDs/certificate, report
versionName `1.6.0` / versionCode `8`, and contain no debug receivers.

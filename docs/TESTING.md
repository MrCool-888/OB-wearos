# Testing

This document covers the debug harness used during development and the checks that can be
reproduced locally. The harness is **not part of the user-facing app** and is never included in
release builds.

> **Do not install `testnotifier` on a daily-driver phone.** Its application ID is
> `com.openbubbles.messaging`, the same as the real OpenBubbles app, so it is intended only for a
> test emulator or a spare device. It also posts fake notifications and fake reply actions.

## Prerequisites

- Debug phone and watch APKs installed.
- `testnotifier` installed on the phone.
- Notification access granted to the phone bridge.
- Both devices reachable through adb.

Build everything:

```bash
./gradlew :protocol:test :phone:assembleDebug :wear:assembleDebug :testnotifier:assembleDebug
```

## Install the test harness

Phone:

```bash
adb install -r phone/build/outputs/apk/debug/phone-debug.apk
adb install -r testnotifier/build/outputs/apk/debug/testnotifier-debug.apk
adb shell pm grant com.openbubbles.messaging android.permission.POST_NOTIFICATIONS
adb shell cmd appops set com.openbubbles.messaging POST_NOTIFICATION allow
adb shell cmd appops set com.openbubbles.wear.bridge POST_NOTIFICATION allow
adb shell cmd notification allow_listener com.openbubbles.wear.bridge/.NotificationRelayService
```

A freshly installed app is in the stopped state, so launch it once before sending it broadcasts:

```bash
adb shell am start -n com.openbubbles.messaging/.FakeMainActivity
```

Watch:

```bash
adb -s <watch-serial> install -r wear/build/outputs/apk/debug/wear-debug.apk
```

## Fake OpenBubbles notifications

`testnotifier` publishes notifications that mirror the structure used by OpenBubbles:

- `MessagingStyle` with a conversation history.
- Dynamic shortcut IDs (`fake-maya`, `fake-family`).
- Incoming and outgoing messages with sender people.
- A group conversation with a title and multiple participants.
- A photo-only entry plus a caption.
- A mutable reply action with RemoteInput key `reply_text`.
- A mark-read action.
- Per-chat PendingIntent request codes to avoid collisions.

Post all fake conversations:

```bash
adb shell am broadcast -n com.openbubbles.messaging/.FakeOpenBubblesReceiver \
  -a com.openbubbles.messaging.DEBUG_POST --es chat all
```

Post one conversation:

```bash
adb shell am broadcast -n com.openbubbles.messaging/.FakeOpenBubblesReceiver \
  -a com.openbubbles.messaging.DEBUG_POST --es chat maya
```

Valid values are `maya`, `family`, and `all`. Clear all fake notifications and reset the in-memory
chat store:

```bash
adb shell am broadcast -n com.openbubbles.messaging/.FakeOpenBubblesReceiver \
  -a com.openbubbles.messaging.DEBUG_POST --es mode clear
```

## Inspecting the bridge

The debug-only `DebugRelayReceiver` exposes relay operations without a watch:

```bash
adb shell am broadcast -n com.openbubbles.wear.bridge/.DebugRelayReceiver \
  -a com.openbubbles.wear.bridge.DEBUG_RELAY --es op status
```

Example output:

```text
OBWearDebug: status access=true installed=true relay=true tracked=2 access=true
OBWearDebug: thread fake-maya cached=true messages=4 unread=true live=true reply=true markRead=true
OBWearDebug: thread fake-family cached=true messages=5 unread=false live=true reply=true markRead=true
```

The status operation reads the real `BridgeStore`, not a separate fake store. `live=true` means the
notification is still posted and its actions are available.

### Reply

```bash
adb shell am broadcast -n com.openbubbles.wear.bridge/.DebugRelayReceiver \
  -a com.openbubbles.wear.bridge.DEBUG_RELAY \
  --es op reply --es thread fake-maya --es text OnMyWay
```

Expected fake-app logs:

```text
OBWearDebug: reply fake-maya: ok
OBWearFake: Fake reply received for fake-maya: OnMyWay
OBWearFake: Posted fake notification for fake-maya with 5 messages
OBWearCenter: notification ... shortcut=fake-maya
```

A subsequent status should show the extra message and no duplicates. The fake receiver updates its
notification after the reply, exercising the same notification-refresh path as the real app.

For a multi-word reply, quote the entire remote command so the device shell does not split the
argument:

```bash
adb shell "am broadcast -n com.openbubbles.wear.bridge/.DebugRelayReceiver \
  -a com.openbubbles.wear.bridge.DEBUG_RELAY --es op reply \
  --es thread fake-maya --es text 'On my way'"
```

### Mark read

```bash
adb shell am broadcast -n com.openbubbles.wear.bridge/.DebugRelayReceiver \
  -a com.openbubbles.wear.bridge.DEBUG_RELAY \
  --es op mark_read --es thread fake-family
```

Expected behavior:

```text
OBWearFake: Fake mark-read received for fake-family
OBWearDebug: mark_read fake-family: ok
OBWearCenter: removed ... key=...9002...
```

The bridge marks the cached thread as history-only (`live=false`, `reply=false`,
`markRead=false`) and keeps its messages.

### Other operations

```bash
# Request a fresh sync.
adb shell am broadcast ... --es op sync

# Open OpenBubbles on the phone.
adb shell am broadcast ... --es op open_phone

# Drop the bridge cache.
adb shell am broadcast ... --es op clear_cache
```

The default thread for reply and mark-read is `fake-maya` if `--es thread` is omitted.

### Logs

```bash
adb logcat -c
adb logcat -s OBWearDebug OBWearCenter OBWearParser OBWearActions OBWearFake OBWearLink
```

## Watch UI without a phone

The debug watch build includes `DebugSeedReceiver`, which renders demo conversations directly in
`WatchRepository`:

```bash
adb -s <watch-serial> shell am broadcast -n com.openbubbles.wear/.DebugSeedReceiver \
  -a com.openbubbles.wear.DEBUG_SEED --es mode demo

adb -s <watch-serial> shell am broadcast -n com.openbubbles.wear/.DebugSeedReceiver \
  -a com.openbubbles.wear.DEBUG_SEED --es mode clear
```

Demo mode is useful for checking:

- Conversation list and unread indicators.
- Chat bubbles, group sender labels, photo placeholder, and timestamps.
- Composer and quick replies.
- Settings, cache counts, and the About limitations.
- Scrolling and swipe-to-dismiss navigation.

The demo receiver is debug-only and is not present in release APKs.

## Release smoke test

Release builds are minified and do not contain the debug receivers or seed commands.

```bash
./gradlew :phone:assembleRelease :wear:assembleRelease
adb install -r phone/build/outputs/apk/release/phone-release.apk
adb -s <watch-serial> install -r wear/build/outputs/apk/release/wear-release.apk
adb shell cmd notification allow_listener com.openbubbles.wear.bridge/.NotificationRelayService
adb shell am broadcast -n com.openbubbles.messaging/.FakeOpenBubblesReceiver \
  -a com.openbubbles.messaging.DEBUG_POST --es chat all
```

Checks performed during development:

- Release phone parses the fake `MessagingStyle` notifications.
- The setup UI reports 2 tracked chats and notification access.
- R8 keeps the protocol serializers and notification parsing code.
- Release watch launches to the empty/offline state without debug-only classes.
- `apksigner verify` confirms both release APKs are signed.

Release action tests need the debug receiver, so reply and mark-read are validated against debug
builds. The action code path is identical apart from R8 shrinking.

## Known emulator limitations

### Data Layer unavailable on the phone emulator

The Google APIs phone emulator image used during development returns:

```text
Wearable.API is not available on this device.
Connection failed with: statusCode=API_UNAVAILABLE
```

The watch can enumerate the phone peer, and the phone can start the Wear listener, but message/data
delivery does not complete. Parsing, caching, and notification actions can still be tested through
the debug receiver. Full end-to-end Data Layer behavior needs a physical phone/watch pair or a
phone image with working Wear support.

### SystemUI notification inflation crash

After a fake conversation is updated, Android 15 SystemUI may log:

```text
CentralSurfaces: couldn't inflate view for notification com.openbubbles.messaging/...
java.lang.IndexOutOfBoundsException: index=4 count=3
```

NotificationManager then removes the notification. The bridge handles this correctly: the cached
thread remains readable with `live=false` and actions become unavailable. This is an emulator
SystemUI issue, not a bridge crash. Validate reply/mark-read persistence on real hardware.

### Stopped app broadcasts

Android does not deliver implicit or explicit broadcasts to a newly installed app until it leaves
the stopped state. Launch `FakeMainActivity` once after installing `testnotifier`. Adding
`-f 0x20` to the `am broadcast` command also marks the intent as including stopped packages, but
launching the app is the simplest fix.

## Unit tests

The protocol module has JVM tests for:

- Message ID stability and content sensitivity.
- Serialization round-trips for sync, thread, update, reply, and action payloads.
- Base64url thread-path round-trips.
- Thread summaries and previews.

Run them with:

```bash
./gradlew :protocol:test
```

HTML output is generated at `protocol/build/reports/tests/test/index.html`.

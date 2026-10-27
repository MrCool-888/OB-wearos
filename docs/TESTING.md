# Testing

This document covers the debug harness used during development and the checks that can be
reproduced locally. The harness is **not part of the user-facing app** and is never included in
release builds.

> **Do not install `testnotifier` on a daily-driver phone.** Its application ID is
> `com.openbubbles.messaging`, the same as the real OpenBubbles app, so it is intended only for a
> test emulator or a spare device. It also posts fake notifications and fake reply actions.

## Prerequisites

- Debug phone and watch APKs installed.
- `testnotifier` installed on the phone for notification-path tests.
- Notification access granted to the phone bridge.
- Optional accessibility access granted to the bridge's automation service for send/history tests.
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

## Enabling automation

The optional accessibility service is required for new chats, sending in quiet chats, and loading
older messages. Live notification replies work without it.

On the phone, open **OpenBubbles Wear** → **Enable automation**, then turn on **OpenBubbles Wear
automation** in Android's accessibility settings. Alternatively:

```bash
adb shell settings put secure enabled_accessibility_services \
  com.openbubbles.wear.bridge/com.openbubbles.wear.bridge.OpenBubblesAutomationService
adb shell settings put secure accessibility_enabled 1
```

The command replaces the enabled-services list. If another accessibility service is already
enabled, include it as a comma-separated entry instead of overwriting it.

On Android 13 and newer, enabling accessibility for a sideloaded app through the UI may be blocked
until you open the bridge's App info and choose **Allow restricted settings**. The adb command above
does not need that toggle.

Verify:

```bash
adb shell settings get secure enabled_accessibility_services
adb shell settings get secure accessibility_enabled
```

The bridge's Setup screen should show the automation card as **On**. Reinstalling, force-stopping,
or clearing the bridge app can require toggling the service again.

Automation cannot be meaningfully tested with `testnotifier`: that app has no OpenBubbles chat UI,
only fake notifications. Use the real OpenBubbles app and a real or test account for the automation
checks below.

## Fake OpenBubbles notifications

`testnotifier` publishes notifications that mirror the structure used by OpenBubbles:

- `MessagingStyle` with a conversation history.
- Dynamic shortcut IDs (`fake-maya`, `fake-family`, `fake-media`).
- Incoming and outgoing messages with sender people.
- A group conversation with a title and multiple participants.
- A media conversation with a static PNG photo and an animated GIF. Both are served through a
  real `FileProvider` with the same authority OpenBubbles uses
  (`com.openbubbles.messaging.fileprovider`), so notification URI-grant handling is exercised.
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

Valid values are `maya`, `family`, `media`, and `all`. Clear all fake notifications and reset the
in-memory chat store:

```bash
adb shell am broadcast -n com.openbubbles.messaging/.FakeOpenBubblesReceiver \
  -a com.openbubbles.messaging.DEBUG_POST --es mode clear
```

The fake `Person` values deliberately have no URI, so fake one-to-one threads have no `address`.
The automation send path needs an address or a real OpenBubbles content intent.

## Inspecting the bridge

The debug-only `DebugRelayReceiver` exposes relay operations without a watch:

```bash
adb shell am broadcast -n com.openbubbles.wear.bridge/.DebugRelayReceiver \
  -a com.openbubbles.wear.bridge.DEBUG_RELAY --es op status
```

Example output:

```text
OBWearDebug: status access=true installed=true relay=true automation=false tracked=2 access=true
OBWearDebug: thread fake-maya cached=true messages=4 unread=true live=true reply=true markRead=true send=true address=null
OBWearDebug: thread fake-family cached=true messages=5 unread=false live=true reply=true markRead=true send=true address=null
```

The status operation reads the real `BridgeStore`, not a separate fake store. `live=true` means the
notification is still posted and its actions are available. `send=true` means either a live reply
action or automation is available; `automation` reports the service state separately.

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
`markRead=false`) and keeps its messages. With automation on, `send` remains true.

### Send

With a live notification, `send` uses the notification reply action:

```bash
adb shell am broadcast -n com.openbubbles.wear.bridge/.DebugRelayReceiver \
  -a com.openbubbles.wear.bridge.DEBUG_RELAY \
  --es op send --es thread fake-maya --es text SentFromAdb
```

To exercise the accessibility automation path, use the real OpenBubbles app and provide an address
with no thread:

```bash
adb shell "am broadcast -n com.openbubbles.wear.bridge/.DebugRelayReceiver \
  -a com.openbubbles.wear.bridge.DEBUG_RELAY --es op send \
  --es address '+15551234567' --es title 'Alice' --es text 'Sent through automation'"
```

The phone opens OpenBubbles, types into the composer, and presses Send. Expect:

```text
OBWearAutomation: Automation send finished for thread=new:+15551234567 address=+15551234567
OBWearDebug: send new:+15551234567: ok
```

A cached thread without a live notification can be sent to with:

```bash
adb shell am broadcast -n com.openbubbles.wear.bridge/.DebugRelayReceiver \
  -a com.openbubbles.wear.bridge.DEBUG_RELAY \
  --es op send --es thread <thread-id> --es text SentFromAdb
```

### History

With the real OpenBubbles app and automation on:

```bash
adb shell "am broadcast -n com.openbubbles.wear.bridge/.DebugRelayReceiver \
  -a com.openbubbles.wear.bridge.DEBUG_RELAY --es op history \
  --es thread <thread-id> --es title 'Alice' --es address '+15551234567' --es limit 40"
```

`title` and `address` are optional, but `thread` defaults to `fake-maya`, which cannot load history
without a real chat UI. Ideally provide the cached thread ID from `status`; the saved content intent
opens the exact chat. Provide a title and address as fallback for UI search and new-chat
navigation.

Expected:

```text
OBWearAutomation: History scrape for <thread-id>: 40 older messages, hasMore=true
OBWearDebug: history <thread-id> messages=40 hasMore=true: ok
```

A second request should return only messages older than the new oldest cached message. The watch
shows **No older messages** when `hasMore` is false.

### Media

Post the media conversation:

```bash
adb shell am broadcast -n com.openbubbles.messaging/.FakeOpenBubblesReceiver \
  -a com.openbubbles.messaging.DEBUG_POST --es chat media
```

The bridge should copy both attachments while the notification URI grant is active. Confirm the
cache count and list media IDs:

```bash
adb shell am broadcast -n com.openbubbles.wear.bridge/.DebugRelayReceiver \
  -a com.openbubbles.wear.bridge.DEBUG_RELAY --es op status
adb shell am broadcast -n com.openbubbles.wear.bridge/.DebugRelayReceiver \
  -a com.openbubbles.wear.bridge.DEBUG_RELAY --es op media
```

Expected phone logs:

```text
OBWearFake: Posted fake notification for fake-media with 5 messages
OBWearDebug: thread fake-media cached=true messages=3 media=2 ...
OBWearDebug: media ids=media-...,media-...
```

Publish one cached item to the watch (the debug command only exercises the Data Layer path; the
watch must be running and reachable):

```bash
adb shell am broadcast -n com.openbubbles.wear.bridge/.DebugRelayReceiver \
  -a com.openbubbles.wear.bridge.DEBUG_RELAY --es op media --es id <media-id>
```

Expected:

```text
OBWearDebug: media <media-id> image/png 480x320 18798B: ok
```

On the watch, open **Riley Park** and tap **Load photo**, then **Load GIF**. The static image and the
animated GIF should appear as thumbnails; tapping a thumbnail opens the full-screen viewer. The GIF
should animate in both places. Pinch should zoom and double-tap should reset.

Media requests return an error if relay is paused, the phone is offline, or the cache was cleared.
Run `--es op clear_cache`, then try an attachment already visible on the watch to confirm the error.

### Other operations

```bash
# Request a fresh sync.
adb shell am broadcast -n com.openbubbles.wear.bridge/.DebugRelayReceiver \
  -a com.openbubbles.wear.bridge.DEBUG_RELAY --es op sync

# Open OpenBubbles on the phone.
adb shell am broadcast -n com.openbubbles.wear.bridge/.DebugRelayReceiver \
  -a com.openbubbles.wear.bridge.DEBUG_RELAY --es op open_phone --es thread fake-maya

# Drop the bridge cache and saved content intents.
adb shell am broadcast -n com.openbubbles.wear.bridge/.DebugRelayReceiver \
  -a com.openbubbles.wear.bridge.DEBUG_RELAY --es op clear_cache
```

The default thread for reply, mark-read, and history is `fake-maya` if `--es thread` is omitted.

### Logs

```bash
adb logcat -c
adb logcat -s OBWearDebug OBWearCenter OBWearParser OBWearActions \
  OBWearAutomation OBWearIntents OBWearStore OBWearFake OBWearLink OBWearMedia
```

## Inspecting OpenBubbles' accessibility tree

The automation selectors are the most device-sensitive part of the project. To inspect the tree,
open a real chat in OpenBubbles, then run:

```bash
adb shell uiautomator dump /sdcard/openbubbles.xml
adb pull /sdcard/openbubbles.xml
```

Look for:

- The message `EditText`/composer near the bottom and its hint/content description.
- The Send control's text, content description, view ID, and bounds.
- The main scrollable conversation container.
- How outgoing/incoming bubbles differ in bounds (alignment).
- Whether the chat header, search field, and conversation rows are exposed as text/clickable.

`uiautomator` sees a slightly different tree than an `AccessibilityService`, but it is usually
enough to identify candidate labels. Adjust the selectors in
`phone/src/main/java/com/openbubbles/wear/bridge/OpenBubblesAutomation.kt`, then rebuild the phone
APK.

## Watch UI without a phone

The debug watch build includes `DebugSeedReceiver`, which renders demo conversations directly in
`WatchRepository`:

```bash
adb -s <watch-serial> shell am broadcast -n com.openbubbles.wear/.DebugSeedReceiver \
  -a com.openbubbles.wear.DEBUG_SEED --es mode demo

adb -s <watch-serial> shell am broadcast -n com.openbubbles.wear/.DebugSeedReceiver \
  -a com.openbubbles.wear.DEBUG_SEED --es mode media

adb -s <watch-serial> shell am broadcast -n com.openbubbles.wear/.DebugSeedReceiver \
  -a com.openbubbles.wear.DEBUG_SEED --es mode clear
```

Demo mode is useful for checking:

- Conversation list, unread indicators, and the **New message** action.
- Chat bubbles, group sender labels, and timestamps.
- Composer and quick replies.
- Settings, cache counts, automation state, and the About limitations.
- Scrolling and swipe-to-dismiss navigation.

Media mode writes a generated 320x200 PNG and a small animated GIF into the watch app's private
media cache, then seeds a **Riley Park** thread. It exercises thumbnails, the full-screen viewer,
GIF animation, pinch zoom, and double-tap reset without a phone or Data Layer. Candidate checks:

- Both attachments render as thumbnails, not **Load photo** cards.
- The GIF animates in the bubble and full-screen viewer.
- Swipe-to-dismiss and the close button return to the chat.
- The Settings cache count and media-trim logic still behave after restarting the app.

The demo receiver is debug-only and is not present in release APKs. Seeded threads do not provide a
real notification, so live reply and automation actions are still rejected unless the phone reports
the corresponding capability.

## Real-device automation checklist

Automation must be validated on a physical phone with the official OpenBubbles app. Suggested
sequence:

1. Enable automation and confirm the bridge card and watch Settings report it as on. Keep the phone
   unlocked and openable while automation runs.
2. Open a chat from a notification, dismiss the notification, then send from the watch. Confirm the
   message appears in OpenBubbles and on the other device, with no duplicate on the watch.
3. Start a brand-new chat from **New message** using a phone number, then send. Confirm OpenBubbles
   opens a composer and that the temporary `new:<address>` thread later merges into the real
   notification thread.
4. Open a quiet chat and tap **Load older messages** twice. Confirm older bubbles prepend, the list
   stays scrolled near the newest message, and the second request either returns older messages or
   shows **No older messages**.
5. Check group chats, emails, international numbers, reaction-heavy threads, photo bubbles,
   animated GIFs, and very long messages.
6. Send a photo and a GIF from the other device, then tap **Load photo** / **Load GIF** on the
   watch. Confirm the thumbnail, full-screen viewer, GIF animation, and zoom. Turn on OpenBubbles'
   **Hide text previews** and confirm new media is reported as unavailable rather than crashing.
7. Rotate/flip the phone or change display size and re-check the Send-button and composer detection.
8. Revoke accessibility and confirm the app degrades to notification-only replies with a clear
   error for new/quiet sends and history.

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
- The setup UI reports 3 tracked chats, 2 cached photos, and notification access.
- R8 keeps the protocol serializers and notification parsing code.
- R8 keeps the manifest-declared automation service.
- Release watch launches to the empty/offline state without debug-only classes.
- `apksigner verify` confirms both release APKs are signed.

Check that the release build kept the automation service:

```bash
adb shell dumpsys package com.openbubbles.wear.bridge
adb shell settings get secure enabled_accessibility_services
```

Look for `OpenBubblesAutomationService` in the package dump. It must keep its original class name
because Android identifies accessibility services by component name.

Release action tests for reply and mark-read need the debug receiver, so those are validated
against debug builds. The notification action code path is identical apart from R8 shrinking.
Automation should be smoke-tested from the watch on a release build with the real OpenBubbles app.

## Known emulator limitations

### Data Layer unavailable on the phone emulator

The Google APIs phone emulator image used during development returns:

```text
Wearable.API is not available on this device.
Connection failed with: statusCode=API_UNAVAILABLE
```

The watch can enumerate the phone peer, and the phone can start the Wear listener, but message/data
delivery does not complete. Parsing, caching, and notification actions can still be tested through
the debug receiver. Full end-to-end Data Layer behavior, including media asset transfer, needs a
physical phone/watch pair or a phone image with working Wear support. The watch-only `media` seed
mode covers image decoding, GIF animation, and the viewer without the Data Layer.

### Automation needs a real OpenBubbles UI

`testnotifier` posts notifications but has no chat screens. The accessibility automation therefore
cannot open a chat, type, send, or scrape history on an emulator using only the harness. Real
OpenBubbles, a signed-in account, and a real or test conversation are required.

### SystemUI notification inflation crash

After a fake conversation is updated, Android 15 SystemUI may log:

```text
CentralSurfaces: couldn't inflate view for notification com.openbubbles.messaging/...
java.lang.IndexOutOfBoundsException: index=4 count=3
```

NotificationManager then removes the notification. The bridge handles this correctly: the cached
thread remains readable with `live=false` and notification actions become unavailable. This is an
emulator SystemUI issue, not a bridge crash. Validate reply/mark-read persistence on real hardware.

### Stopped app broadcasts

Android does not deliver implicit or explicit broadcasts to a newly installed app until it leaves
the stopped state. Launch `FakeMainActivity` once after installing `testnotifier`. Adding
`-f 0x20` to the `am broadcast` command also marks the intent as including stopped packages, but
launching the app is the simplest fix.

## Unit tests

The protocol module has JVM tests for:

- Message ID stability and content sensitivity.
- Serialization round-trips for sync, thread, update, reply, send, history, media, and action
  payloads.
- Base64url thread-path round-trips and media-path validation.
- Media ID stability, MIME normalization, image MIME detection, and media-field round-trips.
- Thread summaries, previews, and automation/address fields.
- Address normalization and temporary thread IDs.
- Transport trimming, including keeping the newest messages.

Run them with:

```bash
./gradlew :protocol:test
```

HTML output is generated at `protocol/build/reports/tests/test/index.html`.

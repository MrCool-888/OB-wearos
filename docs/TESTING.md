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
- Both devices reachable through adb.

Build everything:

```bash
./gradlew :protocol:test :phone:assembleDebug :wear:assembleDebug :testnotifier:assembleDebug
```

There is no accessibility service to enable. These harness instructions test notification replies.
The separate [Morphe preview checklist](MORPHE.md#verification-and-remaining-checks) covers the direct
backend path, which requires release-signed companions and must not be tested by replacing a
daily-driver OpenBubbles installation. `PatchBridgeIntegrationTest` uses a fake bound Messenger
service; it does not require or install the real OpenBubbles APK.

## Install the test harness

Phone:

```bash
adb install -r phone/build/outputs/apk/debug/phone-debug.apk
adb install -r testnotifier/build/outputs/apk/debug/testnotifier-debug.apk
adb shell pm grant com.openbubbles.messaging android.permission.POST_NOTIFICATIONS
adb shell cmd appops set com.openbubbles.messaging POST_NOTIFICATION allow
adb shell cmd appops set com.openbubbles.wear POST_NOTIFICATION allow
adb shell cmd notification allow_listener com.openbubbles.wear/com.openbubbles.wear.bridge.NotificationRelayService
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
The fake app still provides reply actions, so its chats can exercise the whole send path.

## Inspecting the bridge

The debug-only `DebugRelayReceiver` exposes relay operations without a watch:

```bash
adb shell am broadcast -n com.openbubbles.wear/com.openbubbles.wear.bridge.DebugRelayReceiver \
  -a com.openbubbles.wear.bridge.DEBUG_RELAY --es op status
```

Example output:

```text
OBWearDebug: status access=true installed=true relay=true locked=false tracked=2 media=2 access=true
OBWearDebug: thread fake-maya cached=true messages=4 unread=true live=true reply=true markRead=true send=true address=null
OBWearDebug: thread fake-family cached=true messages=5 unread=false live=true reply=true markRead=true send=true address=null
```

The status operation reads the real `BridgeStore`, not a separate fake store. `live=true` means the
notification is still posted and its actions are available. `send=true` mirrors `reply=true`; there
is no separate automation send path.

### Reply

```bash
adb shell am broadcast -n com.openbubbles.wear/com.openbubbles.wear.bridge.DebugRelayReceiver \
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
adb shell "am broadcast -n com.openbubbles.wear/com.openbubbles.wear.bridge.DebugRelayReceiver \
  -a com.openbubbles.wear.bridge.DEBUG_RELAY --es op reply \
  --es thread fake-maya --es text 'On my way'"
```

### Mark read

```bash
adb shell am broadcast -n com.openbubbles.wear/com.openbubbles.wear.bridge.DebugRelayReceiver \
  -a com.openbubbles.wear.bridge.DEBUG_RELAY \
  --es op mark_read --es thread fake-family
```

Expected behavior:

```text
OBWearFake: Fake mark-read received for fake-family
OBWearDebug: mark_read fake-family: ok
OBWearCenter: removed ... key=...9002...
```

The bridge keeps the cached messages and saved reply action but reports `live=false`,
`markRead=false`. A mark-read action needs a live notification, so it is unavailable after
OpenBubbles cancels one.

### Send

Send requires a cached thread ID. With a live or saved notification reply action:

```bash
adb shell am broadcast -n com.openbubbles.wear/com.openbubbles.wear.bridge.DebugRelayReceiver \
  -a com.openbubbles.wear.bridge.DEBUG_RELAY \
  --es op send --es thread fake-maya --es text SentFromAdb
```

To check the rejection path, use a thread that never posted a notification (or clear the cache
first):

```bash
adb shell am broadcast -n com.openbubbles.wear/com.openbubbles.wear.bridge.DebugRelayReceiver \
  -a com.openbubbles.wear.bridge.DEBUG_RELAY \
  --es op send --es thread iMessage\;-\;nobody@example.com --es text Hello
```

Expect an error containing `no reply action`, not a queued message or an OpenBubbles launch.

### Media

Post the media conversation:

```bash
adb shell am broadcast -n com.openbubbles.messaging/.FakeOpenBubblesReceiver \
  -a com.openbubbles.messaging.DEBUG_POST --es chat media
```

The bridge should copy both attachments while the notification URI grant is active. Confirm the
cache count and list media IDs:

```bash
adb shell am broadcast -n com.openbubbles.wear/com.openbubbles.wear.bridge.DebugRelayReceiver \
  -a com.openbubbles.wear.bridge.DEBUG_RELAY --es op status
adb shell am broadcast -n com.openbubbles.wear/com.openbubbles.wear.bridge.DebugRelayReceiver \
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
adb shell am broadcast -n com.openbubbles.wear/com.openbubbles.wear.bridge.DebugRelayReceiver \
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
adb shell am broadcast -n com.openbubbles.wear/com.openbubbles.wear.bridge.DebugRelayReceiver \
  -a com.openbubbles.wear.bridge.DEBUG_RELAY --es op sync

# Open OpenBubbles on the phone.
adb shell am broadcast -n com.openbubbles.wear/com.openbubbles.wear.bridge.DebugRelayReceiver \
  -a com.openbubbles.wear.bridge.DEBUG_RELAY --es op open_phone --es thread fake-maya

# Drop the bridge cache and saved content intents.
adb shell am broadcast -n com.openbubbles.wear/com.openbubbles.wear.bridge.DebugRelayReceiver \
  -a com.openbubbles.wear.bridge.DEBUG_RELAY --es op clear_cache
```

The default thread for reply and mark-read is `fake-maya` if `--es thread` is omitted.

### Logs

```bash
adb logcat -c
adb logcat -s OBWearDebug OBWearCenter OBWearParser OBWearActions \
  OBWearIntents OBWearStore OBWearFake OBWearLink OBWearMedia
```

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

- Conversation list, unread indicators, and the **Find a chat** action.
- Chat bubbles, group sender labels, and timestamps.
- Composer and quick replies.
- Settings, cache counts, and the About limitations.
- Scrolling and swipe-to-dismiss navigation.

Media mode writes a generated 320x200 PNG and a small animated GIF into the watch app's private
media cache, then seeds a **Riley Park** thread. It exercises thumbnails, the full-screen viewer,
GIF animation, pinch zoom, and double-tap reset without a phone or Data Layer. Candidate checks:

- Both attachments render as thumbnails, not **Load photo** cards.
- The GIF animates in the bubble and full-screen viewer.
- Swipe-to-dismiss and the close button return to the chat.
- The Settings cache count and media-trim logic still behave after restarting the app.

The demo receiver is debug-only and is not present in release APKs. Seeded threads do not provide a
real notification, so reply and mark-read actions are rejected unless the phone reports the
corresponding capability.

## 2.0.0 locked-send and accessibility-removal checks

These historical unit tests mocked reply capabilities; they did not catch Android rejecting token
serialization. Use the 2.0.2 runtime checks below for retained-action behavior.

Local validation: see the 2.0.0 release note for the exact test counts and build results. Automated
coverage includes:

- Watch rejection of a chat with no reply action, even when `canSend` is set.
- Immediate dispatch of a reply while the phone reports a secure lock.
- Saved-action send while locked with no live notification.
- Failed-send cleanup: the local bubble becomes `failed` and the outbox is emptied.
- `findChatByAddress` opens an existing chat and reports an unknown contact without creating one.
- Phone rejection of a missing thread ID and of a thread with no live or saved reply action.
- Protocol round-trips without history/automation fields and with a required `SendRequest.threadId`.

Required real-device checks (not performed on this build machine):

1. Update both devices to 2.0.0. Confirm Android's accessibility settings no longer list an
   OpenBubbles Wear automation service and the bridge UI has no automation card.
2. Open a chat notification from OpenBubbles so the bridge captures its reply action, then dismiss
   the notification.
3. Lock the phone with a secure PIN/pattern. From the watch, send in that quiet chat. It must appear
   in OpenBubbles without unlocking and without OpenBubbles opening its composer. Confirm exactly
   one message is sent.
4. Repeat while unlocked. Confirm the saved reply action is used, not a UI takeover.
5. Reboot the phone, relock it, and repeat. If OpenBubbles cancelled the saved token, confirm the
   watch shows a clear no-reply-action error and keeps or fails the message honestly; there is no
   automation fallback.
6. Send twice in a row in the same quiet chat. Confirm a single-use action does not duplicate the
   first message; the second must either send once or show a clear error.
7. Confirm a chat that has never posted a notification is rejected with a no-reply-action message
   and cannot create a new conversation from Find a chat.
8. Confirm Settings and the chat screen no longer mention automation, history loading, or
   **New message**.
9. On Samsung Android 17 / One UI 9, verify the keyguard lock state, saved-token lifetime, Data
   Layer delivery, and reply acceptance. No physical devices were connected for this build.

## 2.0.1 discovery and contact checks

Local validation: see the 2.0.1 release note for the exact test counts and build results. Automated
coverage includes:

- OpenBubbles 2.x shortcut IDs (`chat_<GUID>`, `chat_addr:<address>`, `chat_contact:<id>`) and
  channels (`conv_chat_<key>`) import with their address, title, and group status.
- Legacy bare GUID shortcuts and `com.bluebubbles.new_messages.<GUID>` channels still import.
- Contact lookup prefers the latest phone-sync summary over a cached thread with an older ID.
- Discovery accepts `chat_` IDs instead of rejecting everything that is not a bare chat GUID.

Required real-device checks (not performed on this build machine):

1. On the phone, confirm OpenBubbles is version 2.0.x and at least a few chats have posted
   notifications or have visible conversation channels.
2. Inspect the two sources directly (the phone must be unlocked for `adb`):
   ```bash
   adb shell cmd shortcut get-shortcuts --user 0 'com.openbubbles.messaging' \
     | grep -E "ShortcutInfo|packageName=|shortLabel="
   adb shell dumpsys notification --noredact --package 'com.openbubbles.messaging' \
     | grep -E "NotificationChannel\{mId='(conv_|com.bluebubbles.new_messages)" | head -20
   ```
   The shortcut IDs should start with `chat_`, and conversation channels with `conv_chat_`.
3. Start Shizuku in wireless-debugging (shell) mode, grant the bridge access, then tap
   **Find chats (Shizuku)** on the watch or in the phone bridge. The note must report at least one
   imported chat; "No published chats found" means both sources were unreadable or empty.
4. Open every newly listed group and one-to-one chat. None may answer **Conversation not found**;
   a chat with no notification shows as read-only and only sends after OpenBubbles posts a reply
   action.
5. From the watch, **Choose a contact** for a chat that was cached under an older bridge or
   OpenBubbles version. It must open the current thread, not the old ID and not "not found".
6. Import enough placeholders to exceed the phone store's 60-chat placeholder budget. Only the
   retained chats may appear on the watch, and all of them must open.

## 2.0.2 retained replies on a securely locked emulator

Use only a disposable emulator/spare test device with `testnotifier`, never a daily-driver phone.
Build/install the debug harness above, grant the bridge notification access, and post `maya` once.

Set a test PIN, enable the lock screen, and turn the screen off. **Check that bridge status reports
`locked=true` before continuing**; screen-off alone does not prove secure lock. Then:

```bash
adb -s <test-phone> shell am broadcast \
  -n com.openbubbles.wear/com.openbubbles.wear.bridge.DebugRelayReceiver \
  -a com.openbubbles.wear.bridge.DEBUG_RELAY --es op mark_read --es thread fake-maya
adb -s <test-phone> shell am broadcast \
  -n com.openbubbles.wear/com.openbubbles.wear.bridge.DebugRelayReceiver \
  -a com.openbubbles.wear.bridge.DEBUG_RELAY --es op status
```

Require `locked=true` and `thread fake-maya ... live=false reply=true`. This confirms the live
notification is gone but the bridge retains its actual reply token. Without unlocking:

```bash
adb -s <test-phone> shell am broadcast \
  -n com.openbubbles.wear/com.openbubbles.wear.bridge.DebugRelayReceiver \
  -a com.openbubbles.wear.bridge.DEBUG_RELAY --es op send --es thread fake-maya --es text LockedQuietReply
adb -s <test-phone> logcat -d -s OBWearDebug:I OBWearFake:I
```

Require both `send fake-maya: ok` and exactly one
`Fake reply received for fake-maya: LockedQuietReply`; checking only the bridge's dispatch result
does not prove receiver delivery. Verify status still reports `locked=true`. The fake receiver
reposts a notification after replies, so remove it again before repeating with another unique text.

Native Android 15 smoke test passed for 2.0.2 with the PIN-locked screen off. Automated phone tests
also cover two sends without a repost, canceling live/retained actions, replacement tokens,
and discovery preserving the current action key. Existing watch tests cover immediate queuing
without an unlock deadline; the outbox is a transport journal, not an unlock queue.

Process-death check: remove the notification, stop/restart the bridge, and try sending again.
Expect an explicit missing-action error, not successful sending or retry-on-unlock. A new active
notification must restore capability. Tokens cannot be serialized to disk or restored after reboot.

Still required on the S25 / Watch6: install both 2.0.2 APKs, capture a real OpenBubbles notification,
dismiss it, securely lock the phone, send twice from the watch, and confirm each message reaches
the recipient exactly once without unlocking. Repeat after mark-read and after Shizuku discovery.
No physical devices were connected for this build; fake receiver success does not establish real
OpenBubbles backend delivery or Samsung Data Layer behavior.

## Release smoke test

Release builds are minified and do not contain the debug receivers or seed commands.

```bash
./gradlew :phone:assembleRelease :wear:assembleRelease
adb install -r phone/build/outputs/apk/release/phone-release.apk
adb -s <watch-serial> install -r wear/build/outputs/apk/release/wear-release.apk
adb shell cmd notification allow_listener com.openbubbles.wear/com.openbubbles.wear.bridge.NotificationRelayService
adb shell am broadcast -n com.openbubbles.messaging/.FakeOpenBubblesReceiver \
  -a com.openbubbles.messaging.DEBUG_POST --es chat all
```

Checks performed during development:

- Release phone parses the fake `MessagingStyle` notifications.
- The setup UI reports tracked chats, cached photos, and notification access.
- R8 keeps the protocol serializers and notification parsing code.
- Release watch launches to the empty/offline state without debug-only classes.
- `apksigner verify` confirms both release APKs are signed.
- The release manifest has no `BIND_ACCESSIBILITY_SERVICE` service and no automation strings.

Release action tests for reply and mark-read need the debug receiver, so those are validated
against debug builds. The notification action code path is identical apart from R8 shrinking.

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
- Serialization round-trips for sync, thread, update, reply, send, media, contacts, groups, and
  action payloads.
- Base64url thread-path round-trips and media-path validation.
- Media ID stability, MIME normalization, image MIME detection, and media-field round-trips.
- Thread summaries and address/reply capability fields.
- Address normalization.
- Transport trimming, including keeping the newest messages.

Run them with:

```bash
./gradlew :protocol:test
./gradlew :phone:testDebugUnitTest :wear:testDebugUnitTest
./gradlew :phone:lintDebug :wear:lintDebug
```

HTML reports are generated under each module's `build/reports/tests/`; Android lint reports are
under `phone/build/reports/` and `wear/build/reports/`.

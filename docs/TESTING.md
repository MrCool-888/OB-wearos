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

## Enabling automation

The optional accessibility service is required for new chats, sending in quiet chats, and loading
older messages. Live notification replies work without it.

On the phone, open **OpenBubbles Wear** → **Enable automation**, then turn on **OpenBubbles Wear
automation** in Android's accessibility settings. Alternatively:

```bash
adb shell settings put secure enabled_accessibility_services \
  com.openbubbles.wear/com.openbubbles.wear.bridge.OpenBubblesAutomationService
adb shell settings put secure accessibility_enabled 1
```

The command replaces the enabled-services list. If another accessibility service is already
enabled, include it as a colon-separated entry instead of overwriting it. Optional Shizuku setup
preserves the list for you.

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
adb shell am broadcast -n com.openbubbles.wear/com.openbubbles.wear.bridge.DebugRelayReceiver \
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

The bridge marks the cached thread as history-only (`live=false`, `reply=false`,
`markRead=false`) and keeps its messages. With automation on, `send` remains true.

### Send

With a live notification, `send` uses the notification reply action:

```bash
adb shell am broadcast -n com.openbubbles.wear/com.openbubbles.wear.bridge.DebugRelayReceiver \
  -a com.openbubbles.wear.bridge.DEBUG_RELAY \
  --es op send --es thread fake-maya --es text SentFromAdb
```

To exercise the accessibility automation path, use the real OpenBubbles app and provide an address
with no thread:

```bash
adb shell "am broadcast -n com.openbubbles.wear/com.openbubbles.wear.bridge.DebugRelayReceiver \
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
adb shell am broadcast -n com.openbubbles.wear/com.openbubbles.wear.bridge.DebugRelayReceiver \
  -a com.openbubbles.wear.bridge.DEBUG_RELAY \
  --es op send --es thread <thread-id> --es text SentFromAdb
```

### History

With the real OpenBubbles app and automation on:

```bash
adb shell "am broadcast -n com.openbubbles.wear/com.openbubbles.wear.bridge.DebugRelayReceiver \
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
adb shell cmd notification allow_listener com.openbubbles.wear/com.openbubbles.wear.bridge.NotificationRelayService
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
adb shell dumpsys package com.openbubbles.wear
adb shell settings get secure enabled_accessibility_services
```

Look for `OpenBubblesAutomationService` in the package dump. It must keep its original class name
because Android identifies accessibility services by component name.

Release action tests for reply and mark-read need the debug receiver, so those are validated
against debug builds. The notification action code path is identical apart from R8 shrinking.
Automation should be smoke-tested from the watch on a release build with the real OpenBubbles app.

## 1.7.0 locked send checks

Local validation: **120 tests passed** (24 protocol, 61 phone, 35 watch), zero failures/errors.
Both apps' debug and R8 release builds succeeded. Lint completed with no errors (45 phone / 38
watch warnings). Signed APKs report versionName `1.7.0`, versionCode `9`, and contain no debug
receivers. No ADB devices were connected.

Automated coverage adds saved reply-action parcel round-trip, bounded reply storage, free-form
action filtering, and immediate dispatch of a locked quiet-chat send. The previous locked-send
short-circuit test now asserts that no unlock backoff is applied.

Required real-device checks (not performed on this build machine):

1. Update both devices to 1.7.0. Send a notification from OpenBubbles for a chat so the bridge
   captures its reply action, then dismiss the notification on the phone.
2. Relock the phone with a secure PIN/pattern. From the watch, send in that quiet chat. It must
   appear on the phone's conversation without unlocking and without OpenBubbles opening to its
   composer. Confirm exactly one message is sent.
3. Repeat while the phone is unlocked but the notification is still dismissed. Confirm the saved
   reply action is preferred over automation (no OpenBubbles UI takeover).
4. Reboot the phone, relock it, and repeat. If OpenBubbles cancelled the saved token, confirm the
   watch shows a retryable/queued result rather than losing the message, and that it sends after
   unlock.
5. Send twice in a row from the same quiet chat. Confirm a single-use action does not duplicate the
   first message; the second must either send once or queue with a clear result.
6. Confirm a chat that has never posted a notification still shows that it needs unlock/automation
   and does not claim a reply action is available.
7. While locked, tap **Load older messages**. It must still explain that history needs unlock; a
   queued text message must not block the history retry.

## 1.6.0 discovery and locked-phone checks

Local validation: **117 tests passed** (24 protocol, 58 phone, 35 watch), zero failures/errors.
Both apps' debug and R8 release builds succeeded. Lint completed with no errors (41 phone / 38
watch warnings). Signed APKs share the 1.5.0 application IDs/certificate, report versionName
`1.6.0`, versionCode `8`, and contain no debug receivers. No ADB devices were connected.

Automated coverage adds shortcut and channel parsing (including `Notify Anyways:`, deleted and
redacted channels, disabled/foreign shortcuts), direct/group GUID classification, exact
package/current-user query scoping, import preservation, two-tier placeholder trimming, legacy
`new:<address>` folding, direct-chat discovery merge by address, retryable lock results, unlock
backoff reset, locked send short-circuit, locked live replies, locked history rejection, protocol
lock/retry fields, and older-payload decoding.

Required real-device checks (not performed on this build machine):

1. Update both devices to 1.6.0. Open OpenBubbles on the phone so it publishes recent chats.
   Start Shizuku through wireless debugging (not root) and grant the bridge access.
2. Run **Chats without notifications → Find chats with Shizuku** on the phone and **Published
   chats → Find chats (Shizuku)** on the watch. Compare the result with
   `adb shell dumpsys notification --noredact --package com.openbubbles.messaging` and
   `adb shell cmd shortcut get-shortcuts --user 0 --flags 19 com.openbubbles.messaging`.
   Confirm groups use `;+;` GUIDs, direct chats use `;-;` GUIDs, titles are not inverted, and a
   deleted/redacted channel is skipped. Repeat after reboot and with Shizuku stopped.
3. Open a discovered group that has never notified the bridge. Confirm it opens that exact group,
   not a same-named group, and that importing it did not remove cached messages from any chat.
   Discover 50+ chats and confirm message-bearing conversations are retained while placeholders
   stay capped.
4. Relock the phone with a secure PIN/pattern. While locked, confirm: cached chats open, live
   notification replies send, incoming messages sync, and discovery still works. Confirm opening a
   chat does not silently claim to load history.
5. While still locked, type a quiet-chat send (notification dismissed) on the watch. It must stay
   queued with a lock explanation and must not reach the phone. Unlock the phone and confirm the
   queued message sends once, automatically, without duplicates.
6. While locked, tap **Load older messages** and open a chat with no cache. Expect the lock
   explanation, not a spinner or a fabricated history. Unlock and confirm history-on-open now
   loads, including when the watch cache was empty.
7. Check the watch status card and the phone **Status and diagnostics** card for lock/awake state.
   Verify a swipe-only keyguard is not reported as securely locked.
8. On Samsung Android 17 / One UI 9, verify that both `dumpsys notification` and `cmd shortcut`
   output match the tested parsers. OEM formatting changes may require a follow-up.

## 1.5.0 published-group and history checks

Local validation: **100 tests passed** (23 protocol, 49 phone, 28 watch), zero failures/errors.
Both apps' debug and R8 release builds succeeded. Lint completed with no errors (40 phone / 38
watch warnings). Signed APKs have matching application IDs/certificates, the same signing key as
1.4.0, versionName `1.5.0`, versionCode `7`, and no debug receivers. No ADB devices were connected.

Automated coverage adds AOSP-format shortcut parsing (including the space before readable flags),
disabled/foreign/direct/redacted filtering, package/current-user query scoping, import preservation,
exact GUID launch extras and exact header matching. History tests cover empty caches, missing or
ambiguous oldest anchors, later-only matches, repeated text/direction, full-cache older windows,
multiple pages, snapshots arriving during older viewing, and stale result/failure/timeout IDs.
Protocol tests cover optional anchors and additive group paths/models.

Required real-device checks (not performed on this build machine):

1. Update both devices to 1.5.0. Start Shizuku through wireless debugging and approve the phone
   bridge. Open OpenBubbles so it can publish recent named chats. On the watch use
   **Groups → Find groups (Shizuku)**, or import via the phone's discovery button then sync.
2. Find a published group that has not notified the bridge. Confirm its existing GUID/title
   appears, and selection opens that exact group on the phone, not a same-named group or a newly
   composed recipient list. Discovery is partial: check the empty-result explanation too.
3. Stop/revoke Shizuku and retry: expect a clear setup hint; existing notification groups remain.
   Try the package-scoped query on Samsung Android 17/One UI 9; support is not assumed.
4. Enable accessibility and unlock the phone. Choose an existing contact with no watch messages;
   selection should open Chat, automatically read text history, and not send/create a new phone
   conversation. Repeat for a published group and a notification group. Tap Reply explicitly to send.
5. Open a nonexistent contact conversation: expect an existing-chat-not-found error, not a new
   phone chat created by history navigation. Test two groups/contacts with the same displayed name;
   visible duplicate matches should be rejected; stable group GUID navigation must not guess.
6. Load older pages with 220 messages already cached. The older page must remain visible after
   a fresh phone thread snapshot; load another page, then tap **Latest messages** to return.
   Reopening after process death restores the newest cache, not the memory-only older window.
7. Lock the phone and open a watch chat: cached content remains, but automation asks you to unlock.
   Unlock and retry. Disable accessibility: cached content/live notification replies still work.
8. Try an empty/inaccessible accessibility tree and repeated/truncated anchor texts. Expect an
   error, not false exhaustion or newer messages labeled older. UI times remain approximate;
   accessibility history is text-only and does not invent group sender names.
9. Delay one history request past timeout, issue another, then deliver the first response/failure.
   The newer request must remain pending. Verify Data Layer and UI behavior with actual devices.

## 1.4.0 contact-picker and group checks

Local validation: **73 tests passed** (20 protocol, 33 phone, 20 watch), with zero failures/errors.
Both apps' debug and R8 release builds succeeded; lint completed with warnings and no errors.
Signed release APKs have matching certificates/application IDs, versionName `1.4.0`, versionCode
`6`, and no debug receivers. The phone release declares optional runtime Contacts permission.

New tests cover contact serialization/older metadata, optional permission denial and revocation,
parameterized search, multi-address choices, normalization/deduplication, contact-based pagination,
60 KB response trimming without skipped continuation entries, stale response rejection, and separate
watch contact-cache persistence. Group notification tests preserve group IDs, titles, sender labels
and null one-to-one address; watch tests include direct chats without addresses and verify contact
selection itself does not enqueue/send a message.

On the S25 / Galaxy Watch6 Classic (not directly tested on the build machine):

1. Install both 1.4.0 APKs over 1.3.x. On the phone, allow Contacts under **Watch contact picker**.
   Deny it first if you want to check that recent chats, groups and manual entry still work.
2. Watch: **New message → Choose a contact**. Browse, search a name/number/email, load another page,
   and choose a contact with multiple addresses. Confirm each number/email is offered separately
   and that choosing a recipient only opens the composer. Then explicitly send a test message.
3. With automation disabled, confirm cached notification chats still open/reply, while starting
   a brand-new recipient reports the need for automation. With automation on/unlocked phone,
   verify OpenBubbles performs the actual send to the address chosen, not a different number.
4. Receive a real OpenBubbles group-message notification. Sync the watch; open **Groups** from
   Messages and **Group chats** in New message. Confirm the same existing group opens, sender
   labels remain visible, and a reply reaches the group once, not as separate direct messages.
5. Check a known direct conversation with no exposed address: it should still appear under Recent
   OpenBubbles chats. Groups that have never notified cannot be privately enumerated by this bridge.
6. Browse a contact page, disconnect the phone and reopen the picker. Cached entries remain usable
   offline, but searching beyond that cache and loading more need the phone. Reconnect and refresh.
7. Revoke Contacts on the phone, then sync/reopen the bridge. The watch should clear cached contact
   entries after receiving the revoked permission metadata/response. Offline copies cannot receive
   revocation until reconnect. No contact photos, notes, or third-party uploads should be introduced.
8. Try with an older phone bridge: contact loading should time out with an update/permission hint,
   not spin indefinitely. The wire additions remain protocol v3; use matching 1.4.0 apps for contacts.

## 1.3.1 Samsung permission-status regression checks

This update addresses a Galaxy S25 / Galaxy Watch6 Classic report: App info → Notifications said
Blocked with a grey switch, and accessibility was On in Samsung Settings but reported Off by the
bridge. The user reported Android 17 / One UI 9; no such device is connected to the build machine.
Robolectric tests do not constitute testing of that OS/OEM build.

Local validation: **52 tests passed** (17 protocol, 12 watch, 23 phone). Phone/watch debug and
minified release builds succeeded; lint completed with warnings and no errors. Both release APKs
passed signature verification, use the same certificate/application ID, report version `1.3.1`
with versionCode `5`, and exclude debug receivers.

New regression checks cover shortened/full component equivalence, rejection of the old bridge
and other services, malformed values, secure-settings fallbacks, enabled-but-not-connected state,
actual service attachment/detachment, and diagnostics that exclude other apps' component names.

On the actual Samsung phone:

1. Ignore the phone bridge's App info → Notifications switch: the bridge posts no phone alerts.
   Use the bridge's **Notification access** button and check the actual listener switch there.
   Only follow restricted-settings recovery if that special-access switch is blocked.
2. Turn on Accessibility → Installed apps → OpenBubbles Wear automation. Reopen the bridge. It
   should say On and distinguish whether the service has connected; it must not say Off just
   because Android shortened the component name.
3. Toggle accessibility off/on and check readiness updates on the phone and watch. An enabled,
   disconnected service should not be advertised as usable automation.
4. Check for a second, pre-1.3 bridge installation. Only the `com.openbubbles.wear` package serves
   the current watch; removing an old bridge must not remove OpenBubbles itself.
5. If any status still disagrees, use **Copy permission diagnostics** and share the output plus
   the exact blocked screen. No message text or contact information is included.

## 1.3.0 pre-device validation and checklist

Final local validation on 2026-10-27: **43 tests passed** (17 protocol, 12 watch, 14 phone);
both debug and R8 release APKs built successfully; phone/watch lint completed with warnings but
no errors. Both signed APKs report versionName `1.3.0`, versionCode `4`, and the same release
certificate. Release manifests contain the tile, complication, notification receiver, and Shizuku
provider where expected, and exclude the exported debug receivers. APKs and SHA-256 checksums
are available in `dist/`.

The packaged application ID must be `com.openbubbles.wear` on **both** devices, not the historical
phone-only `com.openbubbles.wear.bridge`. Matching certificates alone are insufficient for Data
Layer delivery. Rebuild both variants together from the shared root application-ID setting.

Automated checks cover protocol serialization plus Robolectric regression tests for the outbox,
fast-result/transport races, full-outbox rejection, paused relay, queued thread migration,
identical-text refreshes, read/visibility state, hash-colliding notification identities, and phone
request deduplication (including concurrent copies and interrupted requests). Debug/release
compilation, R8, Android lint, release signatures, and packaged version metadata are checked too.
These checks do **not** validate a real OpenBubbles account or paired Data Layer delivery.

Before relying on 1.3.0, test on your actual phone/watch:

1. If upgrading the phone from 1.2.0 or earlier, first remove the old
   `com.openbubbles.wear.bridge` bridge (its cache is not migrated; do not remove OpenBubbles).
   Install both 1.3.0 release APKs. Enable phone notification access; allow watch notifications from
   watch Settings. Receive a message with the watch app closed. Check text/voice reply, mark-read,
   and the alert's chat deep link. Turn extra alerts off if phone mirroring causes duplicates.
2. Leave a chat open, return to the watch face, then receive another message. It must alert again;
   messages received while actively viewing that chat should not raise a duplicate alert.
3. Disconnect the phone, queue two messages, restart the watch app, and confirm both remain queued.
   Reconnect and verify exactly one copy of each arrives, in order. Repeat with a stale notification
   and automation enabled. Pause relay and confirm queued messages are not dispatched until resumed.
4. Test new recipients, quiet chats, history loading, photos, GIFs, and Hide text previews using the
   real-device automation checklist above. Inspect OpenBubbles before retrying any unconfirmed send.
5. Add the Messages tile and a supported unread complication. Check empty, unread, read, offline,
   queued, and multiple-chat states. Tap each surface and confirm it opens the expected chat.
6. Without Shizuku, verify manual setup and diagnostics still work. Then optionally start Shizuku
   using wireless debugging, grant the bridge access, and run **Finish setup**. Confirm an existing
   accessibility service remains enabled, grants actually show on the status cards, and stopping
   Shizuku afterward does not break messaging. OEM rejection should leave manual setup usable.

The interrupted emulator processes were not treated as successful device tests. No physical
phone/watch was connected during the final pre-test validation; end-to-end delivery, Shizuku
grants, real accessibility selectors, tile rendering, and real attachment URI permissions still
require the checks above.

### Restricted notification-access regression checks

The nine notification-access setup tests check that App info uses the installed package (not the
source namespace), the listener settings link uses the exact component, unblock commands affect
only the bridge/current user, Android 12 skips the Android 13 app-op, normal grants do not change
restricted settings, existing grants run no shell commands, delayed grants are verified, and an
exit-code-zero denial is never shown as success. An unsupported app-op can still fall back to a
verified working notification grant.

On a real Android 13+ phone, install from the APK and test:

1. If notification access is greyed out, tap it once, dismiss the restricted-settings warning, then
   open **Notification access blocked? → Open App info → ⋮ → Allow restricted settings**. Confirm,
   return through **Open Notification access**, and enable the bridge.
2. Confirm that merely allowing ordinary app notifications is not shown as listener access.
3. If the menu is missing, start non-root Shizuku, grant access to the bridge, and explicitly choose
   **Unblock with Shizuku** from the recovery dialog. Verify the dialog reports actual access and
   receives a new OpenBubbles notification; accessibility automation must remain off if it was off.
4. Test a denied grant or managed-profile policy: setup must retain manual recovery instructions,
   not report success from the shell exit code. This project does not override administrator policy.

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
./gradlew :phone:testDebugUnitTest :wear:testDebugUnitTest
./gradlew :phone:lintDebug :wear:lintDebug
```

HTML reports are generated under each module's `build/reports/tests/`; Android lint reports are
under `phone/build/reports/` and `wear/build/reports/`.

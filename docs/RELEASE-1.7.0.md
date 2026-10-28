# 1.7.0 - send through OpenBubbles while the phone is locked (device-testing build)

## Replies no longer wait for unlock

OpenBubbles attaches a reply `PendingIntent` and `RemoteInput` key to each chat notification. The
phone bridge now saves that action per conversation in `ReplyActionStore` (up to 40 chats). The
action normally stays valid after the notification is dismissed, so the watch can keep sending
through OpenBubbles' own receiver without opening its UI or waiting for the phone to be unlocked.

Send order on the phone is now:

1. The live notification reply action, when the chat is currently notifying.
2. The saved reply action for that chat, even if the notification is gone.
3. The optional accessibility automation, which is still rejected while a secure keyguard is up.

The watch no longer holds messages back while the phone is locked. It dispatches immediately and
only queues with a backoff when the phone explicitly returns a retryable lock result. The outbox
and **Send queued now** button also stop deferring while locked.

This is best effort, not a keyguard bypass. The saved `PendingIntent` belongs to OpenBubbles. It can
be cancelled when OpenBubbles updates or dismisses a notification, or be single-use, in which case
OpenBubbles rejects the send and the bridge falls back to automation or the retryable outbox. A chat
that has never posted a notification has no saved reply action and still needs an unlocked phone
with automation enabled. Older-message history also still requires unlock because it drives
OpenBubbles' UI.

## Unchanged behaviour

- OpenBubbles handles accounts, encryption, delivery, and read receipts. The bridge never talks to
  Apple's servers and does not read OpenBubbles' private database.
- Live notification replies, mark-read, saved content intents, chat discovery through Shizuku, and
  cached chats continue to work while the phone is locked.
- Android's secure keyguard cannot be bypassed. The phone reports retryable lock failures and the
  watch retries automatically after unlock is reported.
- Protocol remains v3. No new fields or request paths are required; matching 1.7.0 phone/watch apps
  are recommended so `canReply` includes saved reply actions and the watch stops deferring sends.

## Install

Install **both 1.7.0 APKs** over 1.6.0 (versionCode 9). Signed APKs and SHA-256 checksums are
attached. The phone and watch still use the same application IDs and signing certificate as the
1.6.0 release.

This is a device-testing prerelease. Real S25 / Galaxy Watch6 Classic behavior, Android 17 / One UI 9
keyguard handling, saved-`PendingIntent` lifetime after dismissal/reboot, Data Layer delivery, and
OpenBubbles reply acceptance still need hardware validation. No physical devices were connected for
this build.

Local validation: **120 automated tests passed** (24 protocol, 61 phone, 35 watch), zero
failures/errors. Both apps' debug and R8 release builds succeeded. Lint completed with no errors
(45 phone / 38 watch warnings). Signed APKs report versionName `1.7.0` / versionCode `9` and
contain no debug receivers.

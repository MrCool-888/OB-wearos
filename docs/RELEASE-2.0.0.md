# 2.0.0 - notification-only sending (device-testing build)

## Accessibility automation removed

The optional accessibility service is gone. The phone bridge no longer drives OpenBubbles' UI,
scrapes chat history, or starts conversations. This removes the largest permission and reliability
surface from the app.

Removed with it:

- The automation setup card, status, and permission diagnostics on the phone.
- Older-message history loading and the **Load older messages** / **Latest messages** controls.
- Starting a brand-new chat from the watch, including manual number/email entry.
- Automation fallback for quiet-chat sends.

The **New message** screen is now **Find a chat**. It opens only chats OpenBubbles has already
published or notified; contacts and addresses are matched to existing cached chats. The **Choose a
contact** picker still reads the Android address book when Contacts is granted, but selecting a
contact cannot create a conversation.

## How sending works now

There is exactly one send path: OpenBubbles' notification reply action.

1. The live notification reply action, when the chat is currently notifying.
2. The saved reply action from an earlier notification, even if the notification is gone.

The phone can stay securely locked and OpenBubbles does not need to open. There is no automation
fallback, no OpenBubbles composer, and no history scrape.

The watch rejects a chat with no reply action before transport and explains that OpenBubbles needs
to post a notification for that chat first. Queued sends that later lose their reply action fail
with that explanation instead of retrying forever.

## Unchanged behaviour

- OpenBubbles handles accounts, encryption, delivery, and read receipts. The bridge never talks to
  Apple's servers and does not read OpenBubbles' private database.
- Cached conversations, groups, contact lookup for existing chats, media viewing, mark-read, open
  on phone, watch alerts, tile/complication, and the offline outbox are retained.
- Notification access is still required. Shizuku remains optional for setup and published
  shortcut/channel discovery.
- Protocol remains v3. Removed history/automation fields are optional in the wire format, so mixed
  1.7.0/2.0.0 payloads decode safely, but matching 2.0.0 phone/watch apps are recommended.
- Android's secure keyguard cannot be bypassed. Sending depends on OpenBubbles' reply token, which
  it may cancel when updating or dismissing a notification.

## Limitations

- A chat that has never posted a notification has no reply action and cannot be sent to while
  locked, or at all, without OpenBubbles posting one.
- The watch cannot start new conversations and cannot load older message history.
- Saved reply actions are best effort and may be single-use or cancelled after a reboot.
- Mark-read requires a live notification.

## Install

Install **both 2.0.0 APKs** over 1.7.0 (versionCode 10). Signed APKs and SHA-256 checksums are
attached. The phone and watch still use the same application IDs and signing certificate as 1.7.0.
Any previously enabled accessibility service can be turned off in Android settings.

This is a device-testing prerelease. Real S25 / Galaxy Watch6 Classic behavior, Android 17 / One UI 9
keyguard handling, saved-`PendingIntent` lifetime after dismissal/reboot, Data Layer delivery, and
OpenBubbles reply acceptance still need hardware validation. No physical devices were connected for
this build.

Local validation: **105 automated tests passed** (24 protocol, 52 phone, 29 watch), zero
failures/errors. Both apps' debug and R8 release builds succeeded. Lint completed with no errors
(45 phone / 38 watch warnings). Signed APKs report versionName `2.0.0` / versionCode `10`, share the
existing release certificate, and contain no accessibility service or debug receivers.

SHA-256:

```text
8b8b714e6248fe3cff8a5bccdd6ee7e705c3107b069cff7b535c162c89d233f8  OpenBubblesWear-Phone-2.0.0.apk
4d9c0b2539c9226748e5c9f42a0ee0bee7e133b45160fd67a101ee9f8188fc0a  OpenBubblesWear-Watch-2.0.0.apk
```

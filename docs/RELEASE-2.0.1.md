# 2.0.1 - OpenBubbles 2.x discovery and contact fixes

## Fixed

- **Shizuku chat discovery works with OpenBubbles 2.x.** Discovery now reads the published
  shortcut IDs `chat_<key>` and conversation channels `conv_chat_<key>`, in addition to the 1.x
  `com.bluebubbles.new_messages.<GUID>` names. The key can be a chat GUID, `addr:<address>`, or
  `contact:<Android contact id>`. Group status still comes only from the GUID, never from a
  title. Discovery previously imported nothing from 2.x because those IDs are not bare chat GUIDs.
- **Contact lookup prefers the current phone ID.** The latest phone sync summary now wins over a
  cached thread. A thread cached under an older bridge/OpenBubbles ID, or one the phone has since
  trimmed, can no longer override the current ID and answer "Conversation not found".
- **Discovery lists only chats the phone kept.** History-free discovery placeholders have a
  bounded budget on the phone. Discovery used to report every imported chat, so tapping one that
  the store had already evicted answered "Conversation not found".
- **Legacy direct-chat history follows the new key.** Importing a direct chat folds a legacy
  GUID-keyed conversation with the same normalized address instead of showing two entries.

## Compatibility and limitations

- Protocol remains v3. Matching 2.0.1 phone/watch apps are recommended; 1.7.0 and 2.0.0 payloads
  still decode.
- A chat published only under `contact:<Android contact id>` has no address until OpenBubbles
  posts a notification, so the watch contact picker cannot match it by number/email before that.
- Discovery remains a partial list. Chats that have never appeared to Android still need a
  notification before the watch can list or send to them.
- Sending, lock behavior, media, mark-read, and the no-accessibility design are unchanged from
  2.0.0.

## Install

Install **both 2.0.1 APKs** over 2.0.0 or 1.7.0 (versionCode 11). Signed APKs and SHA-256
checksums are attached. The phone and watch keep the same application IDs and signing certificate
as 1.7.0/2.0.0, so cached chats and permissions are preserved.

This is a device-testing prerelease. Real S25 / Galaxy Watch6 Classic behavior, Android 17 / One
UI 9 shortcut and channel dumps, keyguard handling, Data Layer delivery, and OpenBubbles reply
acceptance still need hardware validation. No physical devices were connected for this build.

Local validation: **112 automated tests passed** (24 protocol, 57 phone, 31 watch), zero
failures/errors. Both apps' debug and R8 release builds succeeded. Lint completed with no errors
(45 phone / 38 watch warnings). Signed APKs report versionName `2.0.1` / versionCode `11`, share
the existing release certificate, and contain no accessibility service or debug receivers.

SHA-256:

```text
550462628f84c4214207ed22f1009f96a1056493283c1fc1a0278c7b819b00fb  OpenBubblesWear-Phone-2.0.1.apk
f0af5794e5f26bcee328ca505970cf1ca133221be159a576cc3741e0715142db  OpenBubblesWear-Watch-2.0.1.apk
```

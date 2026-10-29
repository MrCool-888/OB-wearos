# 2.1.7 validation

Companions 2.1.7/code20; Morphe 1.2.4; native OB 2.0.0/code20002289.

## Reproduced behavior

Some group chats kept OpenBubbles' internal identifier (`chat125428204935`) as the title. The
resolver accepted it because it was not a GUID, phone number or email address, so the group never
fell through to participant names. The watch preserved the same raw title across refreshes.

## Corrected behavior

- `chat<number>` and `chat_<id>` strings are no longer treated as display names by the patch or by
  the companion title preference logic.
- Unnamed groups fall back to their participants' saved contact names. Custom group names and
  explicit renames are still accepted and preserved.
- Direct chats, group senders, raw addresses and chat GUID handling are unchanged.
- Chat IDs, addresses, participants, message routing and the wire format are unchanged.

## Automated checks

269 passed, zero failed, one Windows symlink test skipped: protocol 38, phone 101, watch 89,
patch 41. Both signed companion release builds passed. Morphe test/assemble, signed four-APK
packaging, independent installable verification, universal merge/signature/alignment checks and
release checksums passed.

## Device checks

Installed in place with `adb install -r` on the Galaxy S25 (SM-S931U1) and Galaxy Watch7 (SM-R950);
app data was preserved and no app was uninstalled. On the watch:

- The group previously titled `chat125428204935` now shows its participant names.
- Direct chats still show contact names (Sam, Adrian, Chris).
- The custom group name "the myg" was preserved.

No message was sent, so send and Apple delivery remain unverified. No app data was cleared and no
message text, address or credential is recorded in this report.

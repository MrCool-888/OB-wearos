# 2.1.6 validation

Companions 2.1.6/code19; Morphe 1.2.3; native OB 2.0.0/code20002289.

## Reproduced behavior

Native OpenBubbles 2.0 can return a blank display name and title for a direct chat and a generic
`Group` title for a group. The bridge previously exported the chat GUID or a raw phone/email
address as the title, and group message senders as raw handles. The watch therefore showed no
contact name even though the same contact was in Android's address book.

## Corrected behavior

- The patch resolves each title and sender handle through OpenBubbles'
  `AndroidContactsRepository`. Direct chats use the saved contact name, unnamed groups use
  participant names, and message senders use their contact name. Raw addresses, chat GUIDs and
  generic labels are not accepted as names.
- Contact resolution is cached per request and bounded to three seconds. If Contacts permission is
  missing or a lookup fails or times out, the bridge falls back to OpenBubbles' cached contact
  (`peek`) and then to the existing label; a name lookup cannot fail the request.
- The phone bridge and watch preserve an existing human title when refreshed metadata is generic
  or raw. A new meaningful title still wins, so renames propagate.
- Chat IDs, addresses, participants, message routing and the wire format are unchanged.

## Automated checks

269 passed, zero failed, one Windows symlink test skipped: protocol 38, phone 105, watch 85,
patch 41. Both signed companion release builds and release lint passed. Morphe test/assemble,
signed four-APK packaging, independent installable verification, universal merge/signature/
alignment checks and release checksums passed.

## Device checks

No phone or watch was attached to this session. The read-only `NameProbe` in `work/id-probe`
reports only counts (chats, named chats, groups, named groups, named senders) and never prints
names, addresses, message text or credentials; it was not run. Live contact-name display therefore
remains unverified for 2.1.6, and the last device validation is the 2.1.5 read-only history and
failed-message check. No app data was cleared, no app was uninstalled, and no message was sent.

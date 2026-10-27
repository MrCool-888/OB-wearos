# 1.4.0 — contact picker and group browsing (device-testing build)

## Choose contacts on the watch

On the phone bridge, tap **Allow contact picker** and approve Contacts. On the watch, open
**New message → Choose a contact**. Browse/search by name, number or email; load additional pages;
choose which address to use when a contact has several. Selection opens a composer, never sends
automatically. Starting a brand-new conversation still needs the existing accessibility automation
and an unlocked phone; sending remains inside OpenBubbles.

This uses Android's address book, which OpenBubbles uses on Android. It does not read OpenBubbles'
private database. Private/imported OpenBubbles-only entries may be absent, and contacts are not a
directory of registered Apple/iMessage accounts. Permission is optional: recent chats, groups and
manual number/email entry still work without it. No root or OpenBubbles modification is required.

## Group chats

The Messages screen now has an explicit **Groups** view. **New message** includes a **Group chats**
section as well as recent direct chats, including chats with no exposed address. Groups retain
their existing conversation IDs and sender labels: choosing one does not create a new group or
message its members separately.

**Group discovery still depends on OpenBubbles notifications.** Receive a group message with
notifications enabled, then sync. This release cannot list groups that OpenBubbles has never
exposed through a notification; that would require another supported integration surface.

## Privacy and compatibility

Only requested name/number/email pages are copied over the Wear Data Layer. No contact photos,
notes, or third-party upload. The last browsed list is cached privately on the watch. Contact
permission revocation clears it after the next metadata sync/response; an offline watch cannot
receive a revocation until it reconnects.

Install both 1.4.0 APKs over 1.3.x. Version code is 6; the additive contact paths/optional metadata
retain protocol v3. For pre-1.3 upgrades, remove only the obsolete phone bridge
`com.openbubbles.wear.bridge`, not OpenBubbles. The current phone/watch package is `com.openbubbles.wear`.

This is a device-testing pre-release. Real OpenBubbles send/history automation, contact permission
behavior and group replies still need validation on your phone and Galaxy Watch. Signed APKs and
SHA-256 checksums are attached.

Validation: 73 automated tests passed. Both apps' debug/R8 release builds and lint completed
(warnings but no errors); matching signatures, package/version metadata, Contacts declaration,
and exclusion of debug receivers were verified.

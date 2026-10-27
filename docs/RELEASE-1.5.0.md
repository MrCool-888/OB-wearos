# 1.5.0 — published groups and history on open (device-testing build)

## Groups without a notification

Open OpenBubbles on the phone first so it can publish recent chat shortcuts. Start Shizuku using
wireless debugging (not root), grant the phone bridge Shizuku access, then use either:

- Watch: **Groups → Find groups (Shizuku)**, also available under New message.
- Phone bridge: **Find published groups with Shizuku**, then sync the watch.

The bridge queries only OpenBubbles' Android-published shortcuts for your current Android user.
Published groups do not require a notification and reopen using the exact conversation GUID.
The private database is not read, OpenBubbles is not modified, and encryption/sending stay in
OpenBubbles. Contacts permission is not needed for group discovery.

**This is not every group.** The inspected OpenBubbles source publishes up to four recent named
chats at startup; these can include direct chats. Unpublished groups remain absent until exposed
by a notification or another supported integration. Android/OEM restrictions may block querying.
Shizuku is optional for everything other than this shortcut discovery route.

## History when opening a contact or group

- Contact/manual recipient selection opens a chat/history screen instead of going straight to
  the composer. Nothing is sent by selecting a contact. Tap Reply/Send message to compose.
- Opening a chat automatically requests readable text history when the phone is online, the relay
  is enabled, and accessibility automation is connected. **The phone must be unlocked.** This
  opens and scrolls OpenBubbles on the phone; it is not a background database reader.
- History works with an empty watch cache. Reading history never creates a new phone conversation.
- **Load older messages** reads another older page. A separate 220-message older viewing window
  retains the page even when the latest cache is full or another sync arrives. **Latest messages**
  switches back to the newest cache. The older viewing window is memory-only; the newest cache
  remains saved for offline reading.
- History results, failures and timeouts are matched to their request IDs. Late responses cannot
  clear a newer request. Missing/ambiguous oldest-text anchors fail clearly rather than guessing
  a boundary. Unreadable UI text is not marked as exhausted history.

History is best effort and bounded to 30 screen pages/two minutes per job. Times are approximated
in one-minute steps, not the original timestamps. Accessibility history is text-only and has no
reliable group sender names. Repeated/truncated text or an incompatible UI can prevent loading.
This does not promise complete history or complete group discovery.

Install **both 1.5.0 APKs** over 1.4.0 (versionCode 7). Protocol remains v3 with additive paths and
optional group/history fields. Use matching phone/watch apps for anchored history pagination.

This is a device-testing prerelease. Actual S25/Watch6 Classic, Android 17/One UI 9, Shizuku shortcut
access, Data Layer delivery and real signed-in OpenBubbles selectors still require hardware
validation; no physical devices were connected for this release. Signed APKs and SHA-256 checksums
are attached.

Local validation: **100 automated tests passed** (23 protocol, 49 phone, 28 watch). Both apps'
debug/R8 release builds and lint passed (warnings, no errors). Matching package/signature/version
metadata, upgrade signing-key continuity, and absence of debug receivers were verified.

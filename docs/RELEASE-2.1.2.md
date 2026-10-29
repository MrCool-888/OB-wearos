# OpenBubbles Wear 2.1.2 release notes

Testing build: phone and watch companions 2.1.2 / version code 15, with Morphe patch 1.2.0 for
native OpenBubbles 2.0.0 / version code 20002289. The native OpenBubbles version and code are
unchanged.

## Changes

- Moved the **Wear companion bridge** runtime toggle to **OpenBubbles → Settings → Morphe patches**.
  The setting gates patch service requests and direct media access. The phone companion no longer
  contains a patches-management menu.
- Added paginated chat discovery with 40 rows per page. The watch can load the directory through
  its 5,000-entry cap; phone-side discovery drains the available count and later syncs remain
  bounded.
- Preserved bounded recent-history and cache limits: 40 chats with detailed phone history plus 60
  additional chat entries. Media and message history remain bounded.
- A chat stays discoverable when optional latest-message links, timestamps, or previews are unreadable. Ordinary sync no longer overwrites directory entries loaded from later pages.
- Existing users can update all three apps in place using the same signing key and APK format.

## Validation

- **237 tests passed, zero failures; one Windows symlink test skipped.** Protocol32, phone93, watch79, patch33 passed. Both signed companion release builds and release lint passed. Fresh patch application, complete signed APK-set verification, runtime-settings persistence/service gating, 95-chat paging/sync retention, stale-page rejection, unreadable-message metadata, and transport-size continuation were checked.
- User-reported runtime observations: companion status reached **Connected · Ready**, and older
  chats appeared in the main OpenBubbles app. No physical phone/watch validation is claimed here;
  real delivery, secure-lock sends, media transfer, and Wear Data Layer behavior still need device
  verification.
- OpenBubbles 2.0 removed its local Messages Backups option. No complete backup/restore procedure
  for replacing stock OpenBubbles is verified. This release note does not recommend uninstalling
  stock or imply a backup path.

For the in-place update sequence and test checklist, see [Install 2.1.2](INSTALL-2.1.2.md).

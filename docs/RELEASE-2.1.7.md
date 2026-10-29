# OpenBubbles Wear 2.1.7 release notes

This coordinated release contains phone and watch companions **2.1.7** (versionCode **20**),
Morphe patch **1.2.4**, and an updated patched APK set for native OpenBubbles 2.0.0
(versionCode **20002289**). Install all components together; this is not a companion-only update.

## Changes

- Fixed group chats that still showed an OpenBubbles identifier such as `chat125428204935` as the
  title. The patch no longer treats `chat<number>` / `chat_<id>` as a display name, so unnamed
  groups fall back to their participants' saved contact names ("Alex, Sam, …").
- Both companions now reject a cached raw `chat<number>` identifier when refreshed metadata arrives,
  so the participant list replaces it. Custom group names and explicit renames still win.
- Direct chats keep showing saved contact names; raw phone numbers, email addresses and chat GUIDs
  are still never accepted as titles.
- Includes the matching patched OpenBubbles phone APK set, both companions, Morphe 1.2.4, and
  `SHA256SUMS.txt` in the full release bundle when optional packaging inputs are available.

## Installation

Install the patched OpenBubbles APK set containing patch 1.2.4 and both companion 2.1.7 APKs.
Follow [Install 2.1.7](INSTALL-2.1.7.md), preserve the installed universal-versus-split APK format,
and use the same signing keys with in-place updates. Do not uninstall or clear data to work around
a signature mismatch.

## Validation and limitations

- **269 tests passed, zero failures; one Windows symlink test skipped**: protocol 38, phone 101,
  watch 89, patch 41.
- Both companion release builds passed. Signed patch packaging, independent APK-set verification,
  universal APK metadata/signature/alignment checks and release checksums passed.
- Device check: 2.1.7 was installed in place on the Galaxy S25 phone and Galaxy Watch7. On the
  watch, the group previously titled `chat125428204935` now shows its participant names, direct
  chats still show contact names, and the custom group name "the myg" was preserved.
- No message was sent, so send and Apple delivery behavior are unchanged and unverified. Apple
  throttling is unchanged, and uncertain sends are not automatically retried.
- Names require the Contacts permission OpenBubbles already uses. If access is missing, the watch
  keeps addresses; unnamed groups fall back to participant addresses.

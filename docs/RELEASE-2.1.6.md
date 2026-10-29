# OpenBubbles Wear 2.1.6 release notes

This coordinated release contains phone and watch companions **2.1.6** (versionCode **19**),
Morphe patch **1.2.3**, and an updated patched APK set for native OpenBubbles 2.0.0
(versionCode **20002289**). Install all components together; this is not a companion-only update.

## Changes

- Fixed contact names missing on the watch. The Morphe patch resolves saved Android contact
  display names for direct conversations, unnamed groups and group message senders instead of
  exporting a phone number, email address or opaque chat ID as the title.
- Contact lookup is presentation-only: chat IDs, recipient addresses, participants and send
  routing are unchanged. Lookups are bounded per request, reuse OpenBubbles' cached contacts,
  and fall back to the existing label when Contacts is unavailable.
- The phone and watch keep a previously cached human name when refreshed metadata is generic or
  raw (`Conversation`, `Group`, a chat GUID, a phone number or an email address). An explicit
  human rename still replaces the old name.
- Includes the matching patched OpenBubbles phone APK set, both companions, Morphe 1.2.3, and
  `SHA256SUMS.txt` in the full release bundle when optional packaging inputs are available.

## Installation

Install the patched OpenBubbles APK set containing patch 1.2.3 and both companion 2.1.6 APKs.
Follow [Install 2.1.6](INSTALL-2.1.6.md), preserve the installed universal-versus-split APK format,
and use the same signing keys with in-place updates. Do not uninstall or clear data to work around
a signature mismatch.

## Validation and limitations

- **269 tests passed, zero failures; one Windows symlink test skipped**: protocol 38, phone 105,
  watch 85, patch 41.
- Both companion release builds and release lint passed. Signed patch packaging, independent APK-set
  verification, universal APK metadata/signature/alignment checks and release checksums passed.
- No phone or watch was attached during this build, so live contact-name resolution was not
  re-verified on a device. The read-only name probe in `work/id-probe` is available for that
  check; the 2.1.5 read-only history and failed-message checks remain the last device validation.
- Names require the Contacts permission OpenBubbles already uses. If access is missing, the watch
  keeps addresses; unnamed groups fall back to participant names or addresses.
- No physical send test or successful Apple delivery is claimed. Apple throttling is unchanged,
  and uncertain sends are not automatically retried.

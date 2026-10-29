# OpenBubbles Wear 2.1.5 release notes

This coordinated release contains phone and watch companions **2.1.5** (versionCode **18**),
Morphe patch **1.2.2**, and an updated patched APK set for native OpenBubbles 2.0.0
(versionCode **20002289**). Install all components together; this is not a companion-only update.

## Changes

- Fixed native OpenBubbles exact 72-character hexadecimal conversation IDs being rejected by Morphe
  discovery and conversation operations. Native UUID and legacy conversation IDs remain supported.
- Read group metadata from persisted participants and delivery status from saved message fields.
  Freshly hydrated database entities can expose uninitialized flows; the bridge no longer exports
  those default values as the real group or delivery state.
- Keep opaque native chat IDs separate from recipient phone numbers and email addresses.
- Report history-refresh failures instead of silently retaining cached history as though refresh
  succeeded.
- Includes the matching patched OpenBubbles phone APK set, both companions, Morphe 1.2.2, and
  `SHA256SUMS.txt` in the full release bundle when optional packaging inputs are available.

## Installation

Install the patched OpenBubbles APK set containing patch 1.2.2 and both companion 2.1.5 APKs. Follow
[Install 2.1.5](INSTALL-2.1.5.md), preserve the installed universal-versus-split APK format, and use
the same signing keys with in-place updates. Do not uninstall or clear data to work around a
signature mismatch.

## Validation and limitations

- Read-only same-key instrumentation established that an affected 72-hex group ID failed history
  with `InvalidId` and zero history rows under the prior bridge. The ID format is now accepted
  strictly; group status still comes from explicit metadata.
- The same read-only group request now succeeds: 79 messages, including 32 outgoing and 6 failed,
  with correct group metadata. After updating all three apps in place, the user confirmed that
  the failed messages appear on the watch with their failure status.
- **256 tests passed, zero failures; one Windows symlink test skipped.** Both companion release
  builds/lint and signed patch packaging passed. Two additional Android runtime checks verified
  delivery-state precedence and group metadata on hydrated native entities.
- No physical send test or successful Apple delivery is claimed. The diagnostic helper was removed.
- Old attempts rejected locally by the watch may never have been persisted in the OpenBubbles
  database; refresh can show only rows OpenBubbles persisted.
- Apple throttling is unchanged, and uncertain sends are not automatically retried.
- Cached history remains a fallback when a refresh fails, with the failure now surfaced to the user.

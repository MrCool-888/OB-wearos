# OpenBubbles Wear 2.1.4 release notes

This coordinated release contains phone and watch companions **2.1.4** (versionCode **17**),
Morphe patch **1.2.1**, and an updated patched APK set for native OpenBubbles 2.0.0
(versionCode **20002289**). Install all components together; this is not a companion-only update.

## Changes

- Fixed native OpenBubbles UUID conversation IDs being rejected by Morphe discovery and
  conversation operations. Legacy conversation IDs remain supported.
- Carry group status explicitly with directory metadata, since a UUID alone does not identify a
  group. The prior patch rejected all 16 discovery rows in the observed phone diagnostics because
  their IDs were invalid under its validator.
- Report history-refresh failures instead of silently retaining cached history as though refresh
  succeeded.
- Includes the matching patched OpenBubbles phone APK set, both companions, Morphe 1.2.1, and
  `SHA256SUMS.txt` in the full release bundle when optional packaging inputs are available.

## Installation

Install the patched OpenBubbles APK set containing patch 1.2.1 and both companion 2.1.4 APKs. Follow
[Install 2.1.4](INSTALL-2.1.4.md), preserve the installed universal-versus-split APK format, and use
the same signing keys with in-place updates. Do not uninstall or clear data to work around a
signature mismatch.

## Validation and limitations

- Connected-phone USB logs showed `IllegalArgumentException: Invalid conversation ID` in
  `BridgeOps.resolve` for history, send, and mark-read; all 16 discovery rows were rejected as invalid GUIDs. The
  UUID validation and explicit group metadata changes address that observed cause.
- **253 tests passed, zero failures; one Windows symlink test skipped.** Protocol 34, phone 99,
  watch 86, patch 34 passed. Both companion release builds/lint, fresh Morphe patch application,
  signed APK-set checks, and universal 7-DEX/9-library payload/signature/alignment checks passed.
- The connected phone was updated in place after its signing identity and existing universal
  format were verified. Watch update and physical read-only refresh confirmation are pending.
  No physical send test or end-to-end delivery
  result is claimed here. Old attempts rejected locally by the watch may never have been persisted
  in the OpenBubbles database; refresh can show only rows OpenBubbles persisted.
- Apple throttling is unchanged, and uncertain sends are not automatically retried.
- Cached history remains a fallback when a refresh fails, with the failure now surfaced to the user.

# OpenBubbles Wear 2.1.8 release notes

This coordinated release contains phone and watch companions **2.1.8** (versionCode **21**),
Morphe patch **1.2.5**, and an updated patched APK set for native OpenBubbles 2.0.0
(versionCode **20002289**). Install all components together; this is not a companion-only update.

## Changes

- Fixed chat photos and GIFs that never appeared when the direct OpenBubbles bridge had not
  downloaded the attachment yet. The patch now exposes every image attachment and downloads it on
  the watch's first request, with a 25-second download budget. Previously only images already
  downloaded into OpenBubbles were exposed.
- Fixed images that stayed broken when OpenBubbles still recorded an attachment as downloaded but
  its cached file was gone. The provider now detects the stale entry, re-downloads it, and refreshes
  the local-path state before serving the file.
- Media requests from the watch now run on a background thread on the phone, so an on-demand
  attachment download cannot block the bridge service.
- Redesigned the Messages tile: card-style rows, ellipsis on every one-line text, tighter spacing,
  a centered title and a single combined footer line. This stops the clipping seen on a 432×432
  display when the title sat in the top arc or unread chats and the queued-send count both added
  footer lines.
- Unread rows remain capped at three, with `+N more unread` and the queued count folded into one
  footer.
- Includes the matching patched OpenBubbles phone APK set, both companions, Morphe 1.2.5, and
  `SHA256SUMS.txt` in the full release bundle when optional packaging inputs are available.

## Installation

Install the patched OpenBubbles APK set containing patch 1.2.5 and both companion 2.1.8 APKs.
Follow [Install 2.1.8](INSTALL-2.1.8.md), preserve the installed universal-versus-split APK format,
and use the same signing keys with in-place updates. Do not uninstall or clear data to work around
a signature mismatch.

## Validation and limitations

- **270 tests passed, zero failures; one Windows symlink test skipped**: protocol 38, phone 101,
  watch 90, patch 41.
- Both companion release builds passed. Signed patch packaging, independent APK-set verification,
  universal APK metadata/signature/alignment checks and release checksums passed.
- Device validation for 2.1.8: both companions and the patched universal APK were installed in
  place on the paired phone and watch with versions, signature and existing data verified. A
  direct-bridge GIF that previously failed with `FileNotFoundException` re-downloaded and displayed
  after the stale-cache fix. The tile empty state was checked on hardware and is fully visible. The
  three-row tile with a footer was not reproduced because the paired account had no unread messages;
  that crowded state is geometrically checked but not observed.
- No message was sent, so send and Apple delivery behavior are unchanged and unverified. Apple
  throttling is unchanged, and uncertain sends are not automatically retried.
- Names require the Contacts permission OpenBubbles already uses. If access is missing, the watch
  keeps addresses; unnamed groups fall back to participant addresses.

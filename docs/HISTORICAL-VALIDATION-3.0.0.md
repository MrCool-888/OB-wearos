# Historical integrated 3.0.0 validation — 2026-10-29

> Historical report from the former combined checkout. It covers a coordinated watch, phone bridge, and Morphe candidate that this watch-only repository no longer builds. Phone-side, Morphe, stock-APK, and coordinated-package results below are not reproducible from this checkout and do not certify later changes.

The local candidate was watch 3.0.0/version code 22, Morphe patch 2.0.0, and native OpenBubbles 2.0.0/version code 20002289. It was not published.

## Automated checks at that time

| Suite | Passed | Skipped | Failed |
|---|---:|---:|---:|
| Protocol | 39 | 0 | 0 |
| Embedded phone library | 44 | 0 | 0 |
| Watch | 92 | 0 | 0 |
| Morphe/native | 47 | 1 | 0 |
| Python payload audit | 10 | 0 | 0 |
| Total | 232 | 1 | 0 |

Release lint, signed watch build, Morphe patch application, signed split packaging, split verification, and universal packaging passed in the former integrated checkout. The Windows-only skip was an existing symlink check.

## Device checks at that time

The corrected universal phone APK was installed in place with its original package identity, UID, install time, and data directory. The watch was installed fresh and received 15 conversations over protocol v4. Installed APK hashes matched the tested local artifacts. The old companions were disabled without uninstalling them or discarding their data/outboxes.

Live messaging, Apple delivery, a new incoming alert, reconnect/reboot behavior, and the 3.0.0 media/UI walkthrough were not tested. The user retained messaging testing. These outcomes are a dated record only and are not evidence for a future build.

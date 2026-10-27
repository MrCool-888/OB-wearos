# 1.3.1 — Samsung permission-status fixes (device-testing build)

## Fixed: accessibility On in Settings but Off in the bridge

Android can report an accessibility component with a shortened class name. 1.3.0 compared raw
strings and could miss the same enabled service. 1.3.1 parses component identities and recognizes
both spellings. It also distinguishes **On in Android settings** from **Service connected**, checks
the actual running service before advertising automation to the watch, and refreshes capability
state when the service connects or unbinds. Shizuku setup no longer duplicates shortened entries.

## Clarified: Samsung App info → Notifications says Blocked

The phone bridge does not post phone alerts. Its ordinary App info → Notifications switch can
therefore say **Blocked** and be greyed out. That is not the permission required to read OpenBubbles
messages. Open the bridge and tap **Notification access**, or use Settings → Apps → Special access →
Notification access. Only use restricted-settings recovery if that actual listener switch refuses
to enable. Watch alerts are configured on the watch.

Do not disable device-wide security protections just to enable the unused phone-alert switch.
The bridge cannot override administrator policy or promise that a Samsung security restriction
can be removed without your approval.

## Diagnostics

**Copy permission diagnostics** includes the Android version/API level, exact app components,
permission/connection flags, Shizuku state, and whether an old bridge is also installed. It excludes
messages, contacts, notification text, and other apps' enabled-service names. If a status still
disagrees with Settings, share this output and the exact warning screen.

## Install

Install both 1.3.1 APKs over 1.3.0; permissions should remain granted. Version code is 5 and wire
protocol remains v3. If coming from 1.2 or earlier, remove only the old phone bridge
`com.openbubbles.wear.bridge`, not the official OpenBubbles app, then grant permissions to the new
`com.openbubbles.wear` package. The phone and watch must use the same signing certificate/app ID.

This is a device-testing pre-release. It responds to a Galaxy S25 / Galaxy Watch6 Classic report
with reported Android 17 / One UI 9, but that hardware/OS combination has not been tested here.
Real OpenBubbles sending, accessibility selectors, attachment URI access, and Samsung policy behavior
still require on-device validation. Signed APKs and `SHA256SUMS.txt` are attached.

52 automated tests passed; debug/release builds, lint (warnings but no errors), matching signing
certificates, package/version checks, and release debug-receiver exclusion checks passed.

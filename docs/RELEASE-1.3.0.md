# 1.3.0 — device-testing build

## Notification access blocked on Android?

The phone bridge now includes **Notification access blocked?** with the correct Android recovery
steps and direct links to App info and notification-listener access. Android 13+ may block these
permissions for sideloaded APKs. Allowing ordinary app notifications is a different permission.

Try enabling listener access once, dismiss the Restricted settings warning, then open **App info →
⋮ → Allow restricted settings** and confirm. Return to **Notification access** and enable the bridge.
If the menu is missing, the recovery dialog offers an explicit, optional **Unblock with Shizuku**
action after you grant Shizuku access. This changes only the bridge's restricted-settings approval
and notification-listener grant, not accessibility automation or OpenBubbles. No root is required.

Grant success is verified with Android's actual component-specific listener status; shell exit
code zero is no longer accepted as proof. OEM enhanced confirmation may still need manual approval.
Administrator/managed-profile restrictions are not overridden. Only approve notification access if
you trust the bridge: Android permits a listener to read all notifications, including sensitive ones.

## Other additions and fixes

- Watch notifications with text/voice reply, mark-read, and chat shortcuts.
- Persistent offline outbox, request deduplication, and send-state race fixes.
- Unread conversation tile and watch-face complication.
- Optional Shizuku setup and phone diagnostics.
- Corrected the application-ID mismatch that prevented phone/watch Data Layer communication.
- Photo/GIF viewing, new chats, quiet-chat sends, and older-message loading remain available.

## Important upgrade step

If upgrading the **phone** from 1.2.0 or earlier, uninstall the old bridge package
`com.openbubbles.wear.bridge`, then install the new phone APK and regrant its permissions.
**Do not uninstall the official OpenBubbles app.** The bridge cache is not migrated, but your
OpenBubbles account/messages are untouched. The watch APK updates normally. Both new APKs use
`com.openbubbles.wear`, the same signing certificate, and wire protocol v3.

## Validation

43 automated tests passed. Phone/watch debug and minified release builds passed; lint had warnings
but no errors. Release signatures and package/version identities were verified. Download both APKs
and `SHA256SUMS.txt` from this release.

This is a pre-release for real-device testing, not a claim of end-to-end validation. Actual
OpenBubbles delivery, media URI access, accessibility selectors, OEM permission approval, and
tile/complication rendering still need your phone and watch. See `docs/TESTING.md` in the repository.

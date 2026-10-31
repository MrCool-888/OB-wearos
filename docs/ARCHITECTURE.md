# Architecture and repository split

This checkout contains the Wear OS app, its protocol module, and a local notification test utility.

- `wear/`: Wear OS app. Version 3.0.0, version code 22, package/application ID `com.openbubbles.messaging`.
- `protocol/`: version 4 Data Layer messages and models shared between phone and watch.
- `testnotifier/`: a developer utility for generating sample OpenBubbles notifications; it is not needed to build the watch app.

The phone integration is maintained separately in the [mrcool-patches repository](https://github.com/MrCool-888/mrcool-patches). That repository owns the Morphe patch and embedded phone-side bridge. This checkout has no phone app module, Morphe project, stock APK inputs, or phone packaging step.

## Shared wire contract

The `protocol/` module is intentionally duplicated byte-for-byte in the phone patch repository so each repository can build independently. Both copies define the protocol v4 wire contract. When changing a protocol model, path, encoding, or behavior, make the matching change in both repositories and run both protocol test suites before releasing either side.

## Identity and signing

The watch application ID and release signing key must match the companion identity expected by the phone patch. Preserve the existing key for updates. CI's default artifact is an unsigned debug APK; the signed release job can run only by explicitly requesting it from `workflow_dispatch` and supplying the existing signing secrets.

## Notifications

The watch manifest disables automatic mirroring of this phone app's notifications with Wear OS's `NO_BRIDGING` mode. The embedded phone bridge supplies live message alerts instead, and the watch creates its own notification with an exact-chat activity intent. The phone continues to show its native notifications. This app requires no separate watch sign-in before the bridge can deliver alerts.

The phone hook forwards alerts only while the phone's lock screen is showing; cached history and sync snapshots never create alerts. Opening a watch chat marks it read through the existing bridge, which clears that conversation's native phone notification. If the chat opens before the bridge is ready, a live sync or reconnect confirmation retries the read for the visible chat. Cached syncs do not send read requests.

See Android's [notification bridging documentation](https://developer.android.com/training/wearables/notifications/bridger) for the manifest control. Blocking notification mirroring applies to this app's phone notifications as a whole; it does not alter notifications from other phone apps.

## Chat identity and presentation

Different native phone conversation IDs may share a recipient. Automatic address matching only migrates watch placeholders and other non-backend IDs. An explicit canonical ID returned by a phone history/send response can still migrate a chat. A periodic directory refresh must not rename an open native conversation, combine native histories, or redirect its outbox just because an address matches.

The list, Find a chat, tile, and complication share a display directory with one row per normalized direct-message address (or single participant when the address is absent). Prefer an unread conversation, then the newest one, with a stable ID tie-break. Groups, unknown addresses, and people with matching names but different addresses remain separate. The full directory and cached histories retain their original IDs.

Chat rows have stable keys, including the footer. The screen follows initial history once and genuinely appended newer messages only while already near the latest messages and not scrolling. Refreshes, delivery status changes, and replacement send IDs do not force a scroll. Phone-lock state remains in the wire/cache model but no longer creates UI notices.

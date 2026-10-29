# OpenBubbles Wear 2.1.3 companion update

This update replaces only the phone and watch companion APKs. It is intended for users already
running the 2.1.2 companions with Morphe patch 1.2.0. Keep OpenBubbles and the patch installed as
they are; no OpenBubbles APK replacement or repatch is needed.

Install both companions with the same signing key used for the existing apps:

```bash
adb -s PHONE_SERIAL install -r OpenBubblesWear-Phone-2.1.3.apk
adb -s WATCH_SERIAL install -r OpenBubblesWear-Watch-2.1.3.apk
```

Do not uninstall either companion. If Android reports a signature mismatch, use APKs signed with
the same key as the installed companions. Users with a Morphe patch older than 1.2.0 should follow
the previous [full setup guide](INSTALL-2.1.2.md) first.

After updating, open the phone companion and check its connection status. On the watch, open a
conversation to see the available **Sent**, **Delivered**, or **Read** status. A backend status is
shown when supplied; unknown status remains unconfirmed.

This update does not address Apple rate limits. A reported case where failed group-message bubbles
are absent on the other device after refresh remains unresolved. Direct messages sent through
Google Messages/RCS have not been tested for sync by this companion path.

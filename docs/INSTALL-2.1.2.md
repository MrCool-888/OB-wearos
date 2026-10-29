# OpenBubbles Wear 2.1.2 — patched phone + watch testing build

This testing set pairs the phone and watch companions 2.1.2 (version code 15) with Morphe patch
1.2.0 for native OpenBubbles 2.0.0 (version code 20002289). OpenBubbles remains responsible for
account setup, encryption, and message delivery.

## Update an existing patched installation

For an existing patched OpenBubbles 2.0.0 installation made with this project's signing key,
update it in place with `install -r`. Keep the same APK format you already use: install either
the universal APK or the complete four-APK split set. Do not uninstall the app or mix formats.
Install the patched OpenBubbles, phone companion, and watch companion updates from the same
2.1.2 bundle:

```powershell
# From dist/OpenBubblesWear-2.1.2
# Choose one OpenBubbles format:
adb -s PHONE_SERIAL install -r phone/openbubbles-wear-patched-universal.apk
# OR all four split APKs together:
adb -s PHONE_SERIAL install-multiple -r phone/openbubbles-wear-patched-base.apk phone/config.arm64_v8a.apk phone/config.en.apk phone/config.mdpi.apk

# Update the two companion apps as well:
adb -s PHONE_SERIAL install -r OpenBubblesWear-Phone-2.1.2.apk
adb -s WATCH_SERIAL install -r OpenBubblesWear-Watch-2.1.2.apk
```

These updates require the same signing key as the installed patched apps. The version, package,
and native OpenBubbles target remain unchanged. If Android reports a signature mismatch, stop;
do not uninstall as a troubleshooting step. This update path does not replace a stock-signed
Play Store OpenBubbles installation. OpenBubbles 2.0 removed its local Messages Backups option,
and no complete backup/restore path for replacing stock is verified. Preserve the existing stock
installation and use a separate test device for the patched build.

## Bundle contents and requirements

Use `dist/OpenBubblesWear-2.1.2/` or the matching `OpenBubblesWear-2.1.2-verified.zip`:

- `phone/`: patched OpenBubbles base plus ARM64, English, and MDPI splits; also includes the
  universal one-file APK. Install either the universal APK or all four split APKs.
- `OpenBubblesWear-Phone-2.1.2.apk` and `OpenBubblesWear-Watch-2.1.2.apk`.
- `OpenBubblesWear-Morphe-1.2.0.mpp`, checksums, and install instructions.

The patch targets only package `com.openbubbles.messaging`, native OpenBubbles 2.0.0 / code
20002289. The patched app remains version 2.0.0 / code 20002289. The companions are 2.1.2 / code
15. Use an ARM64 phone and Wear OS 3 or newer watch paired through the Wear OS app. Root, Shizuku,
and accessibility access are not required for the direct bridge.

## First launch and checks

1. Open OpenBubbles and confirm its existing account/backend is working.
2. In OpenBubbles, open **Settings → Morphe patches** and enable **Wear companion bridge**.
   This controls direct bridge use at runtime. The setting is enforced for service requests and
   direct media access.
3. Open the phone companion, keep **Relay to watch** enabled, and grant notification access for
   live alerts. Its diagnostics should report **Connected · Ready** when the patch service is
   reachable. The companion no longer has a patches-management menu.
4. Open the watch companion and choose **Find chats in OpenBubbles**. The watch loads the paginated
   directory (40 rows per page) until it has the available entries, up to its 5,000-chat cap.
5. Check a quiet or older chat and refresh it to load recent backend history. Phone-side **Find
   chats in OpenBubbles** drains the discovered count; later syncs remain bounded.
6. Lock the phone and test a send from an existing chat. An uncertain send should not be repeated
   automatically; check OpenBubbles before manually retrying.

Chat discovery uses pages of 40. Later phone syncs and the phone/watch recent-history and media
caches remain bounded (40 chats with detailed history plus 60 additional chat entries; watch
directory maximum 5,000). This is not an unlimited database browser. Direct history can include
text hidden by notification-preview settings. Direct media reads only files already downloaded
inside OpenBubbles; it cannot fetch missing attachments.

Automated checks and package verification do not prove real Apple delivery or physical Wear Data
Layer behavior. See [release notes](RELEASE-2.1.2.md) for the validation record and remaining
device checks.

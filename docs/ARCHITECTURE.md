# Architecture and repository split

This checkout contains the Wear OS app, its protocol module, and a local notification test utility.

- `wear/`: Wear OS app. Version 3.0.0, version code 22, package/application ID `com.openbubbles.messaging`.
- `protocol/`: version 4 Data Layer messages and models shared between phone and watch.
- `testnotifier/`: a developer utility for generating sample OpenBubbles notifications; it is not needed to build the watch app.

The phone integration is maintained separately in the private [mrcool-patches repository](https://github.com/MrCool-888/mrcool-patches). That repository owns the Morphe patch and embedded phone-side bridge. This checkout has no phone app module, Morphe project, stock APK inputs, or phone packaging step.

## Shared wire contract

The `protocol/` module is intentionally duplicated byte-for-byte in the phone patch repository so each repository can build independently. Both copies define the protocol v4 wire contract. When changing a protocol model, path, encoding, or behavior, make the matching change in both repositories and run both protocol test suites before releasing either side.

## Identity and signing

The watch application ID and release signing key must match the companion identity expected by the private phone patch. Preserve the existing key for updates. CI's default artifact is an unsigned debug APK; the signed release job can run only by explicitly requesting it from `workflow_dispatch` and supplying the existing signing secrets.

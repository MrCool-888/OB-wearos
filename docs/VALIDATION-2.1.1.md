# 2.1.1 pre-test validation — 2026-10-28

The verified local testing bundle is `dist/OpenBubblesWear-2.1.1/`.
Use its `INSTALL.md` and `SHA256SUMS.txt`. It contains rebuilt, release-signed phone/watch
companions and the verified ARM64 patched OpenBubbles installation options.

## Results

- 222 tests passed, zero failures: protocol 30, phone 90, watch 76, patch 26.
- One additional patch test was skipped because this Windows host cannot create symlinks.
- Both companion release builds and release lint passed (zero errors; 37 phone / 35 watch warnings).
- Morphe patch assembly, packaging fail-closed self-checks, and `verifyInstallableApks` passed.
- A fresh application of patch 1.1.0 to the pinned stock APK passed validation. All six output
  DEX payloads match both the signed base and the standalone APK in the local bundle.
- All seven APKs passed signature and 16 KB ZIP alignment verification. Both companions have
  application ID `com.openbubbles.wear`, version 2.1.1 / code 14, and the pinned release certificate.
- Patched OpenBubbles remains `com.openbubbles.messaging`, 2.0.0 / code 20002289. The standalone
  APK has no split dependency and contains all nine ARM64 libraries, matching the ABI split.
- All eight APK/patch checksums resolve correctly from the bundle root and match.

## Corrections made

- Watch notification posting now explicitly handles revoked permission and only records an
  alert as posted after posting succeeds. A regression test covers revocation during posting.
- The JVM patch tests load Android AAR classes, prioritize the declared Guava dependency over
  copies embedded in input jars, and instrument DEX-derived fixtures to rebuild JVM stack frames.
- The phone companion is staged at the path used by the install guide. Bundle checksums now
  include correct relative paths, and installation step numbering is corrected.

## Limits

No phone or watch was connected. Signed-in backend startup, real delivery, secure-lock sends,
process death/rebinding, incoming alerts, media transfer, and physical Wear Data Layer transport
still require the device checklist in `INSTALL-2.1.1.md`. Automated checks cannot guarantee these.
OpenBubbles 2.0 removed the legacy local Messages Backups option. Earlier installation guidance
incorrectly recommended that menu and has been corrected. No complete, data-preserving 2.0
migration to the differently signed patch has been verified. Keep stock installed and test the
direct patch on a spare device; the separate companions can use stock in notification mode.

This report covers the files in the verified bundle, identified by `SHA256SUMS.txt`.
The verified standalone hash is `0b8f3e600e2c4f80eac08f1a1c14c30c3031b761ea28486f131039ca4ad3617c`.
The original GitHub 2.1.1 standalone had a different hash
(`38312af3b58c1976090610e9b7ec8bf8fe4faf55a741b5faa6025d44a0a4ac57`) and was not certified
by these checks. Use the verified bundle or matching updated individual release assets.

The GitHub Actions release workflow was not run. Its optional Morphe packaging path still uses
Windows executable names in the Gradle helpers despite an Ubuntu runner; its asset staging also
does not generate the standalone APK. These are release-pipeline gaps, separate from this verified
local testing bundle. Morphe Manager import on Android was not tested.

Evidence: Gradle test/lint reports under each module's `build/`, plus logs, fresh patch output,
and `apk-verification.json` in `work/validation-2.1.1/`.

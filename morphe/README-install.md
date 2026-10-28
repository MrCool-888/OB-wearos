# Release-signed OpenBubbles phone APK set

This packaging pipeline keeps `com.openbubbles.messaging` and uses the **existing
repository-root `keystore.properties` and release keystore**, just like the phone
and watch companions. It never creates/replaces a key, falls back to debug
signing, installs, uninstalls, wipes, exports app data, or bypasses signatures.
Keep that same keystore and local configuration for future patched updates.
Do not commit either file or share signing passwords.

## Why four APKs, not one

Ordinary `aapt2` inspection of both stock and patched manifests shows
`requiredSplitTypes="base__abi,base__density"` and
`com.android.vending.splits.required=true`. The base has `extractNativeLibs=false`.
Merging only ARM64 libraries would leave the density requirement/resources
unresolved. Packaging therefore preserves the manifests and signs this complete
supplied ARM64 set:

- `openbubbles-wear-patched-base.apk` — patched base; **not standalone**.
- `config.arm64_v8a.apk` — all nine original, uncompressed ARM64 native libraries.
- `config.en.apk` — supplied English resources.
- `config.mdpi.apk` — supplied density resources, required alongside the ABI split.

REA archive inventory independently identifies these four XAPK members and their
hashes (Evidence `ev_1e100b91b9db52128f57219a11f923a780ebe8a7f712354cc298c09a466eff8b`).
That is static archive evidence, not an install/runtime test. Split declarations
above are ordinary `aapt2` findings, not REA Android analysis.

This is the supplied English/density configuration, not a universal/multilingual
APK. It requires an ARM64 phone running Android 12L / API 32 or newer. This APK set
is for the **phone**, not the watch.

## Build (PowerShell, repository root)

Use JDK 17 for Gradle, the Android SDK with platform 35 and build-tools 35.0.0,
and JDK 21+ for Morphe's launcher. For this workstation:

```powershell
$tools = 'C:\Users\Landon\Documents\Codex\2026-10-12\new-chat\work\tools'
$env:JAVA_HOME = "$tools\jdk\jdk-17.0.20.1+1"
$env:ANDROID_HOME = "$tools\android\sdk"
morphe\gradlew.bat -p morphe packageInstallableApks verifyInstallableApks '-PmorpheJava=C:\Program Files\Android\Android Studio\jbr'
```

Inputs (all SHA-256 pinned):

- `work/com.openbubbles.messaging.apk` — stock native 2.0.0 base.
- `work/config.arm64_v8a.apk` — matching supplied native split.
- `OpenBubbles_2.0.0_APKPure.xapk` — matching container; English and density splits
  are extracted only to `morphe/build/packaging-inputs`.

Optional input relocation properties are `-PinputApk=...`,
`-PinputArm64Split=...`, and `-PinputXapk=...`; they do **not** relax hash checks.
Relative paths resolve from `morphe/`. Signing paths resolve from the repository
root, matching the companion modules.

Outputs go only to `morphe/build/installable/`: the four APKs, `SHA256SUMS`, and
`INSTALL.txt` with the public signing certificate fingerprint. The task explicitly
re-signs the three stock configuration splits with the same release key as the
patched base. It refuses to sign an unchanged stock base or one missing the
injected bridge service. Inputs are never modified.

Every APK is aligned before signing, with 16 KiB shared-library ZIP alignment.
Verification checks package/version/split identities, Android signature validity,
the exact configured release certificate, alignment, unchanged ZIP payloads, and
all native-library contents. ZIP alignment does not change ELF load-segment
alignment or prove that stock libraries work on a particular device/page size.

Checks that do not rebuild/patch/sign the app:

```powershell
morphe\gradlew.bat -p morphe packagingSelfTest verifyPackagingInputs verifySigningKey
morphe\gradlew.bat -p morphe verifyInstallableApks
```

`packagingSelfTest` also runs with `check`. It asserts correct/incorrect/missing
input hashes, required native-library presence, rejection of missing signing
configuration, and rejection of absent/wrong signer fingerprints.
`verifyInstallableApks` requires the current patched APK and pinned source inputs;
it does not implicitly rebuild a stale output set.

## Install yourself, only when ready

**The stock app has a different signing key. Android will not accept this as a
stock update.** If stock is still installed, stop and handle that yourself first.
Uninstalling stock deletes its local data and may require OpenBubbles setup again.
Nothing here migrates or preserves that data. No build task uninstalls it for you.

After you have handled the stock installation, run this from the repository root
with your actual phone serial (check `adb devices`):

```powershell
adb -s PHONE_SERIAL install-multiple -r morphe/build/installable/openbubbles-wear-patched-base.apk morphe/build/installable/config.arm64_v8a.apk morphe/build/installable/config.en.apk morphe/build/installable/config.mdpi.apk
```

Never install just the base or mix these with stock-signed splits. Future patched
updates use the **same key** and the same four-APK `install-multiple -r` command;
`-r` keeps an existing same-signed patched install's data, subject to Android's
normal update checks. There is no downgrade or signature-bypass flag.

Build/install the matching release-signed phone bridge and watch companion
separately on their respective devices. Do not mix debug, older, or differently
signed companion builds; their authenticated IPC/package checks must match the
patched build. Static packaging verification does not establish on-device
messaging, setup/login, or phone-to-watch behavior.

# OpenBubbles Wear 2.1.3 release notes

Companion-only update: phone and watch apps are version **2.1.3** (version code **16**). Morphe
patch **1.2.0** and the patched OpenBubbles app are unchanged.

## Changes

- Parse known outgoing delivery statuses from bridge JSON and preserve backend status on the watch.
- Show **Sent**, **Delivered**, or **Read** when the backend provides that status. Unknown backend
  status stays unconfirmed; the watch no longer assumes every outgoing message was sent.
- Retain a local failed status when matching history omits status; an explicit backend status takes
  precedence.

## Update

Install both companion APKs in place using the same signing key. Existing 2.1.2 users leave
OpenBubbles and Morphe 1.2.0 installed as-is; no OpenBubbles replacement or repatch is needed. See
[Install 2.1.3](INSTALL-2.1.3.md). Users on a patch older than 1.2.0 should follow the [2.1.2 full
setup guide](INSTALL-2.1.2.md).

## Validation and limitations

- **212 tests passed, zero failures:** protocol 32, phone 97, watch 83. Regressions cover failed
  group history through the phone cache and wire encoding, asynchronous send failure on refresh,
  backend status preservation, unknown status, and failed local-attempt reconciliation.
- Both signed release builds and release lint passed. APK package/version, signatures, matching
  release certificates, and ZIP alignment were checked. No hardware validation is claimed.
- Apple rate limits are not addressed.
- A reported issue where failed group-message bubbles are absent on the opposite device even after
  refresh remains unresolved; this release does not claim to fix its root cause.
- Direct messages sent through Google Messages/RCS have not been tested for companion sync.

# 2.1.5 validation

Companions 2.1.5/code18; Morphe 1.2.2; native OB 2.0.0/code20002289.

## Reproduced failure

The affected group used an opaque 72-character hexadecimal ID. The old validator accepted
legacy IDs and UUIDs but rejected this native format. A same-key diagnostic instrumentation
made a read-only `thread` request for that cached conversation: response received, `ok=false`,
invalid ID, zero history rows. Its notification cache contained incoming messages only.

The diagnostic reported ID structure and counts, not IDs, message text, contacts, or credentials.

## Corrected behavior

- Exact 72-hex IDs resolve through the existing backend ID lookup. Invalid lengths, characters,
  control characters, and address fallback remain rejected. Native IDs cannot become recipient
  phone numbers during normalization.
- ObjectBox populates saved entity fields before OB initializes its reactive flows. Raw query
  results therefore need their persisted delivery fields and participant relation rather than
  default flow values. Delivery precedence follows the pinned native app: read, delivered,
  saved error, sending service, sent.
- Native Android synthetic-entity checks passed for failed/delivered/read/sending/sent states
  and for an uninitialized group with two persisted participant handles. Those fixtures were
  never saved to the database. The DEX-derived JVM classpath cannot reliably instantiate these
  native constructors, so these checks ran against the real Android classes instead.
- The same real-group request then returned `ok=true`, correct group metadata, 79 messages,
  32 outgoing, and 6 failed. After same-key in-place updates of OB and both companions, the user
  confirmed that the failed messages appear on the watch with failure status.
- The temporary diagnostic APK was removed. No app-data clear, uninstall of production apps,
  message send, automatic resend, or account setup change was performed.

## Automated checks

256 passed, zero failed, one Windows symlink test skipped: protocol36, phone99, watch86,
patch35 passed. Both signed release builds and release lint passed. Fresh Morphe application
and complete signed APK-set verification passed. Universal metadata, signature, alignment,
and DEX/native-library payloads are checked before staging the release.

The live result establishes history synchronization for the affected group. It does not establish
successful Apple delivery or DM/RCS behavior while the account is rate limited.

# Unsend and group artwork prerelease

Watch 3.2.0-dev.1 pairs with phone patches v1.3.3-dev.1 for native OpenBubbles 2.0.0 build 20002293 ARM64.

The hold menu offers Unsend for eligible outgoing iMessages within the native two-minute window. The phone checks the exact conversation, message, sender, age, deletion state and remaining native content parts. Each visible part uses Message.unsend; requests are deduplicated and never automatically retried. Failed or unconfirmed requests retain the bubble and report an error. Confirmed retractions become tombstones and cannot restore a cached attachment during a history merge.

Group artwork retains custom chat pictures. Otherwise, member contact photos take precedence over shared profile pictures; photos are prioritized in a cluster of up to four circles, with a count for larger groups and individual initials for missing photos. Internal member photo sources are removed before serialization to the watch. The existing bounded, content-addressed avatar cache and media transport are reused.

The Messages heading uses the same titleMedium typography and heading accessibility semantics, without ListHeader’s 48dp minimum height. The home list starts at its first item and uses explicit padding rather than adding automatic centering space above the heading.

Automated validation covers old wire defaults, timing boundaries, ownership, failed versus confirmed actions, retracted attachment merging, native unsend calls, group artwork and private-source stripping. Physical recipient-side retraction remains a live messaging check; no real message is sent or unsent during build validation.

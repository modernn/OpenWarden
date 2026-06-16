# ADR-010: No OS fork; stock Android default, GrapheneOS supported, LineageOS overlay v3+

Status: Accepted
Date: 2026-06-15

## Context

User asked: fork an OS for OpenWarden to enable system-level integration on broad device support. Research at [`docs/BROWSER_AND_OS.md`](../BROWSER_AND_OS.md) §9-14 + [`docs/LINEAGEOS_OVERLAY.md`](../LINEAGEOS_OVERLAY.md).

## Options

1. **Fork AOSP / GrapheneOS / CalyxOS.** 2+ FTE permanently, $5-10k/mo CI, fails SIMPLIFY 5-year test.
2. **LineageOS overlay** (flashable zip on stock LineageOS). 4-6 FTE-months upfront + 0.15 FTE ongoing. Adoption ceiling <5%.
3. **No OS work.** Stock Android primary; GrapheneOS recommended alt where users want; LineageOS overlay deferred to v3+.

## Decision

Adopt **option 3 for v1/v2**; **option 2 conditionally at v3** if grant-funded.

**v1/v2 stance:**
- Primary target: stock Android, broad device support per ADR-001
- Recommended alt: GrapheneOS on Pixel (gets Vanadium browser + Verified Boot + hardened malloc free)
- Document: "Best on stock Pixel. Better on GrapheneOS-Pixel. Other Androids supported per [`docs/ANDROID_COMPAT.md`](../ANDROID_COMPAT.md)."

**v3+ stance:**
- LineageOS overlay PoC for top 3-5 devices if grants land
- Flashable zip distribution model (Option C from LineageOS research)
- Genuinely fork-worthy capability gained: Play Services Help WebView intercept at framework level (closes P9 at OS layer)

**Never:**
- Full AOSP fork
- Magisk module (requires root, defeats security goals)

## Consequences

**Good:**
- v1 ships fast. Bus factor manageable.
- Users who *want* OS-level hardening (GrapheneOS) get the benefit automatically.
- v3 overlay option preserved without v1 commitment.

**Bad:**
- P9 Play Services Help WebView leak only partially mitigated v1/v2 (DNS-block of help endpoints; framework-level intercept requires overlay).
- GrapheneOS user base small; documentation effort there has low ROI.
- LineageOS overlay v3+ depends on grant funding materializing.

## Cross-refs

- [`docs/BROWSER_AND_OS.md`](../BROWSER_AND_OS.md)
- [`docs/LINEAGEOS_OVERLAY.md`](../LINEAGEOS_OVERLAY.md)
- [`docs/ANDROID_COMPAT.md`](../ANDROID_COMPAT.md)
- [`docs/SIMPLIFY.md`](../SIMPLIFY.md) 5-year test

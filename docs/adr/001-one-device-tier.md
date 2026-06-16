# ADR-001: One device tier, not one device family

Status: Accepted
Date: 2026-06-15

## Context

`docs/SIMPLIFY.md` §3 originally said "One target device family — Pixel 7." Broad-Android compatibility research ([`docs/ANDROID_COMPAT.md`](../ANDROID_COMPAT.md)) revealed that limiting OpenWarden to Pixel-only sharply limits adoption and excludes most kids on Samsung / OnePlus / Xiaomi / Motorola hardware. User explicitly asked for broad-Android support.

## Options

1. **Pixel-only forever.** Simplest, highest defense quality (StrongBox, Verified Boot Google chain). Smallest addressable market.
2. **Tier system: Pixel = Tier 1, others = Tier 2/3.** Broader reach, weaker defenses on Tier 2/3.
3. **Equal support across all OEMs.** Pretend differences don't exist. Doesn't survive contact w/ reality.

## Decision

Adopt **option 2: tier system**.

- **Tier 1 (full support):** Pixel 6+, Android 14+ stock. StrongBox + AVB Google chain + tight `DISALLOW_*` enforcement.
- **Tier 2 (works w/ documented caveats):** Samsung Galaxy S22+ / A55+ / Note, OnePlus 11+, Motorola Edge 50+, Nothing Phone 2+. DPC works, attestation per-OEM root, FGS watchdog more aggressive.
- **Tier 3 (best-effort, community-supported):** any Android 13+ w/ DPC support. No warranty on hardened attestation.

Amend SIMPLIFY.md §3 from "one device family" to "one device tier."

## Consequences

**Good:**
- Reach grows ~5× (Pixel ~3% global Android share → Tier 1+2 ~25%).
- Aligns w/ open-source ethos: works on what people already have.
- Maintenance cost mostly fixed (DPC APIs same across OEMs); test matrix grows.

**Bad:**
- Per-OEM provisioning script variants needed (Samsung OOBE differs from Pixel).
- Attestation root allowlist becomes a config file (`oem_roots.json`).
- StrongBox availability ~15-20% of Android market → CRYPTO.md needs TEE-fallback for non-StrongBox devices.
- Bench test cost rises: ~$1k for Pixel 7a + Samsung A55 + 1-2 others.
- Marketing complexity: "Best on Pixel" vs "works on most Androids" requires nuance.

## Cross-refs

- [`docs/ANDROID_COMPAT.md`](../ANDROID_COMPAT.md)
- [`docs/CRYPTO.md`](../CRYPTO.md) §3 (StrongBox fallback)
- [`docs/PROVISIONING_V2.md`](../PROVISIONING_V2.md) (per-OEM scripts)
- [`docs/SIMPLIFY.md`](../SIMPLIFY.md) §3 (amended)

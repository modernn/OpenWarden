# Architecture Decision Records

This directory holds OpenWarden's ADRs. Each ADR is a single decision: context, options considered, decision, consequences.

## Format

```
# ADR-NNN: Title
Status: Accepted | Superseded by ADR-MMM | Proposed
Date: YYYY-MM-DD

## Context
What problem is being solved.

## Options
List of options considered + trade-offs.

## Decision
What we picked + why.

## Consequences
What this means going forward (good + bad).
```

## Index

| # | Status | Title |
|---|---|---|
| [001](001-one-device-tier.md) | Accepted (amended by 023) | One device tier, not one device family |
| [002](002-ios-parent-v1.md) | Accepted | iOS parent app in v1 |
| [003](003-dns-video-tracking.md) | Accepted | DNS-based video tracking, no titles |
| [004](004-multi-child-schema-v1.md) | Accepted | Multi-child data model v1, UI v2 |
| [005](005-generalize-under-18.md) | Accepted | Generalize from "Oliver" to "kids under 18" |
| [006](006-privacy-no-server.md) | Accepted | Privacy via no-server architecture |
| [007](007-default-blocklist-d1.md) | Accepted | Default-blocklist Discord/Roblox/Snap (D1 attack) |
| [008](008-no-default-sms-dialer-replacement.md) | Accepted | No default SMS/Phone replacement; CallScreening v2 |
| [009](009-browser-strategy.md) | Accepted | Chrome enterprise policy v1; Vanadium wrapper v2 |
| [010](010-no-os-fork.md) | Accepted | No OS fork; stock + GrapheneOS supported; LineageOS overlay v3+ |
| [011](011-kmp-not-flutter.md) | Accepted | Kotlin Multiplatform for parent app, not Flutter |
| [012](012-rename-openwarden.md) | Accepted | Project name = OpenWarden (renamed from Kidlock) |
| [013](013-decommission-trigger-authority.md) | Proposed | Decommission/self-wipe trigger authority (red-team R1) |
| [014](014-on-device-ai-content-boundary.md) | Proposed | On-device AI content boundary, no content monitoring (red-team C1/C2) |
| [015](015-event-log-crypto-primitives.md) | Proposed | Event-log crypto primitives normative: sealed-box only (red-team SB1/nonce/SG1) |
| [016](016-fail-closed-dns-floor.md) | Accepted | Fail-closed DNS floor (red-team K3) |
| [017](017-replay-rollback-resistance.md) | Proposed | Replay/rollback resistance + JCS integer bound (red-team K1/JC1) |
| [018](018-version-semantics-and-dynamic-roadmap.md) | Proposed | Version semantics (v0.x road → v1.0 public release), dynamic protected roadmap, tech-lead mode |
| [019](019-canonical-signing-invariant.md) | Accepted | Canonical signing — sign-and-transmit-exact-bytes, verify over received bytes |
| [020](020-failclosed-dayone-restrictions.md) | Accepted (amended by 022) | Day-One restriction baseline applied fail-closed (verify-or-throw) + FRP |
| [021](021-policy-watchdog-reassert-triggers.md) | Accepted | Policy watchdog re-asserts on boot, connectivity, and a periodic timer |
| [022](022-allowlist-deny-by-default-profile-escape.md) | Accepted | Allowlist-only launch deny-by-default + fail-closed; baseline blocks profile escapes (red-team B1) |
| [023](023-enforcement-floor-tiers.md) | Accepted | Device tiers defined by enforcement floor, not just attestation (amends ADR-001) |
| [024](024-no-contact-ratchet-strict-baseline.md) | Accepted | Progressive no-contact ratchet to strict baseline + signed Heartbeat (implements ADR-017 §7) |
| [025](025-pairing-handshake-direction-attestation-sas.md) | Accepted | Pairing handshake ratified — parent-displays-QR + StrongBox attestation + six-emoji SAS (ratifies PROTOCOL §7) |
| [026](026-top-oem-release-scope.md) | Accepted | Commit Pixel + Samsung + OnePlus to the v1.0 release at the disclosed-gap floor (amends ADR-001/023; ADR-025 amended by ADR-029) |
| [027](027-provisioning-distribution-model.md) | Accepted | QR-OOBE Device-Owner provisioning is the primary consumer path; Play-distributed; ADB stays power/bench; weak no-DO mode rejected (amends PROVISIONING_V2) |
| [029](029-tier2-attestation-posture.md) | Accepted | Tier-2 attestation posture — accept allow-listed OEM-root + TEE-level attestation for committed Samsung/OnePlus, disclosed downgrade, four-key SAS still mandatory (amends ADR-025; reconciles CRYPTO §3/§10) |

<!-- 028 is reserved for the in-flight Bluetooth-transport ADR (PR #70); the table fills in when it merges. -->

## When to write an ADR

- Architectural pivot
- Trade-off requires written rationale
- Decision affects multiple modules
- Decision contradicts an earlier doc (must supersede)
- A first-principles re-derivation lands on a different answer than research suggested

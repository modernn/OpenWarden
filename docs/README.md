# OpenWarden Docs

Canonical specs + design docs. Read these before writing code that touches the relevant area.

## Tier system

- **Tier 1 (v1 canon)** = what we're building now
- **Tier 2 (v2 frozen-design)** = design locked, implementation deferred to v2 milestone
- **Tier 3+ (v3+ frozen-design)** = locked for later
- **Research** = raw input reports, not canonical

See [`SIMPLIFY.md`](SIMPLIFY.md) for the tier policy + scope discipline.

## Index

### Tier 1 — v1 canon

Implementation reference. Build against these.

| Doc | Topic |
|---|---|
| [PROTOCOL.md](PROTOCOL.md) | Signed log wire format, sync state machine, JCS canonicalization |
| [CRYPTO.md](CRYPTO.md) | Ed25519/X25519 keys, BIP39 derivation, StrongBox, sealed-box envelope |
| [PROVISIONING_V2.md](PROVISIONING_V2.md) | S0-S10 state machine, ADB commands, atomic completion, emulator path |
| [PARENT_KMP_STRUCTURE.md](PARENT_KMP_STRUCTURE.md) | KMP module layout, libsodium, SKIE, F-Droid build |
| [DNS_RESOLVER.md](DNS_RESOLVER.md) | Local DoT resolver, video-CDN classifier, per-app caps |
| [DNS_FILTER.md](DNS_FILTER.md) | Cloudflare 1.1.1.3, Chrome enterprise policy |
| [RECOVERY.md](RECOVERY.md) | BIP39 phrase, 7-day time-lock, decommission |
| [KID_TRANSPARENCY.md](KID_TRANSPARENCY.md) | Kid-facing "what does OpenWarden see?" screen |
| [ONBOARDING.md](ONBOARDING.md) | 5-step parent install journey + troubleshooting |
| [TESTING.md](TESTING.md) | Test pyramid, CI, test vectors, snapshot tests |
| [AI_DEV_PIPELINE.md](AI_DEV_PIPELINE.md) | Claude Code + Codex automated dev loop |
| [ARCHITECTURE.md](../ARCHITECTURE.md) | Top-level architecture overview |
| [ATTACKS.md](ATTACKS.md) | Threat catalog (technical + behavioral) |
| [DEFENSES.md](DEFENSES.md) | 30-defense ship list + cross-ref to attacks |
| [SECURITY.md](SECURITY.md) | Threat model + defense table |
| [SIMPLIFY.md](SIMPLIFY.md) | Tier system + anti-scope-creep rules |

### Tier 2+ — frozen-design

Design locked. Don't implement without ADR amendment.

| Doc | Topic | Tier |
|---|---|---|
| [AI_IMPLEMENTATION.md](AI_IMPLEMENTATION.md) | Falconsai NSFW + Gemma Nano practical impl | v2 image / v3 text |
| [LOCAL_AI.md](LOCAL_AI.md) | Local AI policy (opt-in, on-device only) | v2+ |
| [FAMILY_MODEL.md](FAMILY_MODEL.md) | Multi-parent + multi-child data model | v1 schema, v2 UI |
| [GRADUATED_PRIVILEGES.md](GRADUATED_PRIVILEGES.md) | L1-L5 trust levels + visibility slider | v2 |
| [GEOFENCING.md](GEOFENCING.md) | Home/school geofence, school-hours leak alerts | v2 |
| [TIME_BANK.md](TIME_BANK.md) | Earned screen-time credits | v3 |
| [TELEPHONY.md](TELEPHONY.md) | CallScreeningService + contact allowlist | v2 |
| [BROWSER_AND_OS.md](BROWSER_AND_OS.md) | Browser strategy + OS fork analysis | v1 stance |
| [LINEAGEOS_OVERLAY.md](LINEAGEOS_OVERLAY.md) | LineageOS overlay PoC analysis | v3+ if funded |
| [ANDROID_COMPAT.md](ANDROID_COMPAT.md) | Per-OEM DPC quirks + Tier 1/2/3 device matrix | v2 expansion |
| [STORE_AND_FORWARD.md](STORE_AND_FORWARD.md) | Iroh transport + signed log sync | v2 |
| [OTA.md](OTA.md) | Self-update + signing key rotation | v2 |
| [I18N.md](I18N.md) | Translation strategy | v2 expansion |
| [NOTIFICATIONS.md](NOTIFICATIONS.md) | Kid + parent notification UX | v1 base / v2 polish |
| [MIGRATION.md](MIGRATION.md) | Device transition + decommission flows | v1 + v2 |
| [PERFORMANCE.md](PERFORMANCE.md) | Battery + bandwidth + latency budgets | v1 + v2 + v3 |
| [PRIVACY_LEGAL.md](PRIVACY_LEGAL.md) | COPPA/GDPR/CCPA stance + ADRs | v1 |
| [PARENT_AS_ADVERSARY.md](PARENT_AS_ADVERSARY.md) | Abusive-parent threat model + protections | v1 stance |
| [GOVERNANCE.md](GOVERNANCE.md) | BDFL → council evolution, PR process | ongoing |
| [COMMUNITY.md](COMMUNITY.md) | Marketing, positioning, contributor onboarding | ongoing |
| [DISTRIBUTION.md](DISTRIBUTION.md) | F-Droid, TestFlight, GitHub Releases, signing | v1 + v2 |
| [DESIGN_PARADIGMS.md](DESIGN_PARADIGMS.md) | UI rules + code conventions for contributors | v1 |
| [UX_PATTERNS.md](UX_PATTERNS.md) | Kid + parent + co-parent UX patterns | v1 |
| [FUNDING.md](FUNDING.md) | Grant strategy, treasury, no subscription | ongoing |
| [MISC.md](MISC.md) | App-permission audit, calendar import, beta program, kid-as-dev | v2-v3 |

### ADRs

[`adr/`](adr/) — Architecture Decision Records. Read first when proposing a direction change.

### Research

[`research/`](research/) — raw research reports. Input, not canon. Useful for context but don't cite as authority.

## Doc relationships

```
                README.md (front door)
                     │
                ARCHITECTURE.md (top-level overview)
                     │
        ┌────────────┼────────────┬────────────┐
        ▼            ▼            ▼            ▼
   PROTOCOL.md   CRYPTO.md   PROVISIONING_V2  PARENT_KMP_STRUCTURE
        │            │            │            │
        ▼            ▼            ▼            ▼
   test vectors  ATTACKS+DEFENSES  ONBOARDING  TESTING+AI_DEV_PIPELINE
```

When in doubt: start from ARCHITECTURE.md and follow links.

# ADR-006: Privacy via no-server architecture

Status: Accepted
Date: 2026-06-15

## Context

User asked for strong privacy + minimal regulatory exposure. Modern parental control products are SaaS w/ cloud data collection → COPPA / GDPR / CCPA / state-PL exposure. OpenWarden's "local-only" approach is a deliberate architectural choice with regulatory consequences.

## Options

1. **SaaS w/ optional self-host.** Like Bark/Qustodio. Maximum regulatory exposure. Highest sustainability bar (server costs).
2. **Optional cloud sync for power users.** Partial exposure. Architectural compromise.
3. **Strict no-server.** Maximum privacy. Zero regulatory exposure for app itself. Some UX limits (iOS push, multi-device sync friction).

## Decision

Adopt **option 3: strict no-server**.

OpenWarden operates exclusively between parent device(s) and child device(s). No central data collection. No telemetry. No analytics. No cloud sync of policy or events.

**Optional convenience integrations (clearly marked, opt-in):**
- Tailscale free tier for NAT-traversed P2P transport (v2)
- ntfy.sh as content-free wake-up doorbell for iOS push (v2)
- Cloudflare 1.1.1.3 family DNS as upstream resolver (always opt-out-able)

**Regulatory consequences:**
- **COPPA:** does not apply (OpenWarden is not an "operator of an online service") ([`docs/PRIVACY_LEGAL.md`](../PRIVACY_LEGAL.md) §1)
- **GDPR:** household exception applies (Article 2(2)(c)) for app itself ([`docs/PRIVACY_LEGAL.md`](../PRIVACY_LEGAL.md) §2)
- **CCPA / state-PL:** no covered business activity (no revenue, no central processing) ([`docs/PRIVACY_LEGAL.md`](../PRIVACY_LEGAL.md) §3-4)
- **Stalkerware policies:** structural alignment with Coalition Against Stalkerware criteria ([`docs/PARENT_AS_ADVERSARY.md`](../PARENT_AS_ADVERSARY.md))

## Consequences

**Good:**
- Maximum privacy as a feature.
- Regulatory simplicity → no compliance overhead, no DPO required, no privacy-policy theater.
- Project sustainability: nothing to run, only develop. Grants cover dev; nothing recurring.
- Differentiates strongly vs Family Link, Bark, Qustodio.

**Bad:**
- iOS background sync limited (no APNs push v1; ntfy.sh wake-up doorbell in v2 is the workaround).
- Multi-device parent sync requires P2P transport (Iroh v2 or LAN-only v1).
- No aggregate "improve the product based on telemetry" — feedback all manual.
- Forensic incident response: zero server logs to share with law enforcement (also a feature in many threat models).

## Cross-refs

- [`docs/PRIVACY_LEGAL.md`](../PRIVACY_LEGAL.md)
- [`docs/PARENT_AS_ADVERSARY.md`](../PARENT_AS_ADVERSARY.md)
- [`docs/ARCHITECTURE.md`](../ARCHITECTURE.md)
- [`README.md`](../../README.md) (no-server pledge)
- [`docs/FUNDING.md`](../FUNDING.md) (no subscription)

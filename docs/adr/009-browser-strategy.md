# ADR-009: Browser strategy — Chrome enterprise policy v1, Vanadium wrapper v2

Status: Accepted
Date: 2026-06-15

## Context

In-app WebView leak + DoH-in-Chrome bypass are major attack vectors ([`docs/ATTACKS.md`](../ATTACKS.md) D1, C2, P9). Three options considered for browser strategy. Research at [`docs/BROWSER_AND_OS.md`](../BROWSER_AND_OS.md).

## Options

A. **Full custom browser fork** ("OpenWarden Browser") — Vanadium / Cromite-style. 3-6 months FTE, 1 FTE ongoing maintenance.
B. **Wrapper around existing FOSS Chromium fork** (Vanadium) — 4-8 weekends. Track upstream Vanadium for security patches.
C. **Force stock Chrome + DPC enterprise policies + DNS filter** — 1-2 weekends. No custom code.

## Decision

- **v1:** Option C (force-Chrome + enterprise policies + DNS filter via OpenWarden's DNS resolver)
- **v2:** Option B (Vanadium-based wrapper) IF DNS filter proves insufficient
- **Never:** Option A (full fork — project-suicide-by-scope)

v1 Chrome enterprise policies applied via DPC `setApplicationRestrictions`:
- `DnsOverHttpsMode=off` — force system DNS
- `DnsOverHttpsTemplates=` empty
- `SafeBrowsingEnabled=true`
- `SafeSitesFilterBehavior=1`
- `IncognitoModeAvailability=1` (disabled)
- `URLBlocklist=[...]` category-based

Non-Chromium browsers blocklisted by default (Firefox, Brave, Samsung Internet ignore enterprise policy on Android).

Play Services Help WebView (P9) leak mitigated via DNS-block on `gds.google.com/gmsdrops` + related Google help endpoints.

## Consequences

**Good:**
- v1 ships fast, no Chromium-fork maintenance burden.
- DNS filter does heavy lifting at network layer (catches Roblox/Discord WebView too).
- Chrome enterprise policy is well-documented and Google-maintained.

**Bad:**
- Stock Chrome = Google. Acceptable trade-off given alternative is Chromium-fork maintenance.
- Brave/Firefox users on parent device unaffected; child device blocks them by default.
- Vanadium-fork bus factor ~3, Cromite bus factor ~1 → v2 path has risk if Vanadium project pauses.

## Cross-refs

- [`docs/BROWSER_AND_OS.md`](../BROWSER_AND_OS.md)
- [`docs/DNS_RESOLVER.md`](../DNS_RESOLVER.md)
- [`docs/DNS_FILTER.md`](../DNS_FILTER.md)
- [`docs/ATTACKS.md`](../ATTACKS.md) D1, C2, P9

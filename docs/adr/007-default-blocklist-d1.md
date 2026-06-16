# ADR-007: Default blocklist Discord/Roblox/Snap (D1 attack)

Status: Accepted
Date: 2026-06-15

## Context

Red-team research ([`research/03-redteam-adversary.md`](../research/03-redteam-adversary.md) §D1, §O2, §O3) showed that in-app WebView inside Discord, Roblox, and Snap is the **#1 daily-driver bypass** — these apps embed full unfiltered web browsers reachable via any link tap. v1 plan with default allowlists for these apps shipped a hole.

## Options

1. **Allow + loud warning.** Allowed by default, parent sees prominent first-launch warning + suggestion to enable DNS filter.
2. **Blocklist by default.** Discord/Roblox/Snap on default blocklist. Parent must explicitly allow + see "this allows the whole web" warning modal.
3. **Conditional allow.** Allowed only if DNS filter active. Couples two unrelated decisions.

## Decision

Adopt **option 2: blocklist by default**.

OpenWarden v1 ships with `com.discord`, `com.roblox.client`, `com.snapchat.android`, `com.twitter.android`, `com.reddit.frontpage` on the default blocklist for all stages 5-13. Parent override available but gated by warning modal: "This app embeds a full web browser. Allowing it allows the whole web. Consider enabling DNS filter (Settings → DNS) for protection."

Stage 14-17 (teen mode): apps allowed by default; warning shown once during setup.

## Consequences

**Good:**
- Closes the most common kid bypass (D1) from day 1.
- Surfaces the WebView leak concept to parents who'd otherwise not know.
- DNS filter becomes the natural upsell path for "I want my kid to use Discord but safely."

**Bad:**
- Some parents will be annoyed by default friction.
- Roblox blocked-by-default may be a hard sell — Roblox is *the* dominant 8-12yo platform.
- Teen-mode special case adds complexity.
- Requires UI for "allow with caveats" + warning copy.

## Cross-refs

- [`docs/ATTACKS.md`](../ATTACKS.md) D1, O2, O3
- [`docs/DEFENSES.md`](../DEFENSES.md) #17 DNS filter
- [`docs/DNS_RESOLVER.md`](../DNS_RESOLVER.md)
- [`docs/GRADUATED_PRIVILEGES.md`](../GRADUATED_PRIVILEGES.md) stage 14-17

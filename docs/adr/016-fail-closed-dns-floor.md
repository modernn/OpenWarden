# ADR-016: Fail-closed DNS floor — pin Private DNS to a public filtering resolver, not localhost

Status: Accepted
Date: 2026-06-16 (ratified + implemented by #19 — `DnsFloor`)

## Context

Red-team finding **K3**: the on-device resolver's failure handling fails *open*. Every resolver-failure path in [`DNS_RESOLVER.md`](../DNS_RESOLVER.md) §11 drops to **unfiltered network DNS**:

- Local listener cert rejected by OS → "OS falls back to default network DNS and we lose visibility… raises an event after 5 minutes of silence" ([`DNS_RESOLVER.md:195`](../DNS_RESOLVER.md)). ~5 min unfiltered window.
- Resolver process crash → "During the gap the OS uses cached entries and then default network DNS" ([`DNS_RESOLVER.md:196`](../DNS_RESOLVER.md)). ~2s+ gap, restart-timed.
- Trust-store loss (OTA, per N1) → cert path breaks, same fall-through.

The localhost design makes this inevitable: Private DNS is pointed at `openwarden.localhost` → `127.0.0.1` ([`DNS_RESOLVER.md:208`](../DNS_RESOLVER.md)). When the local listener dies, the hostname has no live backing and Android reverts to the **carrier/Wi-Fi default resolver** — no adult/malware filtering at all.

A semi-technical kid (the modeled adversary, [`ATTACKS.md:3`](../ATTACKS.md)) can *induce* these failures on demand: fill storage to crash the FGS ([`ATTACKS.md:54`](../ATTACKS.md) "G2 storage-fill needs fail-closed posture"), fuzz malformed queries, or time a reboot. Each yields a **repeatable window of unfiltered DNS**.

This directly violates the project non-negotiable — "Fail-closed. Every error path defaults to *more* restriction, never less" ([`CLAUDE.md`](../../CLAUDE.md)) — and the explicit v1 requirement "Fail-closed everywhere (G2, H2, F3). Sig parse error, missing policy file, clock anomaly, storage fail → strict baseline, never 'unrestricted'" ([`ATTACKS.md:82`](../ATTACKS.md)). [`DEFENSES.md:78`](../DEFENSES.md) marks G2 fail-closed as "mandatory."

## Options

1. **Status quo (fail-open gap).** Localhost floor; on any local-resolver failure, OS reverts to network default DNS. *Rejected:* repeatable unfiltered windows, directly contradicts the fail-closed non-negotiable.
2. **Localhost-only, harder.** Keep `127.0.0.1` target; just make the watchdog faster / cert rotation safer. *Rejected:* shrinks the window but cannot close it. The instant the listener is down, `openwarden.localhost` has no backing and Android *must* fall through to network DNS — there is no localhost configuration that fails closed. Hardening reduces frequency, not the open-by-design property.
3. **Public filtering floor (recommended).** Pin Android Private DNS to a **public filtering resolver hostname** (e.g. `family.cloudflare-dns.com` / 1.1.1.3), not localhost. The on-device resolver remains the primary path for per-app video accounting and visibility; the public filtering resolver is the **fallback that any local outage drops to**. Any crash, cert reject, OTA, or trust-store loss still resolves through a *filtering* upstream over DoT instead of the carrier default.

## Decision

Adopt **Option 3: DNS enforcement fails CLOSED.**

- **Private DNS is pinned to a public filtering resolver**, not `127.0.0.1`. `dpm.setGlobalPrivateDnsMode(admin, PRIVATE_DNS_MODE_PROVIDER_HOSTNAME, "family.cloudflare-dns.com")`. When the local resolver is healthy it intercepts; when it is down, the OS resolves directly against the filtering upstream over DoT.
- **Private DNS is never set to OFF or OPPORTUNISTIC by the DPC.** PROVIDER_HOSTNAME (strict DoT) only. A failed/empty resolver state must never degrade the mode.
- **`DISALLOW_CONFIG_PRIVATE_DNS` stays on** ([`DNS_RESOLVER.md:209`](../DNS_RESOLVER.md)) so the child cannot edit or clear it.
- **Floor reasserted on every policy-bundle apply and on connectivity change** (mirrors [`DNS_FILTER.md:53`](../DNS_FILTER.md), §4 airplane-mode reassert).
- The on-device resolver stays the **primary** path for per-app caps, video-session tracking, and parent-visible logs (ADR-003 intact); the public filtering resolver is the **floor it sits on top of**, exactly the composition framing in [`DNS_RESOLVER.md:9`](../DNS_RESOLVER.md) ("It is the floor… and it works"). The error is that §11 abandoned that floor instead of falling back to it.

This ties directly to the non-negotiable: every DNS error path now defaults to *more* restriction (filtered public resolver), never less (carrier default).

## Consequences

**Good:**
- No reachable "no filtering at all" state on a managed child device. K3 windows are closed by construction, not by timing.
- Local-resolver outages degrade gracefully: lose *visibility and per-app caps* during the gap, keep *adult/malware filtering*. That is the correct fail-closed tradeoff — restriction is preserved, observability is the thing sacrificed.
- Survives OTA / trust-store loss (N1): the public floor needs no local cert, so a broken trust store no longer opens the device.
- Removes the most attacker-controllable bypass in the DNS stack (induce-a-crash → unfiltered).

**Bad:**
- **Privacy tradeoff.** When the device is on the public floor, the upstream (Cloudflare) sees plaintext qnames for that window — the same exposure as the pre-resolver [`DNS_FILTER.md`](../DNS_FILTER.md) floor, and far smaller than the no-filter hole it replaces. Steady state (local resolver healthy) is unchanged: the local resolver still mediates and seals logs. We accept brief upstream visibility over zero filtering. Self-hosting parents (Pi-hole / AdGuard Home, [`DNS_FILTER.md:36`](../DNS_FILTER.md)) avoid even that by choosing their own floor host.
- **Opt-out nuance.** The README frames Cloudflare 1.1.1.3 as an *optional, opt-out-able* convenience integration ([`README.md:23`](../../README.md), [`ADR-006:25`](006-privacy-no-server.md)). This ADR narrows that: the **specific upstream is user-choosable** among *filtering* resolvers (Cloudflare family, Quad9 family, NextDNS, CleanBrowsing, self-hosted — [`DNS_FILTER.md:34-41`](../DNS_FILTER.md)), but **"no filtering" is not a reachable state** on a managed child device. "Opt out of Cloudflare" means "pick a different filtering floor," not "turn filtering off." The [`DNS_FILTER.md:160`](../DNS_FILTER.md) "Off" preset must be reconciled (see below).
- Slightly more upstream load on the public resolver during outages (negligible — outages are rare and brief).

## Doc changes required

1. **[`DNS_RESOLVER.md`](../DNS_RESOLVER.md) §11** — rewrite the two fail-open bullets:
   - Line 195 (cert rejected): remove "OS falls back to default network DNS and we lose visibility." Replace with: because Private DNS is pinned to the public filtering resolver (not localhost), a rejected local cert means the OS resolves directly against the filtering upstream over DoT — filtering preserved, visibility/per-app caps lost for the window; raise the silence event but do **not** characterize it as unfiltered.
   - Line 196 (process crash): same correction — during the watchdog gap the OS uses the **public filtering floor**, not "default network DNS." Log the gap as a *visibility* gap, not a filtering gap.
2. **[`DNS_RESOLVER.md`](../DNS_RESOLVER.md) §12** — change provisioning step 4 (line 208): target `family.cloudflare-dns.com` (or parent-selected filtering resolver), not `openwarden.localhost`/`127.0.0.1`. The local resolver intercepts as the active resolver while healthy; the pinned hostname is the fallback the OS uses when the local listener is down. Note the §1 "floor" framing ([`DNS_RESOLVER.md:9`](../DNS_RESOLVER.md)) is now literal: the floor is the fallback.
3. **[`DNS_FILTER.md`](../DNS_FILTER.md) §10** — the "Off" preset (line 160) must not set Private DNS to OFF/OPPORTUNISTIC. Redefine "Off" as "minimum filtering floor only" (drop curated lists + Chrome SafeSites, keep the public filtering resolver), or remove the preset. "No DNS filtering at all" is unreachable per this decision.
4. **[`ATTACKS.md`](../ATTACKS.md)** — under "What V1 MUST defend against" item 4 ([`ATTACKS.md:82`](../ATTACKS.md)), add K3 (induced-resolver-failure → unfiltered DNS) to the G2/H2/F3 fail-closed cluster.
5. **[`DEFENSES.md`](../DEFENSES.md)** — defense #17 (line 42) and the G2 row (line 78): note the floor is a *public filtering* resolver so the local-resolver failure path stays fail-closed; add K3 → #17 to the attack→defense map.

## Cross-refs

- [`CLAUDE.md`](../../CLAUDE.md) (fail-closed non-negotiable)
- [`docs/DNS_RESOLVER.md`](../DNS_RESOLVER.md) §1, §11, §12
- [`docs/DNS_FILTER.md`](../DNS_FILTER.md) §2-3, §10
- [`docs/ATTACKS.md`](../ATTACKS.md) (G2, K3, "fail-closed everywhere")
- [`docs/DEFENSES.md`](../DEFENSES.md) (#17, G2 row)
- [`docs/adr/003-dns-video-tracking.md`](003-dns-video-tracking.md) (local resolver = primary path, unchanged)
- [`docs/adr/006-privacy-no-server.md`](006-privacy-no-server.md) (opt-out Cloudflare framing, narrowed here)
- [`README.md`](../../README.md) (optional-integration framing)

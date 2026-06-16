---
id: fail-closed-dns-floor
title: Private DNS is pinned to a public filtering resolver — never OFF/localhost
type: decision
tags: [dns, fail-closed, defense, private-dns, dot]
status: active
created: 2026-06-15
updated: 2026-06-15
expires: null
source_pr: null
---

# Fail-closed DNS floor

OpenWarden's DPC (Device Owner) sets device-wide Private DNS to a **public filtering
resolver** over DNS-over-TLS:

```
setGlobalPrivateDnsMode(admin, PRIVATE_DNS_MODE_PROVIDER_HOSTNAME, "<filtering-resolver>")
```

The user cannot turn it off, edit it, or fall back to plaintext. This is **the floor**.

## The fail-closed rule
Every resolver-failure path (resolver crash, cert reject, OTA trust-store change) must still
land on a **filtering** resolver — it must **never** drop to unfiltered/network DNS,
`OFF`, `OPPORTUNISTIC`, or `localhost`. Pin the floor to a *public* filtering hostname (e.g.
`family.cloudflare-dns.com`) so an outage of any local component still fails **closed**.

This is a direct application of the project non-negotiable: "every error path defaults to
more restriction, never less."

## Source / citations
- `docs/DNS_FILTER.md` — Layer 1 device-wide Private DNS ("This is the floor").
- `docs/research/07-redteam-design-review.md` — finding **K3**: resolver-failure paths
  currently drop to unfiltered DNS (violates fail-closed); mitigation = pin to public
  filtering resolver, never OFF/OPPORTUNISTIC.

TODO(maintainer): close the K3 gap in `docs/DNS_RESOLVER.md` and ratify. Task brief called
this "ADR-016" — that ADR does not exist yet.

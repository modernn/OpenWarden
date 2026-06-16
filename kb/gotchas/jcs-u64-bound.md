---
id: jcs-u64-bound
title: JCS / RFC 8785 numbers are exact only to 2^53−1 — cap seq & timestamps
type: gotcha
tags: [crypto, protocol, jcs, rfc8785, replay, policy-seq, fail-closed]
status: active
created: 2026-06-15
updated: 2026-06-15
expires: 2026-12-31
source_pr: null
---

# JCS / RFC 8785 numbers are exact only to 2^53−1

Signed policy bundles are canonicalized with **JCS (RFC 8785)**, whose number serialization
follows ECMAScript/IEEE-754 doubles: integers are represented **exactly only up to
2^53−1**. A `u64` field (e.g. `policy_seq`, timestamps) larger than that cannot round-trip,
so the signer and verifier can disagree.

## Why it bites (fail-closed angle)
- A large `policy_seq` that can't round-trip → signature mismatch → bundle rejected.
- Worse: set the monotonic floor absurdly high (near 2^63) and **no future bundle can ever
  exceed it** → permanent update freeze (a self-inflicted DoS). This collides with the
  replay-protection design (`policy_seq` monotonic + freshness window).

## What to do
- Cap `policy_seq` / timestamps at **≤ 2^53−1**, or serialize large integers as **strings**.
- Add a large-integer test vector and bound the max allowed seq jump.

## Source / citations
- `docs/research/07-redteam-design-review.md` — finding **JC1** (red-team input, not yet
  ratified canon). `docs/PROTOCOL.md` currently declares these fields as `u64` without the
  cap — that gap is exactly what JC1 flags.

TODO(maintainer): ratify the cap in `docs/PROTOCOL.md` / an ADR. Task brief referenced this
as "ADR-017", but no ADR-017 exists yet (ADRs only go 001–012). Cite the red-team finding
until an ADR lands.

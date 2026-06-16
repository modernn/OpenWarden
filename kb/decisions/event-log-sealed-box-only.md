---
id: event-log-sealed-box-only
title: Event log uses anonymous sealed-box only — child cannot read its own log
type: decision
tags: [crypto, event-log, sealed-box, privacy, parent-as-adversary]
status: active
created: 2026-06-15
updated: 2026-06-15
expires: null
source_pr: null
---

# Event log: anonymous `crypto_box_seal` only

The encrypted event log is sealed to the **parent's** X25519 public key using libsodium's
anonymous **`crypto_box_seal`** (sealed box). There is no symmetric key the child holds and
no authenticated-box mode where the child is a readable sender.

## Consequence (the point of the design)
**The child cannot decrypt its own writes.** A kid with root sees only a queue of opaque
blobs. To map a blob back to "what did OpenWarden report about me," the kid would have to
either break libsodium (no known vuln) or recover the recipient private key — which lives in
the parent's Keystore/Keychain on a device the kid does not control.

This supports the "parent-as-adversary"-resistant *transparency* model: categories are
disclosed to the kid up front (`docs/KID_TRANSPARENCY.md`), but raw per-event detail is not
self-readable on the child.

## Source / citations
- `docs/CRYPTO.md` §4 — "the child cannot decrypt its own writes."
- `docs/DEFENSES.md` — Pattern B (sealed-box as the design answer).

TODO(maintainer): there is a related open contradiction (sealed-box vs authenticated-box,
red-team finding SB1) about the *exact* construction; ratify in an ADR. Task brief called
this "ADR-015" — that ADR does not exist yet.

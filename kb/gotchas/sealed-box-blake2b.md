---
id: sealed-box-blake2b
title: libsodium `crypto_box_seal` nonce is BLAKE2b, NOT BLAKE3
type: gotcha
tags: [crypto, libsodium, sealed-box, blake2b, event-log, interop]
status: active
created: 2026-06-15
updated: 2026-06-15
expires: 2026-12-31
source_pr: null
---

# Sealed-box nonce is BLAKE2b, not BLAKE3

libsodium's `crypto_box_seal` (the anonymous sealed box used for the event log) derives its
nonce as:

```
nonce = BLAKE2b( ephemeral_pubkey || recipient_pubkey )   # first 24 bytes
```

It uses **BLAKE2b**, which is built into libsodium — **not BLAKE3**. Any spec text or code
that says "BLAKE3" for the sealed-box nonce will **not interoperate** with real libsodium:
events encrypt fine but the parent silently fails to decrypt them (a fail-open logging hole).

## What to do
- Implement and document the nonce as BLAKE2b. If you wrap or re-implement sealed-box,
  match libsodium's construction exactly and test against a libsodium-produced vector.

## Source / citations
- `docs/CRYPTO.md` §4 — correctly describes BLAKE2b + XSalsa20-Poly1305.
- `docs/PROTOCOL.md` §6.2 — currently says **BLAKE3-256**; this is the bug.
- `docs/research/07-redteam-design-review.md` — finding **SB1** (BLAKE3 vs BLAKE2b
  contradiction; literal PROTOCOL impl won't interop).

TODO(maintainer): fix `docs/PROTOCOL.md` to BLAKE2b (or open an ADR resolving SB1). Task
brief referenced this as "ADR-015", but no ADR-015 exists yet.

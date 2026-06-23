# ADR-038: Six-emoji SAS encoding — four-key HKDF + pinned 64-emoji table (parent slice d)

Status: Accepted
Date: 2026-06-22
Implements: **docs/PROTOCOL.md §7.4** (six-emoji confirmation) + **ADR-025 D5(d)** (the parent-side "derive + display the SAS, capture Match/Mismatch" slice) and **ADR-025 D2a** (the four-key HKDF binding)
Pins: the previously-underspecified §7.4 emoji encoding — the **concrete 64-emoji table**, the **bit→emoji mapping**, and the **byte layout** — as protected canon, because the (not-yet-built) child side MUST derive the identical six emojis byte-for-byte
Relates: ADR-025 (pairing handshake ratified; §7.4/D2a), ADR-035/036/037 (parent slices a/b/c — session+QR / endpoint / attestation-verify), ADR-029 (Tier-2 posture — SAS is the load-bearing human MITM catch on TEE-level attestation), ADR-032 (child identity / `K_bind`); docs/ATTACKS.md H3 (pubkey substitution); docs/DEFENSES.md #4; issue #97 (re-scope of #23)
Maintainer-approved: attended agent-blocked review, 2026-06-22 (table source = Matrix/Element SAS 64; record as new ADR-038).

## Context

PROTOCOL §7.4 (ratified by ADR-025) specifies that, after §7.3 attestation passes, both
sides derive and display a **six-emoji** Short Authentication String (SAS) the human
compares to catch a key-substitution MITM the endpoint cannot detect. ADR-025 **D2a**
fixed the SAS input to bind **all four** pinned keys —

```
shared_secret_for_display := HKDF-SHA256(
    salt = "openwarden-pair-v1",
    ikm  = parent_ed25519_pub ‖ parent_x25519_pub ‖ child_ed25519_pub ‖ child_x25519_pub,
    info = provisioning_nonce
)[0..15]
6 emojis := SAS-emoji-table-lookup-from-Signal-spec(shared_secret_for_display)
```

— so substituting **any** key (including either X25519 key, which §7.3 attestation does
**not** bind) changes the emojis and the human compare catches it.

But the lookup itself — **"SAS-emoji-table-lookup-from-Signal-spec"** — is not actually
specified. **Signal publishes no emoji-SAS table** (Signal uses numeric safety numbers).
The recognizable, documented 64-emoji SAS table in the wild is the **Matrix/Element SAS
table** (used by Matrix `m.sas.v1` device verification; the emoji list, names, and
codepoints are published and translation-vetted). Until a concrete table + bit mapping are
pinned, parent and child cannot derive the **same** six emojis, so the SAS is unbuildable.
This ADR pins them.

This is a **display-only derivation**, not a `proto` wire-format change: the SAS never goes
on the wire — each side derives it independently and a **human** compares. It is, however,
**normative canon both peers must share**, so it is recorded as an ADR with KATs and test
vectors (per the §7 "crypto/protocol REQUIRES tests + ADR" rule), and the child slice that
derives the SAS later MUST use this exact table and mapping.

## Decision

**D1 — Salt / IKM / info / length are §7.4 (D2a) verbatim.** HKDF-SHA256 (RFC 5869,
Extract-then-Expand) with `salt = "openwarden-pair-v1"` (ASCII, 18 bytes), `ikm` = the four
32-byte pinned public keys concatenated in the **fixed order** `parent_ed25519_pub ‖
parent_x25519_pub ‖ child_ed25519_pub ‖ child_x25519_pub` (128 bytes total), `info` = the
raw 32-byte `provisioning_nonce`, output length **16 bytes**. Each input key MUST be exactly
32 bytes — a wrong length is a fail-closed error (no SAS is produced), never a silent
truncation.

**D2 — Pinned table = the Matrix/Element SAS 64-emoji list.** The 64 emojis, indices 0–63,
are fixed by this ADR (full list in the appendix + `docs/test-vectors/pairing/pair-09-*`).
The order is **load-bearing**: both peers index the same list, so any reorder is a breaking
canon change requiring a superseding ADR. The table is committed in code as
`SasEmojiTable.EMOJIS` and as a test vector; the two MUST stay byte-identical.

**D3 — Bit→emoji mapping = six 6-bit big-endian indices over the first 36 bits.** Read the
16-byte HKDF output as a big-endian bit stream (bit *k* = `(out[k/8] >> (7 − k mod 8)) & 1`,
bit 0 = MSB of `out[0]`). Emoji *i* (i = 0..5) = `EMOJIS[ bits 6·i .. 6·i+5 ]`. Six emojis
consume the first **36 bits**; the remaining bits are unused. This yields a **36-bit SAS**
(≈ 2⁻³⁶ blind-MITM success per attempt — the ZRTP/Matrix SAS strength), gated further by the
single-use nonce + per-session attempt cap (ADR-036) and the mandatory attestation (ADR-025
D2/D7). Six emojis (not Matrix's seven) is the §7.4 canon count.

**D4 — Scope = derive + display + capture Match/Mismatch; this slice pins nothing.** Per
ADR-025 D5, slice (d) produces the SAS and captures the human's verdict. **Match** hands the
child keys to slice (e) for pinning (ADR-025 D5e — a *separate* agent-blocked issue); **this
slice does not write the pin.** **Mismatch** aborts the pair, surfaces the MITM warning, and
**burns the single-use nonce** (the ADR-036 D4 HARD criterion this slice inherits) via the
existing `PairingNonceBurner` seam — forcing a fresh QR, no silent retry on the same nonce.

**D5 — Fail-closed everywhere.** A malformed parent-key snapshot in the pairing session, a
wrong-length key, or any derivation error resolves to **no SAS / no pin**, never a
permissive default. The SAS is only ever derived *after* `Section73AttestationVerifier`
returns `Accepted` (ADR-037); attestation is never replaced by the SAS (D7 of ADR-025/029
holds — never SAS-only).

**D6 — Host-testable pure core + injected KDF seam.** The table, the bit mapping, the IKM
assembly, and the Match/Mismatch lifecycle are pure `commonMain` (host-tested in
`commonTest` with a fake KDF). HKDF-SHA256 is a `SasKdf` seam; the real `androidMain`
implementation reuses Bouncy Castle's `HKDFBytesGenerator` (the ADR-033 host-testable
precedent) and is exercised in `androidUnitTest` against an RFC 5869 KAT and the SAS golden
vector — no device, no libsodium.

## Consequences

Good:
- The §7.4 SAS is now **buildable**: parent and child have one pinned table + mapping to
  agree on, vectored and KAT-locked.
- **D2a is enforced concretely**: the four substitution regressions (incl. **both** X25519
  keys) are the executable proof of the X25519-substitution fix.
- The encoding decision lives in **one reviewable record** (this ADR) instead of buried in
  an Accepted ADR, matching the one-ADR-per-slice rhythm (035/036/037).

Bad / accepted limits:
- **36-bit SAS.** Standard for emoji SAS (ZRTP/Matrix), and the live window is bounded by the
  single-use nonce + attempt cap + mandatory attestation; not a brute-force surface in
  practice. A longer SAS would cost human-compare UX for negligible gain.
- **Table is now frozen canon.** Reordering/replacing emojis is a breaking change needing a
  superseding ADR (peers must match). Accepted — that immutability is the point.
- **No child-side SAS yet.** This slice is parent-only; the child derivation lands with its
  own (agent-blocked) issue and MUST consume this table/mapping unchanged.

## Test plan (binds the implementation)

- **HKDF KAT:** `BouncyCastleSasKdf` reproduces RFC 5869 Test Case 1 (proves Extract/Expand
  parameter ordering) — `androidUnitTest`.
- **SAS golden vector:** fixed four keys + nonce ⇒ the exact six emojis (`pair-09-sas-kat`).
- **Four-key substitution (D2a):** swapping **each** of `parent_ed25519_pub`,
  `parent_x25519_pub`, `child_ed25519_pub`, `child_x25519_pub` changes the six emojis (the
  two X25519 cases are the regression for the flaw D2a closes).
- **Both-sides agreement:** the parent's derivation and the child's (same inputs, same fixed
  order) produce identical emojis.
- **Mapping KAT (pure):** known 16-byte outputs ⇒ known emoji indices (bit-extraction
  correctness), driven by a fake KDF in `commonTest`.
- **Mismatch burns the nonce:** `confirm(matched = false)` calls `PairingNonceBurner.burn()`
  exactly once and yields no pinnable keys; `confirm(matched = true)` burns nothing and
  yields the child keys for slice (e). Deterministic fake burner, `commonTest`.
- **Fail-closed:** a wrong-length input key ⇒ the derivation throws / refuses (no SAS).

## Appendix — the pinned 64-emoji table (indices 0–63)

Matrix/Element `m.sas.v1` order. `index name codepoint(s)`:

```
 0 Dog        U+1F436      16 Tree       U+1F333      32 Hat        U+1F3A9      48 Hammer     U+1F528
 1 Cat        U+1F431      17 Cactus     U+1F335      33 Glasses    U+1F453      49 Telephone  U+260E FE0F
 2 Lion       U+1F981      18 Mushroom   U+1F344      34 Spanner    U+1F527      50 Flag       U+1F3C1
 3 Horse      U+1F40E      19 Globe      U+1F30F      35 Santa      U+1F385      51 Train      U+1F682
 4 Unicorn    U+1F984      20 Moon       U+1F319      36 Thumbs Up  U+1F44D      52 Bicycle    U+1F6B2
 5 Pig        U+1F437      21 Cloud      U+2601 FE0F  37 Umbrella   U+2602 FE0F  53 Aeroplane  U+2708 FE0F
 6 Elephant   U+1F418      22 Fire       U+1F525      38 Hourglass  U+231B       54 Rocket     U+1F680
 7 Rabbit     U+1F430      23 Banana     U+1F34C      39 Clock      U+23F0       55 Trophy     U+1F3C6
 8 Panda      U+1F43C      24 Apple      U+1F34E      40 Gift       U+1F381      56 Ball       U+26BD
 9 Rooster    U+1F413      25 Strawberry U+1F353      41 Light Bulb U+1F4A1      57 Guitar     U+1F3B8
10 Penguin    U+1F427      26 Corn       U+1F33D      42 Book       U+1F4D5      58 Trumpet    U+1F3BA
11 Turtle     U+1F422      27 Pizza      U+1F355      43 Pencil     U+270F FE0F  59 Bell       U+1F514
12 Fish       U+1F41F      28 Cake       U+1F382      44 Paperclip  U+1F4CE      60 Anchor     U+2693
13 Octopus    U+1F419      29 Heart      U+2764 FE0F  45 Scissors   U+2702 FE0F  61 Headphones U+1F3A7
14 Butterfly  U+1F98B      30 Smiley     U+1F600      46 Padlock    U+1F512      62 Folder     U+1F4C1
15 Flower     U+1F337      31 Robot      U+1F916      47 Key        U+1F511      63 Pin        U+1F4CC
```

## Cross-refs
- [docs/PROTOCOL.md](../PROTOCOL.md) §7.4 (pinned here), §7.5 (pin = slice e)
- [ADR-025](025-pairing-handshake-direction-attestation-sas.md) D2a/D5(d)/D7, [ADR-029](029-tier2-attestation-posture.md) D2 (SAS load-bearing on TEE), [ADR-036](036-parent-pairing-endpoint-pre-auth.md) D4 (burn-on-fail HARD criterion), [ADR-037](037-parent-attestation-verifier-slice-c.md) (the Accepted gate before the SAS)
- docs/ATTACKS.md H3; docs/DEFENSES.md #4; issue #97

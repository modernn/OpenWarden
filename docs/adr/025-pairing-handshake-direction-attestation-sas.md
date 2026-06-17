# ADR-025: Pairing handshake ratified — parent-displays-QR + StrongBox attestation + six-emoji SAS (ratifies PROTOCOL §7)
Status: Accepted
Date: 2026-06-17
Ratifies: **docs/PROTOCOL.md §7** (pairing handshake §7.1–§7.5) as protected canon
Relates: docs/DEFENSES.md #4 (StrongBox identity + attestation cert pinning → H3/swap), #9 (AVB via Key Attestation), #14 (hardware-attested QR provisioning w/ nonce), Pattern E (remote attestation); docs/ATTACKS.md H3 (pubkey substitution, CRITICAL); ADR-001/ADR-023 (Pixel-class enforcement tier); ADR-015 (X25519 sealed-box audience = the child key pinned here); ADR-019 (canonical signing); issue #23; **PR #64 (the inverted-direction attempt this ADR exists to reject)**

## Context

PROTOCOL §7 specifies the pairing handshake — the one-time bootstrap that pins both peers' public keys — but it was **never ADR-ratified**. Because the design was not CODEOWNERS-gated canon, issue #23 ("parent scans the child pairing QR") was mislabeled `agent-ready`, and an autopilot run (PR #64) implemented it as written. That PR:

1. **Inverted the §7 direction** — parent runs a camera and scans a QR the *child* displays. §7.1 has the **parent display** the QR; §7.2 has the **child scan** it and **POST** its response over LAN.
2. **Invented a `proto` wire field** — replaced §7.2's `child_attestation_cert_chain` with a fabricated `tls_spki`. `tls_spki` appears nowhere in §7.
3. **Deleted the trust model** — pinned the peer on JSON-parse success alone, with **no** §7.3 hardware attestation and **no** §7.4 six-emoji SAS. A parent scanning an attacker's QR would pin the attacker's keys, making the attacker the audience for sealed-box events and the pinned sync channel — the exact **H3 pubkey-substitution** attack (ATTACKS.md, CRITICAL) that §7 exists to close.

Dual review (crypto-reviewer + tech-lead) caught all three before merge; PR #64 was closed and #23 re-labeled `agent-blocked`. This ADR closes the root cause: it **ratifies §7 as canon**, records the rejected inversion and *why* it is forbidden, and **re-scopes the parent-side pairing work** so the follow-up issues are correctly shaped and correctly gated.

**Why the direction is not a free choice — the attestation binding forces it.** The anti-device-swap guarantee (DEFENSES #14, Pattern E; closes H3) requires the child to return a hardware **attestation cert chain** whose challenge is a **fresh parent-issued nonce**:

- The nonce must reach the child to become its StrongBox `setAttestationChallenge` input (§7.2 step 2) ⇒ the nonce flows **parent → child** ⇒ the **parent must be the displayer** of the QR that carries it (§7.1).
- The attestation response is a chain of ~3 DER certificates (commonly >2 KB) — **too large to encode in a scannable QR** — so it must return over the **network POST** (§7.2 step 4), not via a child-displayed QR.

So the only direction that preserves the attestation defense is §7's: **parent displays, child scans + POSTs.** A "parent scans the child's QR" model cannot carry attestation and has no channel for the nonce challenge; it necessarily drops the anti-swap guarantee.

## Options

- **A. Ratify §7 as written — parent displays QR (parent pubkeys + nonce + transport hints); child scans, StrongBox-attests with the nonce, POSTs `(child pubkeys + attestation cert chain)`; parent verifies attestation **and** six-emoji SAS, then pins (chosen).** Preserves DEFENSES #4/#14 + Pattern E; closes H3. Costs: pairing needs LAN reachability at pair time and a parent-side pairing endpoint.
- **B. Reverse to parent-scans-child (PR #64's model).** Child displays a QR, parent scans. Forces *either* dropping hardware attestation (a QR can't carry the cert chain) — losing the anti-swap defense and failing H3 — *or* adding extra network round-trips to re-home the nonce challenge, at which point the QR is doing nothing the POST couldn't. **Rejected:** strictly weaker, and only attractive if we deliberately abandon attestation (a v3+ non-Pixel concern, not today's Pixel-class tier).
- **Trust-model sub-options for A:**
  - **A-both. §7.3 hardware attestation AND §7.4 six-emoji SAS (chosen).** Attestation proves *genuine unrooted allow-listed hardware*; SAS gives the human a MITM-catching visual confirm. Belt and suspenders, matching the Pixel-7-only v0.x tier (ADR-001/023).
  - **A-sas-only.** Drop attestation, keep SAS. Works on non-StrongBox devices but loses anti-device-swap (DEFENSES #14). Out of scope until device support broadens (v3+).
  - **A-attest-only.** Attestation without SAS. Loses the human MITM confirm at pairing. Rejected.

## Decision

Adopt **Option A with A-both**. PROTOCOL §7.1–§7.5 is ratified verbatim as protected canon. Seven parts.

**D1 — Direction is fixed: parent displays, child scans + POSTs.** The parent generates a pairing session and **displays** the §7.1 QR:

```json
{ "v":1, "parent_ed25519_pub":"b64url(32)", "parent_x25519_pub":"b64url(32)",
  "provisioning_nonce":"b64url(32)", "transport_hints":{ "mdns":"_openwarden._tcp.local", ... } }
```

`provisioning_nonce` is CSPRNG-fresh and **single-use per pair attempt**. The freshly-DPC-provisioned child **scans** it, generates Ed25519 + X25519 in StrongBox with the nonce as `setAttestationChallenge`, and **POSTs** the §7.2 response (`child_ed25519_pub`, `child_x25519_pub`, `child_attestation_cert_chain`) to the parent's pairing endpoint discovered via `transport_hints`.

**D2 — Trust model = hardware attestation AND six-emoji SAS; pin only after both pass; any failure refuses the pair (fail-closed).** Per §7.3 the parent MUST verify, and refuse on any failure:
1. cert chain parses and roots in the **Google Hardware Attestation root**;
2. leaf attestation challenge == the `provisioning_nonce` it issued;
3. `verifiedBootState == VERIFIED` (GREEN), bootloader **locked**, device is an **allow-listed model**, attestation security level == **STRONGBOX**;
4. leaf-cert public key == `child_ed25519_pub`.

Then per §7.4 both sides derive `HKDF-SHA256(salt="openwarden-pair-v1", ikm=parent_ed25519_pub‖child_ed25519_pub, info=provisioning_nonce)[0..15]` → a **six-emoji SAS**. The parent visually compares and taps **Match** → the child pubkeys enter `pinned` state; **Mismatch → abort pair + surface MITM warning.** No pin occurs on attestation-only or SAS-only success.

**D3 — Wire schema is §7.1/§7.2 as written; `child_attestation_cert_chain` is mandatory; there is NO `tls_spki` field.** The invented `tls_spki` of PR #64 is rejected. The TLS sync channel is pinned **against the pinned pubkey** post-pairing (§4.3 "cert pinning against pubkey"), **not** via a separate SHA-256 SPKI hash carried in the pairing QR. Introducing a pairing wire-format field is a `proto` change and is **agent-blocked** by definition.

**D4 — The parent-scans-child inversion is forbidden.** Recorded for posterity (Option B): it breaks the nonce→attestation-challenge binding (no parent→child channel for the nonce) and cannot carry the >2 KB cert chain in a QR. Any future proposal to support it MUST come as its own ADR that explicitly addresses how attestation survives.

**D5 — Re-scope the parent-side pairing work.** The parent half of pairing is **not** "scan a QR." It is, and follow-up issues MUST be cut as:
- (a) generate the pairing session — CSPRNG `provisioning_nonce`, ephemeral parent material, **render** the §7.1 QR;
- (b) run the pairing **endpoint** that receives the §7.2 child POST (LAN, mDNS-discovered);
- (c) **verify** the §7.3 attestation chain (D2 checks 1–4);
- (d) derive + display the §7.4 SAS, capture **Match/Mismatch**;
- (e) **pin** `(child_ed25519_pub, child_x25519_pub)` only on Match.

All of (a)–(e) are crypto/`proto` surface ⇒ **`agent-blocked`**, human-implemented, ADR-and-test-gated. Issue **#23 ("parent scans child QR") is wrong as written** and stays closed-for-rescope; replace it with issues for (a)–(e). The child half (scan + StrongBox keygen + attestation POST) is the existing `agent-blocked` issues #20/#21/#22.

**D6 — Byte-level key validation is a conformance requirement.** Wherever a pubkey is parsed (parent verifying the POST, or any consumer), the validator MUST base64url-**decode**, assert the decoded length is **exactly 32 bytes**, validate the alphabet, and reject malformed curve encodings — not merely check string length (PR #64 accepted any ≥43-char string, so a 32.25-byte blob passed). Test vectors land under `docs/test-vectors/pairing/` (`pair-01` valid, `pair-02` malformed), per §9.1.

**D7 — Device scope: attestation is mandatory on the v0.x Pixel-class tier.** Verification requires StrongBox + a Google-root chain + an allow-listed model (Pixel 7; ADR-001 as amended by ADR-023). A SAS-only fallback for non-attesting devices is explicitly **out of scope** (v3+); on today's tier, attestation failure refuses the pair.

**D8 — Pinning + rotation unchanged from §7.5, restated as canon.** The parent pins the child pubkeys; the child StrongBox-wraps the parent pubkeys. **Rotation of a pinned key MUST require the parent's BIP39 recovery phrase + a 24-h delay** (Defense #15) — this is the second half of the H3 defense (pin + recovery-gated rotation) and MUST NOT be weakened by any pairing-UX change.

## Consequences

Good:
- **§7 is now protected canon.** The gap that let #23 be mislabeled `agent-ready` is closed; the pairing design is CODEOWNERS-gated like the rest of the crypto surface.
- **H3 anti-swap + MITM defenses are preserved end-to-end:** attestation proves genuine unrooted hardware, SAS catches key-substitution MITM, recovery-gated rotation closes the long tail.
- **The invented `tls_spki` field is killed before it spread** into the child side or test vectors.
- **Future pairing issues are correctly scoped and gated** (D5): the parent half is endpoint + verify + SAS + pin, all `agent-blocked`, not an autopilot QR-scan task.

Bad / accepted limits:
- **Pairing requires LAN reachability at pair time** (the child POST). Acceptable: LAN-only is the v0.3 transport anyway; pairing is a one-time bench/OOBE step.
- **Attestation ties v0.x pairing to Pixel-class StrongBox devices.** Already the declared tier (ADR-001/023); non-Pixel support is deferred (D7), not regressed.
- **The parent must run a pairing endpoint**, which is more surface than a pure QR scan — but it is the *only* direction that preserves attestation (Context), so the cost is intrinsic, not incidental.
- **This ADR ships no code.** It is a design-ratification + re-scope record (cf. ADR-015/017). The implementation lands in the D5 follow-up issues, each with the tests below.

## Test plan (binds the D5 implementation issues, not this ADR)

- **Attestation verify:** good Google-rooted chain with matching nonce + GREEN verified-boot + locked bootloader + STRONGBOX + allow-listed model + leaf-pubkey == `child_ed25519_pub` ⇒ proceed; **each** of {wrong root, nonce mismatch, `verifiedBootState != VERIFIED`, unlocked bootloader, non-allow-listed model, security level != STRONGBOX, leaf-pubkey != `child_ed25519_pub`} ⇒ **refuse pair** (deterministic, seam-injected, fail-closed).
- **SAS:** the HKDF-derived six-emoji sequence is identical on both sides for matching inputs; a substituted `child_ed25519_pub` changes the SAS (drives the Mismatch → abort + MITM-warning path).
- **Single-use nonce:** a replayed `provisioning_nonce` from a prior attempt ⇒ refuse.
- **Byte-level pubkey validation (D6):** `pair-01` valid (exactly 32 decoded bytes) accepted; `pair-02` (non-base64url, wrong length, over-long, bad curve point) rejected — vectors in `docs/test-vectors/pairing/`.

## Cross-refs
- [docs/PROTOCOL.md](../PROTOCOL.md) §7.1–§7.5 (ratified here), §4.3 (TLS pin against pubkey), §8 (versioning), §9.1 (test vectors)
- [ADR-001](001-one-device-tier.md) / [ADR-023](023-enforcement-floor-tiers.md) (Pixel-class attestation tier)
- [ADR-015](015-event-log-crypto-primitives.md) (X25519 sealed-box audience = the pinned child key)
- [ADR-019](019-canonical-signing-invariant.md) (canonical signing of subsequent bundles to the pinned key)
- docs/DEFENSES.md #4/#9/#14 + Pattern E; docs/ATTACKS.md H3
- issue #23 (closed-for-rescope), PR #64 (rejected inversion); CLAUDE.md (fail-closed; no content monitoring)

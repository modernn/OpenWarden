# ADR-025: Pairing handshake ratified — parent-displays-QR + StrongBox attestation + six-emoji SAS (ratifies PROTOCOL §7)
Status: Accepted
Date: 2026-06-17
Ratifies: **docs/PROTOCOL.md §7** (pairing handshake §7.1–§7.5) as protected canon
Amends: **docs/PROTOCOL.md §7.4** — the six-emoji SAS now binds **all four** pinned keys (both Ed25519 + both X25519), closing an X25519-substitution MITM the original Ed25519-only SAS left open (caught in this ADR's review — see D2a)
Relates: docs/DEFENSES.md #4 (StrongBox identity + attestation cert pinning → H3/swap), #9 (AVB via Key Attestation), #14 (hardware-attested QR provisioning w/ nonce), Pattern E (remote attestation); docs/ATTACKS.md H3 (pubkey substitution, CRITICAL); ADR-001/ADR-023 (Pixel-class enforcement tier); ADR-015 (X25519 sealed-box audience = the child key pinned here); ADR-019 (canonical signing); issue #23; **PR #64 (the inverted-direction attempt this ADR exists to reject)**

> **Amended by ADR-029 (Accepted, 2026-06-18):** for the committed Samsung/OnePlus **Tier-2** targets (ADR-026), **D2 check 1** widens to accept an **allow-listed OEM attestation root** (Google + Samsung Knox + OnePlus, not only Google), and **only the `securityLevel` sub-condition of D2 check 3** widens to accept **TEE** (`TRUSTED_ENVIRONMENT`, not only `STRONGBOX`) — check 3's other sub-conditions (**VERIFIED boot, locked bootloader, allow-listed model**) are **unchanged**. **D7**'s Pixel-only scope widens to include them. **Refuse-closed on any *unknown* root or `SOFTWARE` level; the four-key SAS (D2a) stays mandatory; the downgrade — incl. no per-leaf revocation on OEM roots — is disclosed (ADR-026 D3).** Tier 1 (Pixel) is unchanged. See ADR-029.

> **Amended by ADR-032 (Proposed, 2026-06-22):** Android StrongBox cannot generate or attest **Curve25519** keys (only EC P-256/RSA/AES/HMAC), so §7.2's "generate Ed25519 + X25519 in StrongBox" and **D2 checks 3–4** (mandatory `STRONGBOX` attestation of `child_ed25519_pub`) are **unsatisfiable on the declared hardware**. ADR-032 resolves it: the child generates a **StrongBox EC P-256 *device-binding key* `K_bind`** (the key that carries the `STRONGBOX` attestation challenge and chain), and `K_bind` **signs a `ChildKeyBinding`** over the TEE-resident `child_ed25519_pub ‖ child_x25519_pub ‖ provisioning_nonce` (new POST field `child_binding_sig`). **D2 check 3 is now the attestation of `K_bind`** (satisfiable); **check 4** becomes "leaf pubkey == `K_bind`" **plus new check 4b** "`child_binding_sig` verifies against the attested `K_bind` over the JCS body". The **four-key SAS (D2a) is unchanged and still mandatory**; **D7's mandatory-attestation / never-SAS-only rule holds** (a real `STRONGBOX` attestation of `K_bind` still gates the pin). The Curve25519 privkeys are **TEE-resident, bound to StrongBox** (disclosed residual, consistent with ADR-029's TEE posture). See **ADR-032**.

## Context

PROTOCOL §7 specifies the pairing handshake — the one-time bootstrap that pins both peers' public keys — but it was **never ADR-ratified**. Because the design was not CODEOWNERS-gated canon, issue #23 ("parent scans the child pairing QR") was mislabeled `agent-ready`, and an autopilot run (PR #64) implemented it as written. That PR:

1. **Inverted the §7 direction** — parent runs a camera and scans a QR the *child* displays. §7.1 has the **parent display** the QR; §7.2 has the **child scan** it and **POST** its response over LAN.
2. **Invented a `proto` wire field** — replaced §7.2's `child_attestation_cert_chain` with a fabricated `tls_spki`. `tls_spki` appears nowhere in §7.
3. **Deleted the trust model** — pinned the peer on JSON-parse success alone, with **no** §7.3 hardware attestation and **no** §7.4 six-emoji SAS. A parent scanning an attacker's QR would pin the attacker's keys, making the attacker the audience for sealed-box events and the pinned sync channel — the exact **H3 pubkey-substitution** attack (ATTACKS.md, CRITICAL) that §7 exists to close.

Dual review (crypto-reviewer + tech-lead) caught all three before merge; PR #64 was closed and #23 re-labeled `agent-blocked`. This ADR closes the root cause: it **ratifies §7 as canon**, records the rejected inversion and *why* it is forbidden, and **re-scopes the parent-side pairing work** so the follow-up issues are correctly shaped and correctly gated.

**Why the direction is not a free choice — the attestation binding forces it.** The anti-device-swap guarantee (DEFENSES #14, Pattern E; closes H3) requires the child to return a hardware **attestation cert chain** whose challenge is a **fresh parent-issued nonce**:

- The nonce must reach the child to become its StrongBox `setAttestationChallenge` input (§7.2 step 2) ⇒ the nonce flows **parent → child** ⇒ the **parent must be the displayer** of the QR that carries it (§7.1).
- The attestation response is a chain of ~3 DER certificates (commonly >2 KB) — **too large to encode in a scannable QR** — so it must return over the **network POST** (§7.2 step 4), not via a child-displayed QR.

So §7's direction — **parent displays, child scans + POSTs** — is the canon choice. A *pure* child-displayed QR cannot carry the nonce challenge or the >2 KB cert chain, so it necessarily drops attestation. A *network-assisted* parent-scans-child variant (the child QR bootstraps only a session/endpoint, then the nonce and cert chain flow over the network) *could* preserve attestation in principle — but it buys nothing over §7's flow while adding round-trips, so it is rejected for **complexity with no benefit**, not impossibility (D4).

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

`provisioning_nonce` is CSPRNG-fresh and **single-use per pair attempt**. The freshly-DPC-provisioned child **scans** it, generates its keypairs with the nonce as `setAttestationChallenge` and `setUnlockedDeviceRequired(true)` (§7.2 step 2 — follow §7.2 verbatim for the keygen flags), and **POSTs** the §7.2 response (`child_ed25519_pub`, `child_x25519_pub`, `child_attestation_cert_chain`, `child_binding_sig`) to the parent's pairing endpoint discovered via `transport_hints`. *(Amended by ADR-032: the attested key is the StrongBox EC P-256 `K_bind`; the Ed25519/X25519 identity keys are TEE-resident and bound to it by `child_binding_sig` — StrongBox cannot generate Curve25519.)*

**D2 — Trust model = hardware attestation AND six-emoji SAS; pin only after both pass; any failure refuses the pair (fail-closed).** Per §7.3 the parent MUST verify, and refuse on any failure:
1. cert chain parses and roots in the **Google Hardware Attestation root**;
2. leaf attestation challenge == the `provisioning_nonce` it issued;
3. `verifiedBootState == VERIFIED` (GREEN), bootloader **locked**, device is an **allow-listed model**, attestation security level == **STRONGBOX**;
4. leaf-cert public key == `child_ed25519_pub`. *(Amended by ADR-032: the attested leaf is now the P-256 `K_bind`, so check 4 reads "leaf == `K_bind`" plus a **new check 4b** verifying `child_binding_sig` (ECDSA-P-256 by `K_bind`) over the Curve25519 keys + nonce — see the ADR-032 banner above. StrongBox cannot hold Curve25519, so an Ed25519 leaf could never satisfy the old check 4.)*

Then per §7.4 (as amended, D2a) both sides derive `HKDF-SHA256(salt="openwarden-pair-v1", ikm=parent_ed25519_pub‖parent_x25519_pub‖child_ed25519_pub‖child_x25519_pub, info=provisioning_nonce)[0..15]` → a **six-emoji SAS**. The parent visually compares and taps **Match** → the child pubkeys enter `pinned` state; **Mismatch → abort pair + surface MITM warning.** No pin occurs on attestation-only or SAS-only success.

**D2a — Amends §7.4: the SAS binds every pinned key (X25519-binding).** Review of this ADR caught a latent flaw in §7 as originally written: attestation (§7.3 check 4) binds only `child_ed25519_pub`, and the original SAS hashed only `parent_ed25519_pub‖child_ed25519_pub` — yet pairing **pins both** Ed25519 **and** X25519 keys (§7.2 response, §7.5). The pairing endpoint is pre-pin and mDNS-discovered (untrusted by definition), so a LAN MITM could relay the child POST, pass the Ed25519 key + attestation chain through untouched (unforgeable), and **swap `child_x25519_pub` for its own** — attestation still passes, the old SAS still matches, and the parent pins the attacker's X25519 key (the sealed-box audience). The fix: §7.4's HKDF `ikm` now concatenates **all four pinned keys in fixed order** — `parent_ed25519_pub ‖ parent_x25519_pub ‖ child_ed25519_pub ‖ child_x25519_pub`. Any substituted key — including either X25519 — changes the six emojis, so the human compare catches it. This is the one normative change in this ADR (everything else ratifies §7 unchanged); maintainer-approved.

**D3 — Wire schema is §7.1/§7.2 as written; `child_attestation_cert_chain` is mandatory; there is NO `tls_spki` field.** The invented `tls_spki` of PR #64 is rejected. The TLS sync channel is pinned **against the pinned pubkey** post-pairing (§4.1 / §4 intro — "cert pinning against pubkey"), **not** via a separate SHA-256 SPKI hash carried in the pairing QR. Introducing a pairing wire-format field is a `proto` change and is **agent-blocked** by definition.

**D4 — The parent-scans-child inversion is rejected (canon direction = §7).** A *pure* child-displayed-QR inversion is impossible with attestation: it breaks the nonce→attestation-challenge binding (no parent→child channel for the nonce) and cannot carry the >2 KB cert chain in a QR. A *network-assisted* inversion is technically possible but pointless — it re-homes the nonce + cert chain over the network, buying nothing over §7 while adding round-trips. Either way the inversion is rejected; any future proposal to revisit it MUST come as its own ADR that explicitly shows how attestation and the four-key SAS survive.

**D5 — Re-scope the parent-side pairing work.** The parent half of pairing is **not** "scan a QR." It is, and follow-up issues MUST be cut as:
- (a) generate the pairing session — CSPRNG `provisioning_nonce`, ephemeral parent material, **render** the §7.1 QR;
- (b) run the pairing **endpoint** that receives the §7.2 child POST (LAN, mDNS-discovered);
- (c) **verify** the §7.3 attestation chain (D2 checks 1–4);
- (d) derive + display the §7.4 SAS, capture **Match/Mismatch**;
- (e) **pin** `(child_ed25519_pub, child_x25519_pub)` only on Match.

All of (a)–(e) are crypto/`proto` surface ⇒ **`agent-blocked`**, human-implemented, ADR-and-test-gated. Issue **#23 ("parent scans child QR") is wrong as written** and stays closed-for-rescope; replace it with issues for (a)–(e). The child half (scan + StrongBox keygen + attestation POST) is the existing `agent-blocked` issues #20/#21/#22.

**D6 — Byte-level key validation is a conformance requirement.** Wherever a pubkey is parsed (parent verifying the POST, or any consumer), the validator MUST base64url-**decode**, assert the decoded length is **exactly 32 bytes**, validate the alphabet, and reject malformed curve encodings — not merely check string length (PR #64 accepted any ≥43-char string, so a 32.25-byte blob passed). Test vectors land under `docs/test-vectors/pairing/` (`pair-01` valid; `pair-03-fail-malformed-pubkey` for the malformed-pubkey case — a **distinct** name from §9.1's existing `pair-02-fail-not-verified` attestation-refusal vector, which is a different failure class), per §9.1.

**D7 — Device scope: attestation is mandatory on the v0.x Pixel-class tier.** Verification requires StrongBox + a Google-root chain + an allow-listed model (Pixel 7; ADR-001 as amended by ADR-023). A SAS-only fallback for non-attesting devices is explicitly **out of scope** (v3+); on today's tier, attestation failure refuses the pair. *(Amended by ADR-029: the accepted attestation tier widens to include the committed Samsung/OnePlus targets at allow-listed-OEM-root + TEE level, with the downgrade disclosed; **attestation stays mandatory** on every committed device and **SAS-only stays out of scope** — we never drop to SAS-only. Refuse-closed on unknown root / `SOFTWARE` level.)*

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
- **The parent must run a pairing endpoint**, which is more surface than a pure QR scan — but a network channel for the cert chain is intrinsic to *any* attestation-preserving flow (Context), so the cost is fundamental, not incidental to this particular direction.
- **This ADR ships no code.** It is a design-ratification + re-scope record (cf. ADR-015/017). The implementation lands in the D5 follow-up issues, each with the tests below.

## Blocking before implementation (binds the D5 issues, not this ratification)

Review (PR #65, Codex lens) flagged conformance behavior that §7 leaves underspecified. These do **not** block ratifying the design + trust model above, but each D5 implementation issue MUST resolve its items, fail-closed, before code lands — they are recorded here so they are not lost:

- **Nonce lifetime + single-use consumption.** Define the `provisioning_nonce` TTL, whether it persists across a parent restart, the exact point it is consumed, and that a failed attestation **or** SAS mismatch **burns** it (no reuse on retry). Canon today says only "CSPRNG-fresh, single-use" (§7.1).
- **Pairing-endpoint pre-auth.** The endpoint receives the child POST *before* any pin exists (mDNS-discovered, spoofable). Specify accepted-source rules, rate-limiting, and a per-attempt session token so an attacker cannot flood or race it. (The four-key SAS, D2a, stops a *silent* key swap; it does not by itself throttle a hostile endpoint.)
- **Full-transcript replay binding.** Beyond the single-use nonce and the four-key SAS, bind the whole pairing transcript so a replayed or spliced POST cannot pin.
- **Abort / cleanup UX (fail-closed).** On attestation failure or SAS mismatch: wipe the pending nonce/session, leave **nothing** half-pinned on either parent or child, and define retry/lockout. A partial or aborted pairing MUST resolve to **unpaired**, never a stuck or half-trusted state.

## Test plan (binds the D5 implementation issues, not this ADR)

- **Attestation verify:** good Google-rooted chain with matching nonce + GREEN verified-boot + locked bootloader + STRONGBOX + allow-listed model + leaf-pubkey == `child_ed25519_pub` ⇒ proceed; **each** of {wrong root, nonce mismatch, `verifiedBootState != VERIFIED`, unlocked bootloader, non-allow-listed model, security level != STRONGBOX, leaf-pubkey != `child_ed25519_pub`} ⇒ **refuse pair** (deterministic, seam-injected, fail-closed). *(Amended by ADR-032: substitute "leaf-pubkey == `child_ed25519_pub`" → "leaf == `K_bind` (P-256)" and add a `child_binding_sig` accept + reject (wrong key / substituted Curve25519 pubkey / stale nonce) case — see ADR-032 test plan, vectors `pair-06`/`pair-07`/`pair-08`.)*
- **SAS (four-key binding, D2a):** the HKDF-derived six-emoji sequence is identical on both sides for matching inputs; a substitution of **any** pinned key — `parent_ed25519_pub`, `parent_x25519_pub`, `child_ed25519_pub`, **or** `child_x25519_pub` — changes the SAS (drives the Mismatch → abort + MITM-warning path). The X25519 cases are the regression test for the flaw this ADR closes.
- **Single-use nonce:** a replayed `provisioning_nonce` from a prior attempt ⇒ refuse.
- **Byte-level pubkey validation (D6):** `pair-01` valid (exactly 32 decoded bytes) accepted; `pair-03-fail-malformed-pubkey` (non-base64url, wrong length, over-long, bad curve point) rejected — vectors in `docs/test-vectors/pairing/`.

## Cross-refs
- [docs/PROTOCOL.md](../PROTOCOL.md) §7.1–§7.5 (ratified here; §7.4 amended for four-key SAS, D2a), §4.1 / §4 intro (TLS pin against pubkey), §8 (versioning), §9.1 (test vectors)
- [ADR-001](001-one-device-tier.md) / [ADR-023](023-enforcement-floor-tiers.md) (Pixel-class attestation tier)
- [ADR-015](015-event-log-crypto-primitives.md) **(Status: Proposed)** (X25519 sealed-box audience = the pinned child key; the §6 sealed-box behavior relied on here is already normative in CRYPTO.md/PROTOCOL.md independent of ADR-015's status)
- [ADR-019](019-canonical-signing-invariant.md) (canonical signing of subsequent bundles to the pinned key)
- docs/DEFENSES.md #4/#9/#14 + Pattern E; docs/ATTACKS.md H3
- issue #23 (closed-for-rescope), PR #64 (rejected inversion); CLAUDE.md (fail-closed; no content monitoring)

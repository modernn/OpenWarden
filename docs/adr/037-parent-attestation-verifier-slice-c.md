# ADR-037: Parent §7.3 attestation verifier (slice c) — D2 checks 1–4 + 4b, seam-injected, burns the nonce on failure
Status: Accepted
Date: 2026-06-22
Implements: **ADR-025 D5(c)** (the §7.3 *verify* slice of the parent pairing flow) and the parent-side half of **ADR-032 D4** (the `K_bind` leaf + `child_binding_sig` check 4b). Discharges the ADR-036 D4 **HARD acceptance criterion** that a failed attestation **burns the nonce**.
Builds on: **ADR-035** (slice (a) — session + nonce + §7.1 QR), **ADR-036** (slice (b) — the endpoint + `ValidatedPairingPost` handoff seam this consumes)
Relates: docs/PROTOCOL.md §7.3 (the five checks ratified here), §9.1 (vectors); ADR-025 D2/D6/D7; **ADR-029** (Tier-2 posture — the allow-list this leaves injectable so Tier-2 slots in); **ADR-032** (still *Proposed* — the bench-confirm gate below); docs/DEFENSES.md #4/#9/#14 + Pattern E; docs/ATTACKS.md H3; issue #96

## Context

ADR-025 D5 split the parent half of pairing into five slices. (a) mints the nonce + QR (ADR-035); (b) is the hardened pre-pin endpoint that shape-validates the §7.2 child POST and hands a `ValidatedPairingPost` to an `AttestationVerifier` seam (ADR-036). (b) shipped with a fail-closed default — `RefuseAllAttestationVerifier` — that refuses every pair until this slice lands.

**Slice (c) is the §7.3 attestation verify** — the trust decision that, with the SAS (slice d, #97) and pinning (slice e, #98), closes H3 (pubkey substitution / device swap, ATTACKS.md CRITICAL). Per PROTOCOL §7.3 (as amended by ADR-032 for the StrongBox/Curve25519 split) the parent MUST, refusing on any failure:

1. cert chain parses + roots in an **allow-listed attestation root**;
2. leaf attestation challenge == the issued `provisioning_nonce`;
3. `verifiedBootState == VERIFIED` (GREEN) + bootloader **locked** + **allow-listed model** + **`securityLevel == STRONGBOX`**;
4. leaf-cert public key is the attested **`K_bind`** (EC P-256), not `child_ed25519_pub`;
4b. **`child_binding_sig`** verifies as ECDSA-P-256 by that leaf (`K_bind`) over `JCS{v, child_ed25519_pub, child_x25519_pub, provisioning_nonce}` — the cryptographic link from attested hardware to the pinned Curve25519 identity (ADR-032 D2/D4).

`Accepted` means **only** "attestation passed, may proceed to the §7.4 SAS"; this slice derives no SAS and pins nothing.

## Decision

Adopt a **pure, seam-injected verifier core + thin real-crypto platform seams**, mirroring the ADR-033 root-key precedent (Bouncy-Castle crypto in `androidMain`, host-tested via `androidUnitTest`; pure logic in `commonMain`, host-tested via `commonTest`). Six parts; the three maintainer forks (attended, issue #96) are recorded as D2/D3/D5.

**D1 — A pure decision core, `Section73AttestationVerifier`.** It implements the slice-(b) `AttestationVerifier` interface and runs checks 1–4b over an **`AttestationEvidence`** value (the policy-relevant fields a parser extracted) + the live session nonce + an **`AttestationPolicy`** (allow-listed roots / models / security levels). It owns **no** ASN.1, X.509, or signature math — those are seams (D4). Every check is fail-closed and order-independent of the others; the first failure refuses. This is the "deterministic, seam-injected verifier" the issue requires: the whole 1–4b matrix is exercised in `commonTest` by injecting crafted evidence + a programmable signature seam, no device and no real chain needed.

**D2 — (fork 1, maintainer-approved) Burn-on-failure lives in the verifier via an injected `PairingNonceBurner`, not in the endpoint.** ADR-036 D4 made "a failed attestation burns the nonce (no reuse on retry)" a HARD criterion on this issue, and deliberately left the burn to slice (c). The verifier is constructed with a narrow `PairingNonceBurner` (`fun burn()`) and calls it on **every** `Refused` outcome (and never on `Accepted`). The merged slice-(b) `PairingEndpoint`/`PairingServer` and their tests are **untouched** — the alternative (a `burnSession` flag on `AttestationOutcome.Refused` that the endpoint actions) was rejected because it re-opens already-merged, already-reviewed code for no gain. In the Android wiring the burner is `{ sessionAccess.cancel() }`, called inside the same `sessionLock`-serialized `handle()` (ADR-036 D5), so the burn cannot race a concurrent POST. A genuine attestation failure thus forces a fresh QR — the single-use-nonce guarantee survives the slice boundary.

**D3 — (fork 2, maintainer-approved) Tier-1 allow-lists now, injectable so ADR-029 Tier-2 slots in later.** `AttestationPolicy` is a constructor value, not a hardcoded global. The shipped `AttestationPolicy.tier1(...)` default accepts the **Google Hardware Attestation root** (caller-supplied pinned SPKI), **`STRONGBOX`** only, and the **Pixel-7 model** allow-list — the v0.x Pixel-class tier (ADR-001/023, ADR-025 D7). ADR-029's Tier-2 widening (Samsung Knox + OnePlus roots, `TRUSTED_ENVIRONMENT` level) is a future `AttestationPolicy.tier2(...)` + the OEM root pins; it is **not** wired here (smaller blast radius, and the OEM root certs are not yet pinned in-repo). Refuse-closed on any unknown root or `SOFTWARE` level holds by construction (not in the allow-set ⇒ refused).

**D4 — Two real-crypto seams, in `androidMain`, host-testable.**
- **`AttestationChainParser.parse(certChainBase64Der): AttestationEvidence?`** — `BouncyCastleAttestationChainParser` decodes each `base64(DER)` cert (JDK `Base64`), builds the X.509 chain (`CertificateFactory`), verifies each cert is signed by its issuer above it (chain integrity; any signature/parse failure ⇒ `null`, fail-closed), and parses the Android Key Attestation extension (OID `1.3.6.1.4.1.11129.2.1.17`) for the challenge, `securityLevel`, `RootOfTrust` (`verifiedBootState`, `deviceLocked`), and `attestationIdModel`. It returns the **top cert's SPKI** as `rootSpkiDer` (the verifier pins the anchor — D1) and the **leaf SPKI** as `K_bind`, with `leafIsEcP256` set iff the leaf key is secp256r1. The risky ASN.1 KeyDescription extraction is a separately-testable internal function.
- **`EcdsaP256BindingVerifier.verify(leafSpkiDer, signedBytes, derSignature): Boolean`** — `JdkEcdsaP256BindingVerifier` verifies a DER ECDSA / `SHA256withECDSA` signature by the leaf key; returns `false` on any failure, never throws. ECDSA `(r, n−s)` malleability is irrelevant here (one-shot accept/reject, never a uniqueness/replay key — ADR-032 D2).

`AndroidAttestationVerifierFactory` assembles a `Section73AttestationVerifier` from these + a policy + a burner, so the slice-(e) coordinator wires the real verifier into `PairingServer` with one call (this slice does not modify the server). iOS actuals are deferred (host-gated off, as with ADR-033).

**D5 — (fork 3, maintainer-approved) Land now; ADR-032 stays *Proposed*; the real-Pixel chain vector is a tracked follow-up.** ADR-032's bench-confirm gate (real Pixel-7 StrongBox/Curve25519 behavior) is still open and no real attestation chain exists in-repo. We land the verifier + seams + the deterministic matrix + the **real-ECDSA check-4b** test now (it unblocks slices d/e), and **defer the real-Pixel `pair-06` capture + a full real-chain parser round-trip** to the follow-up that pairs with the bench confirmation. ADR-032 is **not** flipped to Accepted by this slice — only its parent-verify shape is implemented behind the still-open gate.

**The check-1 trust model this slice ships is *internal chain-link signatures* + a *byte-pin of the top cert's SPKI* — and nothing more.** It deliberately does **not** perform: certificate **validity-window** checks (`notBefore`/`notAfter`); **revocation** (the Google Key-Attestation CRL / status list); or X.509 **path constraints** (`basicConstraints`/CA/`keyUsage`/`pathLen`/EKU) via a real `CertPathValidator`. The SPKI-pin closes the attacker-supplied-intermediate hole **for a correct pin** (an attacker cannot link a hostile leaf up to the genuine Google-root SPKI without the root key), but an **expired or revoked-but-correctly-chained** cert is accepted. These three validations — **expiry, revocation, full path validation** — are **HARD gates that MUST land before pairing is declared production-ready** (with the ADR-032 bench confirm + the real `pair-06` capture). Landing slice (c) records the maintainer's explicit acceptance of this as a disclosed pre-production residual.

**D7 — (amendment 2026-06-26, issue #120) A zero-length pinned root is the "no root pinned" sentinel, never a wildcard.** `AttestationPolicy.isAllowedRoot` originally compared the parsed top SPKI to each pinned anchor with `contentEquals`. A zero-length entry in `allowedRootSpkiDer` would therefore `contentEquals` a zero-length *parsed* `rootSpkiDer` and **accept** — silently flipping a *disabled* gate to pass. This is safe in production today only by coincidence: the sole real parser (`BouncyCastleAttestationChainParser`) emits `cert.publicKey.encoded`, which is never empty-but-non-null (and PR #118's `AndroidPairingFactory` already wires an explicit refuse-all-with-burn verifier when `googleRootSpkiDer` is empty). It is a fragile invariant — a future parser/seam (the iOS actual, a mock wired by mistake, a refactor defaulting `rootSpkiDer` to empty) returning an empty SPKI would resurrect the accept. The defense-in-depth, on the merged slice-c crypto, is two-layer and fail-closed by construction:
- **Policy layer:** `AttestationPolicy` **drops zero-length entries** from `allowedRootSpkiDer` at construction, and `isAllowedRoot` returns `false` for an empty argument. A pin list of only empty entries (incl. `tier1(ByteArray(0))`) thus collapses to "no allow-listed root ⇒ every chain refused".
- **Verifier layer:** `Section73AttestationVerifier` refuses (`untrusted-root`) when the parsed `rootSpkiDer.isEmpty()`, before consulting the policy — so an empty parsed root is refused even if a pin were somehow empty. Burn-on-refuse (D2) is preserved on both paths. No behavior change for any non-empty pin/root; this only closes the empty==empty coincidence. Tests: empty pin + valid evidence ⇒ refuse+burn; empty parsed root (vs tier1 and vs empty pin) ⇒ refuse+burn; direct `AttestationPolicy` asserts that empty pins drop and a real pin still matches.

**D6 — Check-4 reduction + the binding body's exact bytes (drift guards).** Because the §7.2 POST carries **no** standalone `K_bind` field (ADR-032 D3) — `K_bind` *is* the attestation leaf key — check 4 ("leaf == `K_bind`") reduces to "the leaf key is a well-formed EC P-256 key, and it is the key check 4b verifies against." There is no second source to mismatch; 4b is the cryptographic bind. The check-4b signing input is **exactly** `JCS{v, child_ed25519_pub, child_x25519_pub, provisioning_nonce}` where the three string values are the **verbatim wire strings** (`child_*_pub` as POSTed; `provisioning_nonce` as the **unpadded base64url(32)** the §7.1 QR carried — `Base64Url.encode(session.nonce())`), canonicalized by the one `com.openwarden.proto.Canonical` JCS used everywhere else. The signer (child #22) and verifier MUST agree byte-for-byte; a twin-signer test (a P-256 `K_bind` stand-in signs the same JCS; the real verifier accepts) pins this, and the substituted-`ed`/substituted-`x`/stale-`nonce` reject cases prove the body actually covers each field.

## Consequences

Good:
- The slice-(b) fail-closed default is replaced by a real §7.3 verifier; slices (d)/(e) are unblocked.
- The entire 1–4b decision matrix is host-deterministic (seam-injected) — no device, no real chain, no flaky native dep — and the highest-risk piece (the check-4b JCS byte-agreement) is proven with **real** ECDSA-P-256.
- Burn-on-failure satisfies ADR-036 D4 without re-opening merged slice-(b) code.
- The allow-list is data, so Tier-2 (ADR-029) is a policy value + root pins later, not a verifier rewrite.
- **(amendment, #120)** The empty-SPKI sentinel coincidence is closed at both the policy and verifier layers (D7); a zero-length pin can no longer act as a wildcard even if a future parser emits an empty root.

Bad / accepted residuals (disclosed):
- **Landed ahead of the ADR-032 Pixel-7 bench confirmation (D5).** A maintainer MUST still confirm the StrongBox/Curve25519 behavior on a real Pixel 7 and capture a real chain; `pair-06` (real attested chain) and a full real-chain parser round-trip are the tracked follow-up. The `BouncyCastleAttestationChainParser`'s ASN.1 field extraction is unit-tested against bcprov-built synthetic `KeyDescription` bytes, **not** yet against a real device chain — a real chain can still surface an extraction quirk.
- **The production Google-root SPKI pin is supplied by the caller and not yet committed** (it rides the bench-confirm capture). Until then the policy is exercised with a synthetic root in tests; a misconfigured/empty root set fails **closed** (no allow-listed root ⇒ refuse).
- **Model provenance.** `attestationIdModel` (tag 714) is only present on device-ID-attested keys; if absent the verifier refuses (model `null` ∉ allow-list) — fail-closed, possibly stricter than a real chain warrants. Revisit with the real capture.
- **Tier-2 not wired (D3); iOS seam deferred (D4).** Both tracked, neither regressed.

**Hard gates before production pairing (NOT satisfied by this slice — must land with the bench-confirm follow-up):**
1. **Certificate validity-window** validation (`notBefore`/`notAfter`) on every cert in the chain.
2. **Revocation** against the Google Key-Attestation CRL / status list.
3. **Full X.509 path validation** (`CertPathValidator`: `basicConstraints`/CA/`keyUsage`/`pathLen`/EKU), not just internal link signatures + top-SPKI pin.
4. **Commit + verify the real Google Hardware Attestation root SPKI** as the Tier-1 `tier1(...)` pin (today caller-supplied, uncommitted).
5. **Confirm `attestationIdModel` (tag 714) presence on real Pixel-7 attestations** — or relax check 3's model sub-condition — so genuine devices are not refused.

These are flagged from the dual review (crypto-reviewer HIGH-1 / MED-1 / MED-2) and accepted as disclosed pre-production residuals at this slice's merge.

## Test plan (this slice)

- **Deterministic core (`commonTest`, seam-injected):** with a fake parser + programmable signature seam + fake burner, assert **accept** on a good evidence set, and **refuse + burn** on each of {chain-parse `null`, non-allow-listed root, challenge≠nonce, `verifiedBootState≠VERIFIED`, bootloader unlocked, model not allow-listed, `securityLevel≠STRONGBOX`, leaf not P-256, binding-sig false}. Assert **burn fires on every refuse and never on accept** (ADR-036 D4).
- **Real ECDSA-P-256 check 4b (`androidUnitTest`):** a P-256 `K_bind` twin signs `JCS{v,ed,x,nonce}`; the real `JdkEcdsaP256BindingVerifier` **accepts**. **Reject** (real crypto): signature by a different P-256 key; body with `child_ed25519_pub` or `child_x25519_pub` substituted (sig no longer covers the presented keys); stale `provisioning_nonce` in the body; malformed/empty signature bytes. This proves parent↔child JCS byte-agreement (D6).
- **ASN.1 extraction (`androidUnitTest`):** a bcprov-built synthetic `KeyDescription` extension round-trips through the parser's extractor → correct challenge / securityLevel / verifiedBootState / deviceLocked / model; truncated/garbage extension ⇒ `null`.
- **Parser fail-closed decode paths (`androidUnitTest`):** empty chain, non-base64 cert, valid-base64 non-DER bytes, broken chain signature ⇒ `null`.
- **Vectors** under `docs/test-vectors/pairing/`: `pair-06-binding-ok` (valid `ChildKeyBinding` + `child_binding_sig`, accept-shape), `pair-07-fail-binding-sig` (sig over substituted keys / by wrong key), `pair-08-fail-binding-stale-nonce`. The real-Pixel attested-chain body of `pair-06` is the deferred bench-confirm follow-up (D5).

## Cross-refs
- [docs/PROTOCOL.md](../PROTOCOL.md) §7.3 (checks 1–4b), §7.4 (SAS — slice d), §9.1 (vectors)
- [ADR-025](025-pairing-handshake-direction-attestation-sas.md) D5(c)/D2/D6/D7
- [ADR-032](032-child-identity-hardware-binding-strongbox-p256.md) D2/D4/D4a (still *Proposed*; bench-confirm gate open — D5 here)
- [ADR-035](035-parent-pairing-session-nonce-qr.md) / [ADR-036](036-parent-pairing-endpoint-pre-auth.md) (slices a/b — the session + handoff seam this consumes)
- [ADR-029](029-tier2-attestation-posture.md) (Tier-2 — left injectable, D3)
- [ADR-033](033-parent-root-key-recovery-phrase.md) (the Bouncy-Castle-in-androidMain, host-tested precedent this mirrors)
- docs/DEFENSES.md #4/#9/#14 + Pattern E; docs/ATTACKS.md H3; issue #96

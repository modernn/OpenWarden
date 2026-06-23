# ADR-040: Child verifies the policy bundle over the received bytes (discharges the ADR-019 child residual)

Status: Accepted
Date: 2026-06-23
Supersedes/relates: ADR-019 (sign-and-transmit-exact-bytes; this implements its D2/D3 on the child), ADR-034 (the residual this discharges), ADR-017 (admission ordering)

## Context

ADR-019 is **Accepted canon**: the verifier verifies the Ed25519 signature over the bytes it
**received**, verbatim (D2), and `SigningInput.forDocument` — canonicalize the *received* JSON
object, never a re-encoding of the verifier's own typed model — is the canonical verifier entry
point (D3). PROTOCOL.md §3.1 states the rule as a MUST: *"A verifier MUST NOT parse a document
and re-canonicalize it before verifying."*

The child violated this. `BundleVerifier.canonicalBody(bundle)` took the **typed** `SignedBundle`,
re-serialized it with `Json { encodeDefaults = true; explicitNulls = false }`, and canonicalized
*that* (BundleVerifier.kt:46-49). `ApiServer` received the wire body straight into the typed model
(`call.receive<SignedBundle>()`, with `ignoreUnknownKeys = true`), discarding the received bytes.
So the child verified over a re-canonicalization of its own model, not over what the parent signed.

This was the maintainer-accepted residual recorded at PR #89 (ADR-034 Consequences, crypto-review
finding 1) and tracked as issue #91. It was *correct today* only because the parent `signingBytes`
and the child re-canonicalization were pinned byte-identical by the golden-vector twin tests plus a
libsodium-verify KAT — a fragile guarantee. Any field/default/serializer/unknown-field drift would
silently `SIG_FAIL` a genuinely valid bundle (the ADR-017 JC1 divergence failure mode arriving
through a schema-shape door), and `ignoreUnknownKeys = true` means a parent-signed field the child
does not model is **dropped** before re-canonicalization → its absence flips the signature.

The issue offered two options: (a) move the child to verify over the received bytes; (b) ratify the
parse-then-re-canonicalize approach as an explicit ADR-019 exception + extend the golden vector to a
unicode bundle. Option (b) **contradicts Accepted ADR-019 and PROTOCOL §3.1's MUST NOT**, so taking
it would require *superseding* canon and would weaken a signing-integrity defense. We take (a).

## Decision

**D1 — The received JSON object is the crypto authority.** The child parses the `/policy` body to a
`JsonObject` and treats *that* as the signed document. JC1 integer bounds, canonical size, and the
Ed25519 signature are all computed over the received object, never over a re-serialized typed model.

**D2 — Verify first, parse second, apply third (ADR-019 D2).** Order on the child:
1. parse the body to a `JsonObject` (structural JSON parse only) — unparseable → `MALFORMED`;
2. `requireAllIntegersJcsSafe(doc)` over the whole tree (JC1, ADR-017) → `MALFORMED`;
3. `signingBytes := Canonical.canonicalizeWithout(doc, "sig")`; size ≤ 65536 → else `MALFORMED`;
4. audience: `doc["child_device_id"]` == my id → else `MALFORMED`;
5. `Ed25519.verify(signingBytes, doc["sig"], pinnedParentKey)` → else `SIG_FAIL` (fail-closed);
6. **only now** decode the typed `SignedBundle` from the *same* `doc` (post-verification parse;
   a verified-but-unparseable body is rejected, never applied);
7. floor / genesis / replay decisions on the typed bundle (unchanged ADR-017 logic).

**D3 — `BundleVerifier.verifyDocument(receivedDoc, pubkey)` is the live verifier.** It canonicalizes
the received object minus `sig` and verifies the detached signature carried in `doc["sig"]`. The old
`canonicalBody(typedBundle)` / `verify(typedBundle)` path is retained **only** as a signer-side test
convenience for building golden vectors (ADR-019 D3: the typed model is a post-verification parse
target, never the verifier's signing input).

**D4 — `receivedDoc` is a required, non-defaulted input to `decide()`/`admit()`.** There is no
"derive the doc from the typed bundle" fallback: a silent revert to typed re-canonicalization is the
exact footgun this ADR removes. Callers (the live `ApiServer`, and tests) pass the received object
explicitly; the pure decision cannot run without the bytes it must verify.

**D5 — No `proto`/wire change, no new primitive.** Reuses the existing child `Canonical` port
(byte-rule-identical to `proto/Canonical.kt`) and `Ed25519`. The wire format is unchanged: a single
JSON object with `sig` as an embedded field; "verify over received bytes" means *canonicalize the
received object minus `sig`*, which is idempotent on already-canonical input the parent transmits.

## Consequences

- Parent↔child interop is byte-stable against schema-shape drift: an extra parent-signed field, a
  defaulted field's presence/absence, or key reordering on the wire no longer flips a valid
  signature, because the child verifies the bytes the parent committed to — there is no second
  canonicalizer derived from a divergent typed model. A new regression test pins this (a wire
  document carrying a signed field the child's typed model does not declare verifies under
  `verifyDocument` and would `SIG_FAIL` under the old typed re-canonicalization path).
- Fail-closed is strengthened, not relaxed: every new branch (unparseable body, JC1 overflow in any
  field, oversize, audience miss, bad sig, verified-but-unparseable) rejects without applying.
- The child still owns one canonicalizer that MUST stay byte-identical to the parent signer's
  (`If you change one, change both`). This ADR moves *what is fed into it* (received object, not
  typed re-encode); it does not change the JCS rules.
- Discharges the ADR-034 / issue #91 residual. The remaining ADR-034 child residual — `not_before`/
  `not_after` freshness enforcement (#90) — is handled separately (ADR-041).

## References
ADR-019, ADR-017, ADR-034; `child-android/.../BundleVerifier.kt`, `Canonical.kt`, `PolicyAdmission.kt`,
`ApiServer.kt`; `proto/SigningInput.kt` (`forDocument`); PROTOCOL.md §3.1; issue #91.

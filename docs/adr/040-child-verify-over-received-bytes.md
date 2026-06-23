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

**D6 — "Verify over received bytes" = canonicalize-the-parsed-object (the ADR-019 D2 variant chosen).**
ADR-019 D2 offers two forms: `SigningInput.forDocument` on the received JSON, *or* directly over the
raw request body. Because `sig` is an **embedded** field, the raw-body form is not cleanly available
(stripping `sig` from raw bytes itself requires parsing). We therefore verify by canonicalizing the
parsed `JsonObject` minus `sig` — the canon-sanctioned `forDocument` variant. Its equivalence to the
parent's transmitted bytes rests on the parent signer being, and remaining, the **byte-identical**
canonical port (`proto/SigningInput.forBundle`); the cross-impl golden-hex + libsodium-KAT tests are
the standing merge gate that pins this, and "if you change one, change both" is the code guardrail.
A parent that ever transmits non-canonical-but-valid JSON (e.g. `A` for `A`) would `SIG_FAIL` a
genuine bundle — a **liveness** failure (rejects a valid bundle), never a **safety** one (never
admits a bad bundle), so it is fail-closed and acceptable.

**D7 — Fail-closed structural gates over the received document, then parse.** Order on the child is
exactly: parse-to-`JsonObject` → JC1 (`requireAllIntegersJcsSafe`, whole tree) → **null-rejection**
(`requireNoNulls`, §3.1 rule 6 / ADR-019 D4 — discharged here, no longer a follow-up) → canonical
size → version (`v` read from the doc) → audience (`child_device_id` read from the doc) → Ed25519
verify → **then** typed decode → floor/genesis/apply. The pre-signature gates read the wire object
directly and are **reject-only**, so a forged pre-verify field can only cause a rejection, never an
apply (apply is strictly gated behind a verified `Accept`); a verified-but-unparseable body is
rejected `MALFORMED`, never applied (ADR-019 D2 "verify first, parse second, apply third").

**D8 — Duplicate keys are fail-closed; unknown fields are ignored (with a schema-evolution rule).**
kotlinx `parseToJsonElement` collapses duplicate object keys last-wins; both the signature check and
the typed decode see the *same* collapsed object, and any duplicate that changes an effective value
yields different canonical bytes → `SIG_FAIL`. No bypass (pinned by a regression test). A
**cross-language** verifier MUST adopt the same (reject-or-last-wins) rule or interop will diverge.
Separately, `ignoreUnknownKeys = true` means an unknown parent-signed field is honored in the
signature but **ignored** by enforcement (the additive-field interop win). To keep fail-closed under
schema growth: **any future field whose absence would *weaken* enforcement MUST ship with a `v` bump**
(PROTOCOL §8 format break) so an older child rejects (`v != 1` → `MALFORMED`) rather than silently
under-enforces — never add a tightening field as an ignorable `v1` field.

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
- Also discharges the ADR-019 D4 `null`-rejection follow-up on the child verify path (D7): a `null`
  anywhere in the received document fails closed before verification (PROTOCOL §3.1 rule 6).
- `/policy` now bounds the request body (`MAX_POLICY_BODY_BYTES`, fail-closed Content-Length gate)
  before buffering — closing a pre-auth LAN OOM-DoS vector the old `call.receive` path also had.
- Tests added/strengthened: drift regression (unmodeled field) at both the verifier unit **and** the
  full `admit()` pipeline; verified-but-unparseable → `MALFORMED` never applied; a unicode/escaping
  golden vector through `verifyDocument`; duplicate-key fail-closed; null-rejection; and the libsodium
  interop KATs re-pinned to the live `verifyDocument` path.
- **Out of scope (tracked follow-up):** `/heartbeat`, `/lock`, `/unlock` still verify their signed
  objects over a typed re-serialization (`HeartbeatVerifier` / command verifiers) — the same ADR-019
  tension this ADR fixes for `/policy`. They should get the verify-over-received-bytes treatment in a
  separate change (ADR-024/ADR-030 artifacts).
- Discharges the ADR-034 / issue #91 residual. The remaining ADR-034 child residual — `not_before`/
  `not_after` freshness enforcement (#90) — is handled separately (ADR-041).

## References
ADR-019, ADR-017, ADR-034; `child-android/.../BundleVerifier.kt`, `Canonical.kt`, `PolicyAdmission.kt`,
`ApiServer.kt`; `proto/SigningInput.kt` (`forDocument`); PROTOCOL.md §3.1; issue #91.

# ADR-047: The signed-wire `encodeDefaults=true` invariant — every parent→child signed object transmits exactly the bytes it signed (audit #161; guards #160/#154)

Status: Proposed (flips to Accepted on maintainer merge — attended agent-blocked)
Date: 2026-07-02
Relates: **ADR-046** (signed lock/unlock demo trust path — the #157 fix this generalizes), **ADR-040**
(child verifies `/policy` over the received bytes), **ADR-034** (parent assembles/signs/sends bundles),
**ADR-030** (LAN signed commands), **ADR-015/019** (the one signing rule — Ed25519 over RFC 8785 JCS
minus `sig`); PROTOCOL.md §2.1 (verify) / §3.1 (sign-and-transmit-exact-bytes). Discharges #161 (audit),
#160 (regression), #154 (shared vector).

## Context

The signed lock/unlock wire broke **twice** in short succession:

- #152 shipped `RealLockCommandSender` with a decode-only `Json { ignoreUnknownKeys = true }` on the
  HttpClient — **no `encodeDefaults`** — so `"v":1` (a default value) was dropped from the transmitted
  body. The child's `SignedCommand.v` has **no default**, so `call.receive<SignedCommand>()` on a
  `v`-less body threw and every `/lock` and `/unlock` was rejected `400 MALFORMED` — Lock Now was dead,
  even though the Ed25519 signature (taken over the JCS body **with** `"v":1`) was valid.
- #157 fixed it by adding `encodeDefaults = true` to the sender's wire `Json` and pinning it with a
  regression test that asserts the **raw** posted body contains `"v":1` (a *decode-based* assertion
  re-defaults the missing field to `1` and hides the bug — the exact trap #152's test fell into).

The root cause is a class, not a one-off: **the signature is computed over the canonical JCS bytes
(which include defaulted fields — `v`, empty `blocklist`/`windows`/`restrictions`), but any `Json`
used to serialize the object for the *wire* that omits `encodeDefaults` transmits a *different*
byte-set.** The child then either cannot parse it (fields with no default → `MALFORMED`) or
canonicalizes a different object than was signed → `SIG_FAIL`. Either way the parent→child control
path silently dies while the crypto is "correct."

Issue #161 asked: does the **policy-bundle** wire (`KtorPolicyTransport` / `PolicySender`) have the
same latent gap that killed the lock wire?

## Decision

**D1 — The signed-wire invariant.** Every `Json` instance that serializes a **signed** parent→child
object *for transmission* MUST set `encodeDefaults = true` (and `explicitNulls = false`, since
PROTOCOL §3.1 rule 6 forbids `null` on the wire). The transmitted bytes MUST carry exactly the field
set the signer committed to. This is the same rule the signer side already states (`SigningInput`,
`PolicySigner`, `CommandSigner` all set `encodeDefaults = true`); D1 makes it explicit that the
**wire serializer must match the signer**, not just the signer itself. Response-DTO and decode-only
`Json` configs (e.g. `KtorPolicyTransport.parser`, `ApiServer` response JSON) are exempt — they never
serialize a signed object.

**D2 — Audit verdict (#161): the policy-bundle wire is SAFE; no code fix.** `PolicySender.json`
(`parent-kmp/shared/.../policy/PolicySender.kt`) already sets `encodeDefaults = true; explicitNulls =
false` and serializes the signed bundle to the wire string; `KtorPolicyTransport.postPolicy` transmits
that string **verbatim** (`setBody(bundleJson)` — its only `Json` is a decode-only response `parser`).
The child verifies `/policy` **over the received bytes** (ADR-040), so field-order/whitespace drift is
harmless and the transmitted field-set (with `"v":1` + the empty policy lists) canonicalizes to exactly
the signed bytes. The bundle path therefore never had the #157 defect. The risk was not a live bug — it
was an **unguarded invariant** (the same `encodeDefaults` removal that killed the lock sender would
silently kill policy push, and no test asserted the raw bundle wire).

**D3 — The child `v` field stays required (no default) — fail-closed.** The child models
`SignedBundle.v`, `SignedCommand.v`, and `SignedHeartbeat.v` have **no Kotlin default**, so a `v`-less
or version-downgraded wire body is **rejected** (`MALFORMED`), never silently re-defaulted to `1`. This
asymmetry with the parent models (which *do* default `v = 1`) is the **fuse** that made #157 fatal — but
it is the *correct* fail-closed posture and is **kept deliberately.** Adding a default to the child `v`
for symmetry was considered and **rejected**: it would let a malformed/downgraded body parse instead of
failing closed, and would re-default a dropped `v` and thereby *hide* exactly the wire drift D1 guards
against. The invariant is enforced on the **parent send** side (D1 + D4); the child stays strict.

**D4 — Behavioral regression guards, not a structural lint (#160/#154).** The invariant is pinned by
tests, not a compile-time guard:
1. **Raw-wire assertions** at each sender boundary — the transmitted body string MUST contain the
   signed defaults (`"v":1`; for the bundle also `"blocklist":[]`, `"windows":[]`, `"restrictions":[]`).
   Decode-based assertions are forbidden (they re-default and hide the bug).
2. **A byte-equality round-trip** — the transmitted wire, parsed and canonicalized via
   `SigningInput.forDocument` (bundle) / `Canonical.canonicalizeWithout` (command), MUST equal the
   signer's `signingBytes`; and a real Bouncy Castle Ed25519 verify over the transmitted wire bytes MUST
   succeed. The negative twin — re-serializing with a bare `Json {}` (no `encodeDefaults`) — MUST break
   both byte-equality and the signature, reproducing the #157 failure deterministically.
3. **A shared canonical `SignedCommand` test-vector** (`docs/test-vectors/command/`, #154) cross-checked
   by **both** Canonical ports (parent `CommandSigner` and child `CommandVerifier.canonicalBody`), so the
   two independent implementations stay byte-identical.

A compile-time/architecture lint that fails the build if a signed-wire `Json` omits `encodeDefaults`
was considered and **deferred** (it would touch detekt/CI config — a more gated surface — and the
behavioral guards already fail on the regression). Revisit if the class recurs a third time.

**D5 — `DemoLockCommandSender` is a dead landmine.** `DemoLockCommandSender.kt:38` uses a `Json` with
**no `encodeDefaults`**, but `MainActivity` wires `RealLockCommandSender` (the #157-fixed one), so the
demo stub is not on any live path. Flagged here; delete or align it when the demo command senders are
retired. Not a live defect today.

## Consequences

- **No product-code change.** This ADR is an audit record (#161) plus net-additive tests (#160) and a
  shared vector (#154). The bundle path was already correct.
- **The invariant is now behaviorally pinned.** Removing `encodeDefaults` from any signed-wire `Json`
  now fails a test in CI — the regression that shipped twice (#152, latent on the bundle) can no longer
  reach a live child undetected.
- **Fail-closed is preserved, not relaxed.** The child stays strict on `v` (D3); the guards only assert
  the parent transmits what it signed.
- **Scope boundary:** `/heartbeat`, `/lock`, `/unlock` still *verify* over a typed re-serialization
  (ADR-040 out-of-scope follow-up, #111) — orthogonal to this ADR, which pins the **parent-send**
  invariant, not the child verify-path migration. The command round-trip guard here proves the
  transmitted bytes match the signed bytes; #111 is the separate question of the child verifying over
  the received bytes.
- **Cross-impl drift guard:** the shared `SignedCommand` vector extends the existing pairing/bundle
  golden-vector regime to the command shape, so `proto/Canonical` ≡ `child/Canonical` for commands is
  a standing merge gate.
- Accepted on maintainer approval at merge (attended agent-blocked — crypto/protocol surface).

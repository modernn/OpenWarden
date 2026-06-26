# ADR-044: Sealed-box event encryption — ratify the `crypto_box_seal` primitive, host-test it against reference libsodium (issue #3)

Status: Accepted
Date: 2026-06-26
Implements: **[ADR-015](015-event-log-crypto-primitives.md)** ruling 1 (anonymous `crypto_box_seal` is the ONLY event-channel encryption primitive) + ruling 2 (BLAKE2b nonce, exactly libsodium) + **docs/CRYPTO.md §4** (sealed-box envelope) + **docs/PROTOCOL.md §6** (event encryption). Flips ADR-015 Proposed → Accepted and applies its mandated PROTOCOL §6.2 nonce doc-fix (BLAKE3 → BLAKE2b).
Relates: ADR-039 (the pinned `child_x25519_pub` whose private counterpart on the *parent* opens these boxes — the audience), ADR-034 (parent Ed25519 signing — the authenticity layer that pairs with this confidentiality layer), ADR-033 (parent root X25519 key derivation); docs/DEFENSES.md Pattern B (#13 constant-size traffic), docs/ATTACKS.md SB1 / H5; docs/research/07 (red-team, SB1 + BLAKE3 nonce findings); issue #3.
Maintainer-approved: attended agent-blocked review, 2026-06-26 (scope = ratify + KAT-test the existing `SealedBox` primitive + §6.2 nonce doc-fix; the EventEntry envelope assembly — pad-to-4096, seal payload, Ed25519-sign the outer envelope, wire a producer/consumer — is explicitly OUT of scope, see D7).

## Context

The "child cannot read its own log" invariant ([`CLAUDE.md`](../../CLAUDE.md), [ADR-015](015-event-log-crypto-primitives.md)) is the load-bearing wall of the event channel: child events are sealed to the **parent** X25519 pubkey with libsodium [`crypto_box_seal`](https://doc.libsodium.org/public-key_cryptography/sealed_boxes), whose per-message **ephemeral** sender key libsodium zeroizes immediately — so a rooted child retains no decryption-capable secret. Authenticity is supplied separately by the entry-level Ed25519 signature (ADR-015 ruling 3 / ADR-034); the sealed box authenticates nothing about the sender, by design.

ADR-015 settled the *design* (sealed-box only, BLAKE2b nonce, one signing rule) but created no code and was left **Proposed**. Two gaps remained:

1. **An untested, unwired primitive.** `parent-kmp/shared/.../crypto/SealedBox.kt` (commit e20338e) wraps the ion-spin libsodium `Box.seal` / `Box.sealOpen` with a fail-closed `OpenResult`, but had **zero tests**, **no KAT vector**, and **no caller** — crypto + protocol code REQUIRES tests ([`CLAUDE.md`](../../CLAUDE.md)).
2. **A still-wrong normative doc.** ADR-015 doc-change #5 (PROTOCOL §6.2: `nonce := BLAKE3-256(...)` → BLAKE2b) was never applied. A child following PROTOCOL literally would derive a BLAKE3 nonce; the parent's `crypto_box_seal_open` recomputes BLAKE2b, the Poly1305 tag check fails, and **every event silently fails to decrypt** — a fail-open monitoring gap the parent only notices by absence.

A prior assumption baked into the build (`shared/build.gradle.kts`: *"the desktop JVM does not ship [libsodium]"*) and into the issue text ("on-device `connectedAndroidTest` only") said the round-trip could not be host-tested. **That assumption is false** — empirically, ion-spin `multiplatform-crypto-libsodium-bindings:0.9.2` bundles native libsodium for the JVM and a `crypto_box_seal` round-trip passes in plain `jvmTest` in ~0.2 s with no device.

## Decision

**D1 — Ratify `crypto_box_seal` as the sole event-encryption primitive, via the existing `SealedBox`.** No authenticated `crypto_box`, no sender private key in the event path, no double-ratchet (all struck by ADR-015). `SealedBox.seal(recipientX25519Pub, plaintext)` and `SealedBox.open(recipientX25519Pub, recipientX25519Priv, sealed)` delegate to libsodium; the nonce is BLAKE2b-192(ephemeral_pub‖recipient_pub) **by construction** (libsodium computes it — we MUST NOT hand-roll it).

**D2 — Test host-side on the `jvm()` target, not on-device.** The seal/open round-trip, the interop KAT, the child-cannot-decrypt negative, and the fail-closed paths run in **`jvmTest`** — fast, deterministic, no emulator. This **corrects** the "JVM has no libsodium" assumption: ion-spin bundles a desktop native libsodium for the Kotlin **`jvm()`** target specifically. It does **NOT** load on the **Android host unit-test** target — `testDebugUnitTest` fails inside the ion-spin native resource loader (`NullPointerException: ... URL.getFile() because url is null`) — nor on iOS without a simulator. The suite therefore lives in `jvmTest`, **not** `commonTest`, so it cannot leak into `./gradlew check`'s android unit tests and break the build. The `jvm()` round-trip is itself a complete byte-for-byte interop proof; an Android round-trip, if ever needed, is on-device (`androidInstrumentedTest`). Residual (D2a): CI MUST run `:shared:jvmTest` (where the native loads); it must not rely on `testDebugUnitTest` for this suite. ion-spin bundles linux-x64/macos/windows desktop natives, so `jvmTest` is expected to pass on the CI runner — a **pre-merge CI check**, not assumed.

**D3 — Byte-for-byte interop is proven against reference libsodium.** `docs/test-vectors/event-log/sealed_envelope.json` is generated by **PyNaCl** (a thin wrapper over real libsodium) using a **fixed ephemeral key** (so the sealed bytes are stable and pinnable), and the generator self-asserts that PyNaCl's high-level `SealedBox.decrypt` opens the hand-built box. The Kotlin test pins the same hex and asserts `SealedBox.open` recovers the exact plaintext — proving ion-spin ≡ libsodium on the open path. The seal path is non-deterministic (random ephemeral), so we pin **size + open-round-trip**, not seal output (consistent with CRYPTO.md §12). The generator (`gen_sealed_envelope.py`) is committed for reproducibility; the seeds are fixed and non-secret (vectors only).

**D4 — Fail-closed and loud.** `open` returns `OpenResult.Failure` for any cryptographic failure (wrong key, truncated, tampered, forged) — **never a thrown exception, never a silent success**. The sealed `OpenResult` type forces callers to handle the failure branch; there is no fail-open decode path. Tests pin tampered-ciphertext, truncated-input, and wrong-recipient → `Failure`.

**D5 — Child-cannot-decrypt is *modeled* by a KAT (not structurally enforced at the primitive).** The vector includes an unrelated `child` X25519 keypair; the test asserts that opening the parent-sealed box under the child keypair yields `Failure`. This **models** the production reality — the child seals to `parent_x25519_pub`, never holds the parent private key, and `crypto_box_seal` zeroizes the per-message ephemeral *sender* key — so the child retains nothing that can decrypt its own writes. What the test *pins* is the narrower "a foreign keypair cannot open the box"; the stronger "no decryption-capable sender secret exists" is a property of choosing sealed box over authenticated `crypto_box` (ADR-015 ruling 1), guaranteed by construction, not by this test. A future regression to authenticated box would re-introduce a child-recoverable secret without failing this test — which is why ruling 1 (sealed box only) is the real guard and must not be weakened.

**D6 — Apply the PROTOCOL §6.2 nonce fix + flip ADR-015 to Accepted.** PROTOCOL §6.2 now reads BLAKE2b-192 and states implementations MUST call `crypto_box_seal` / `crypto_box_seal_open` and MUST NOT hand-roll the nonce. ADR-015 moves Proposed → Accepted. (The BLAKE3-256 `prev_hash` log chain in §1.2 is unrelated and untouched.)

**D7 — Scope boundary (disclosed residual) + binding obligations on the future consumer.** This ratifies and tests the **primitive only**. The full `EventEntry` envelope — pad plaintext to the constant cover-traffic size, seal it, Ed25519-sign the canonical outer envelope (ADR-015 ruling 3), and wire a child producer + parent consumer — is **separate downstream work** and is NOT in this PR. `SealedBox` therefore remains **unreferenced by production code** after this change; it is a *tested, ratified* primitive awaiting that wiring. Dead code cannot fail open, but an unconstrained future *caller* can — so the downstream D7 ADR MUST honor these, as normative requirements recorded here:

- **Fail-closed consumer.** The parent consumer MUST map `OpenResult.Failure` to a **loud drop-and-surface**, never a silent skip — a missing event is a fail-open monitoring gap (the same class of bug as the BLAKE3 nonce).
- **Verify authenticity around the open.** The sealed box gives **no** sender authentication (ADR-015 authenticity note); the consumer MUST verify the entry-level Ed25519 signature over the canonical envelope (ADR-015 ruling 3) as a precondition of trusting opened plaintext.
- **Reconcile the padding constant before any producer pads.** CRYPTO.md §4 specifies a **4096-byte** ciphertext (4080-byte plaintext) cover-traffic size; PROTOCOL.md §6.4 currently says **2048**. These disagree — the D7 ADR MUST pick one and sync both docs, or child and parent break Defense #13 (constant-size traffic). Flagged here so it is not silently inherited; out of scope for this primitive PR (the KAT plaintext is unpadded).

The ADR-015 doc-changes #1–#4 (STORE_AND_FORWARD non-normative banners + signing-input unification) are likewise a tracked follow-up doc PR.

## Consequences

**Good:**
- The event-encryption primitive is now KAT-proven byte-for-byte against reference libsodium, with the "child cannot read own log" property (modeled, D5) and the fail-closed open contract pinned by tests — host-side on the `jvm()` target, no device.
- The silent fail-open BLAKE3-nonce bug is removed from normative canon; PROTOCOL and CRYPTO now agree with libsodium and with each other on the nonce.
- Host-testability (not on-device) makes this gate runnable in ordinary CI and re-runnable on every change — a strictly better test posture than the on-device assumption it replaces.

**Bad / accepted trade-offs:**
- **No sender authentication at the encryption layer** (sealed box authenticates nothing about the sender). Unchanged from ADR-015 and explicitly relied-upon: authenticity is the mandatory entry-level Ed25519 signature, not this box.
- **No forward secrecy against parent-key compromise** — anyone with the parent X25519 private key decrypts all captured past logs. Accepted per ADR-015 (events are short, metadata-only; a retained ratchet secret on the child is the very thing that would re-break the invariant).
- **Native-libsodium-on-CI is a runtime dependency** of the host tests (D2a), and the suite is **`jvm()`-target-only** — CI must run `:shared:jvmTest`, since `testDebugUnitTest` cannot load the native (NPE in the resource loader). If a CI runner platform lacks the bundled native, those tests error rather than silently skip — the correct fail-closed posture for a crypto KAT — so it is fixed in CI config, not worked around by skipping.
- `SealedBox` stays unwired until the D7 envelope work lands; the ratified primitive is dead code in the interim (intentional, scoped).

## Cross-refs

- [`docs/adr/015-event-log-crypto-primitives.md`](015-event-log-crypto-primitives.md) — design ADR this implements (now Accepted)
- [`docs/CRYPTO.md`](../CRYPTO.md) §4 (sealed-box envelope), §12 (`sealed_envelope.json` vector) — NORMATIVE
- [`docs/PROTOCOL.md`](../PROTOCOL.md) §6 (event encryption; §6.2 nonce fixed here), §10.1 (byte-for-byte conformance) — NORMATIVE
- `parent-kmp/shared/src/commonMain/.../crypto/SealedBox.kt` — the ratified primitive
- `parent-kmp/shared/src/jvmTest/.../crypto/SealedBoxTest.kt` — conformance + KAT tests (`jvm()` target)
- `docs/test-vectors/event-log/sealed_envelope.json` + `gen_sealed_envelope.py` — reference vector + reproducible generator
- [`docs/DEFENSES.md`](../DEFENSES.md) Pattern B / #13; [`docs/ATTACKS.md`](../ATTACKS.md) SB1, H5; [`docs/research/07-redteam-design-review.md`](../research/07-redteam-design-review.md)

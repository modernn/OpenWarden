# ADR-046: v0.x demo-grade LAN trust path — make parent↔child pair → provision → push → lock work end-to-end, ahead of the full attested flow

Status: Accepted
Date: 2026-06-29
Relates: **ADR-043** (the full attested pairing flow this is an interim for — QR + §7.4 SAS + §7.3 attestation; the child-scanner + `RefuseAllAttestationVerifier` replacement = issue #96), **ADR-033** (parent BIP39 root key + the deferred D7 onboarding UI), **ADR-030** (signed `/lock` `/unlock` `SignedCommand`), **ADR-025** (parent-key pinning at pairing), **ADR-031** (mDNS discovery + SPKI-pinned TLS, deferred), **ADR-034** (parent signed-bundle send — already wired by PR #149). Supersedes the `demo/child-pair` + `demo/parent-pair` branch hacks by porting their *reachability* (not their ephemeral key / unconditional re-pin) onto the ratified crypto spine.

## Context

The full crypto spine on `main` is complete and tested — `PolicyAdmission` (signed bundles), `CommandAdmission` (signed lock/unlock), `StoredRootKeyProvider` (BIP39→Argon2id→HKDF→Ed25519), `PolicySender` (parent push, wired by #149). **But the three *entry points* that make it reachable end-to-end are missing or stubbed on `main`:**

1. **No way to pin the parent key.** `ApiServer`'s `/policy` and `/lock` handlers read `PolicyStore.parentPubkey()` as an already-pinned key, and the `/policy` admission comment states the key is "pinned out-of-band at pairing (`PolicyStore.pinParentPubkey`)" — but **no endpoint calls `pinParentPubkey`**. The ADR-043 flow that would (parent shows QR, child scans, attestation + SAS) terminates in `RefuseAllAttestationVerifier` (issue #96), so pairing never completes. Result: every signed `/policy` and `/lock` is rejected with "no pinned parent key".
2. **No way to provision the parent root key.** `RecoveryOnboarding` exists but nothing in the parent UI drives it (ADR-033 D7 deferred), so `StoredRootKeyProvider.rootPublicKey()` returns `null` → `PolicySender`/signing fail-closed to `NotProvisioned`.
3. **Lock sends an empty body.** `DemoLockCommandSender` POSTs no `SignedCommand`; the child correctly rejects it (`v != 1`). The dashboard "Lock Now" is inert against a real child.

Consequence: parent-Apply→child-enforce and Lock-Now cannot work on `main` — the **real reason "the child is not locking"**, on top of the child not being Device-Owner-provisioned. The only place these worked was the `demo/*` branches, which used an **ephemeral** parent key (lost on process death) and an **unconditional** `/pair` overwrite (any LAN host could re-pin) — both unacceptable to carry forward verbatim.

We need the end-to-end loop on `main` for real testing **now**, without waiting on the full attested pairing (#96) or polished onboarding UX. The crypto is already there; only the reachable plumbing is missing.

## Decision

Introduce a **demo-grade LAN trust path** as the explicit **v0.x interim**, built on the *unchanged* ratified crypto spine. It is **NOT** v1.0: v1.0 still requires the full ADR-043 attested pairing (#96), ADR-031 SPKI-pinned TLS, and the ADR-033 recovery-grade onboarding UX. This ADR governs four slices, shipped as sequenced reviewed PRs:

- **D1 — Child `POST /pair` (this PR).** An app-layer LAN endpoint that pins the parent Ed25519 public key so the existing signed `/policy` + `/lock` paths become reachable. Body `{ "parent_pubkey": "<base64, 32-byte Ed25519>" }`; on accept it calls the existing `PolicyStore.pinParentPubkey(raw)` and responds `{ "status": "paired", "child_id": "<id>" }`.
  - **`child_id` is the REAL `ReplayFloorStore.childDeviceId()`** — the same stable id used for mDNS and, critically, the **audience** every `SignedBundle` (`child_device_id`) and `SignedCommand` binds to. The demo branch returned a SHA-256-of-parent-pubkey hash, which would make the parent address bundles/commands to the wrong audience → all rejected. This is the single most important correctness fix over the demo.
  - **First-pairing-only (fail-closed hardening over the demo).** If a parent key is *already* pinned (`parentPubkey() != null`), `/pair` **refuses** (HTTP 409) and does **not** overwrite. The demo's unconditional overwrite let any LAN host re-pin over an existing pairing and then push arbitrary signed policy. Re-pairing is deferred to the recovery-gated re-pair of ADR-043 §7.5 (clearing the pin requires Device-Owner reset / factory reset). One-shot first-use is the most-restrictive interim.
  - Validation is fail-closed: non-32-byte or non-base64 input → 400 MALFORMED; a pin write failure → 500 with no "paired" claim.

- **D2 — Parent root-key provisioning (PR2).** Wire the existing `RecoveryOnboarding` (BIP39 + Argon2id m=256MiB + HKDF, persisted via the StrongBox/`requireUserAuthentication` `AndroidSecureKeyStorage`) so the parent actually holds a root key. Scope (real onboarding screen vs. a `BuildConfig.DEBUG`-only quick-seed for emulator testing vs. both) is settled in PR2. Precondition (already enforced fail-closed by #145): a secure lock screen must be set, else `write()` throws `SecureStorageUnavailableException` surfaced as "set a screen lock first". The key is the **real persisted BIP39 root** — an improvement over the demo's ephemeral key (which un-paired the child on every parent restart).

- **D3 — Signed Lock (PR3).** Replace the empty-body `DemoLockCommandSender` with a real `SignedCommand` builder: `v=1`, `type`, `child_device_id` from `AndroidPairedChildStore.pairedChildId()`, `issued_at = clock()`, signed by `StoredRootKeyProvider.sign()` over the **same** JCS-canonical-minus-`sig` bytes the child's `CommandVerifier` checks. The `SignedCommand` type + its canonicalization move to the shared `proto/` module so parent-sign and child-verify share one definition (mirroring `PolicySigner.signingBytes`). The child side already validates fully (audience, sig, monotonic `issued_at` floor, 5-min freshness).

- **D4 — Pairing-completion glue (PR2/PR3).** On a completed pairing, the parent POSTs `/pair` with `rootPublicKey()` and stores the returned real `child_id` in `AndroidPairedChildStore.pin(...)`, linking the signing key (D2) to the audience (D1) that D3 and #149's push both bind to.

## Security delta (what the interim skips vs. the full path)

| Property | Full path (ADR-043/031/030) | v0.x demo `/pair` |
|---|---|---|
| Mutual auth | SPKI-pinned TLS + Ed25519 PoP | none — anonymous app-layer POST |
| Out-of-band verify | §7.4 6-emoji SAS compare | none |
| Attestation | Google-root cert chain (§7.3) | none (issue #96) |
| Transport | mDNS-discovered TLS, pinned SPKI (ADR-031) | plaintext HTTP on the LAN |
| Re-pair | recovery-phrase + delay gate (§7.5) | **refused** (first-pairing-only) — stricter than the demo, weaker than full |
| Parent key lifetime | persisted BIP39 root (ADR-033) | **persisted BIP39 root** — same as full (we do NOT use the demo's ephemeral key) |

**Residual threat (accepted for v0.x only):** before first pairing, any host that reaches `:7180` on the LAN (or host loopback via `adb forward`) can pin its own key and thereafter push signed policy/commands. Mitigations: (a) first-pairing-only blocks re-pinning over an established pairing; (b) the read/enforcement spine is unchanged and still fail-closed; (c) this is gated to local dev/test on a trusted LAN. **v1.0 MUST replace `/pair` with the attested ADR-043 flow (#96) before any non-local distribution** — tracked as the blocking dependency. The signed-bundle/command crypto, replay floors, freshness windows, and fail-closed admission are all **unchanged** by this ADR — we only add the missing reachable entry points.

## Consequences

- The end-to-end loop (pair → provision → push allowlist → lock) works on `main` for real two-emulator testing; "child not locking" is resolved once the child is DO-provisioned, paired, and sent a real signed lock.
- The crypto spine, fail-closed admission, replay protection, and audience binding are untouched — this ADR adds reachability, not new trust primitives.
- A clear, tracked v1.0 blocker remains: attested pairing (#96) + SPKI-TLS (ADR-031) must supersede `/pair` before any distribution beyond a trusted local LAN.
- Sequenced PRs: **PR1 = D1 (child `/pair`, this PR)** → **PR2 = D2 + D4 glue** → **PR3 = D3 (signed lock)** → emulator end-to-end validation.

# ADR-030: Child LAN server authentication — app-layer Ed25519 signed commands for lock/unlock (closes issue #20's auth + command surface)

Status: Accepted
Date: 2026-06-18
Relates: **ADR-015** (the one signing rule — JCS canonical bytes minus `sig`), **ADR-017** (replay floor + two-phase commit + audience binding), **ADR-019** (canonical signing invariant), **ADR-024 D4** (`SignedHeartbeat` — the keep-alive this command surface is modelled on), **ADR-025** (pairing pins the parent Ed25519 key — the trust root these commands verify against); docs/PROTOCOL.md §2/§5/§7, docs/ROADMAP.md (LAN-only v1 transport), docs/ATTACKS.md §1 (threat model)

## Context

The child runs an embedded Ktor server (`ApiServer.kt`) bound to the LAN. On `main` its authentication is a **stub** — the class kdoc says *"All endpoints require `Authorization: OpenWarden <hex-hmac>` … Stub here — actual HMAC validation goes in v1."* — and nothing enforces it. Concretely:

- `POST /policy` is already fully gated: ADR-017 admission (audience → JC1 integer bound → Ed25519 over canonical bytes against the **pinned** parent key → monotonic replay floor → two-phase commit). **Secure; unchanged by this ADR.**
- `POST /heartbeat` is already gated: `SignedHeartbeat` + `HeartbeatVerifier` + `ContactClock.admitHeartbeat` (ADR-024 D4). **Secure; unchanged.**
- `POST /lock` calls `PolicyEnforcer.lockNow()` with **no signature check at all** — any process that can reach the port can lock the device.
- `POST /unlock` **does not exist**, yet the parent app's `LockCommandSender` seam already declares `sendUnlock()`.
- `GET /state` returns `is_locked = false` hardcoded — there is no persisted lock state.

Issue #20's acceptance: *"unauthenticated requests rejected; /policy only accepts verified signed bundles; lock/unlock require valid signed command."*

**Why this is a design decision, not a mechanical fix.** The issue text floats *"auth (HMAC/keyed)"*, but that wording predates ADR-025. The ratified pairing handshake (PROTOCOL §7, ADR-025) pins the parent's **Ed25519/X25519 public keys** on the child and establishes **no shared symmetric secret**. An HMAC scheme would require inventing a new pairing artifact (a shared HMAC key) that the ratified flow does not produce. The child already holds exactly the credential needed to authenticate a parent: the **pinned parent Ed25519 key**. Re-using it for commands is strictly stronger than HMAC (asymmetric; a compromised child cannot forge parent commands) and obeys ADR-015's *one signing rule*.

## Decision

**D1 — The child server's authentication primitive is the app-layer Ed25519 signature against the pinned parent key. There is no transport-layer HMAC.** Every state-changing request carries a parent-signed wire object verified against `PolicyStore.parentPubkey()`. `/policy` and `/heartbeat` already do this; this ADR extends the same model to `/lock` and `/unlock`. Authentication is **app-layer and transport-independent** — it holds identically over LAN HTTP today and any future transport (TLS, Bluetooth ADR-028), so confidentiality/TLS (issue #21) and authentication are orthogonal concerns and neither blocks the other.

**D2 — New wire type `SignedCommand` (mirrors `SignedHeartbeat` byte-for-byte in its rules).**

```
SignedCommand { v: 1, type: "lock" | "unlock", child_device_id, issued_at, sig }
```

- Signed by the parent Ed25519 key over the **RFC 8785 JCS canonical bytes of the object minus `sig`** — the identical rule as `SignedBundle`/`SignedHeartbeat` (ADR-015/019). Verifier canonicalizes the object it received; signer and verifier emit byte-identical input.
- `sig` is hex Ed25519, matching `Ed25519.verify` on `main`.
- Defaults exist only so a partial object still parses; an empty `type`, `child_device_id`, or `sig` can never verify or admit (fail-closed).

**D3 — `CommandAdmission.decide` is a pure function whose check order mirrors `HeartbeatAdmission.decide` exactly, with two command-specific checks added.** No I/O. Order:

1. `v == 1` else reject (unsupported version).
2. **JC1 integer bound** on `issued_at` (`Canonical.isJcsSafe`), **before** signature — consistent with ADR-017 verify step 3 and the heartbeat path.
3. **`type` is a known command** (`"lock"` or `"unlock"`), before signature — an unknown verb is rejected without a crypto op.
4. **Audience binding**, before signature: `child_device_id` non-empty and `== myChildDeviceId`. A command for child A must never act on child B.
5. **Pinned parent key present** — `pinnedParentPubkey != null`; a pre-provision child has nothing to verify against ⇒ fail-closed reject.
6. **Ed25519 signature** verifies over the canonical body (`SIG_FAIL` ⇒ fail-closed reject).
7. **Monotonic replay floor** (shared across BOTH command types): `issued_at > commandFloor`. A captured command, once any later command has been admitted, is dead.
8. **Freshness window** (command-specific, see D4): `|now − issued_at| ≤ COMMAND_FRESHNESS_MS`.

Every non-accept is `Outcome.Reject(reason)` and the caller MUST mutate **no** state (no floor advance, no lock-flag change).

**D4 — A freshness window is mandatory for commands (it is NOT redundant with the monotonic floor).** The monotonic floor only rises when a command is *admitted*; it therefore does **not** bound a command that is captured on the wire and **never delivered**. On plaintext-LAN v1 a sniffed `unlock(issued_at=T)` stays replayable indefinitely — until some *newer* command happens to advance the floor past `T` — which is the dangerous case (kid bypasses a lock with a stale captured unlock). The freshness window bounds that exposure to `COMMAND_FRESHNESS_MS` (default **5 minutes**) of wall-clock. This is safe to rely on because `DISALLOW_CONFIG_DATE_TIME` is in the Day-One baseline (`PolicyEnforcer.requiredRestrictions`), so the child clock is not kid-settable; and it is fail-closed — clock skew beyond the window (or an unreadable/absurd child clock) rejects a legitimate command (parent retries) rather than admitting a stale one. The floor stays the primary anti-replay; freshness is the bound the floor cannot give. **Disclosed residual (worse than a one-shot — read carefully):** because lock and unlock share ONE monotonic floor, the floor only bounds a captured command against the *last admitted* command, NOT against the specific lock it is being used to defeat. So a kid who sniffs a freshly-issued `unlock(T)` on plaintext LAN can replay it to defeat **any lock the parent issued with a timestamp earlier than `T`**, repeatedly, for the whole `FRESHNESS_MS` window — it is a repeatable per-captured-unlock bypass window, not a single replay. The window bounds the exposure in *time* (5 min) but not in *count*. The real fix is confidentiality on the wire — the TLS-SPKI-pinned channel (issue #21) — which is out of scope here; until it lands this window is the accepted v1 residual.

**D5 — Lock state becomes durable and parent-readable; enforcement strength is unchanged and honestly scoped.** A durable `locked` flag is added to `ReplayFloorStore` (same EncryptedSharedPreferences, same commit()+readback fail-closed contract as the replay floor). `/lock` sets it (and calls `lockNow()` as today); `/unlock` clears it; `/state.is_locked` reads it. The floor advance and the lock-flag write happen in **one atomic durable commit** (`admitCommand`), mirroring `admitHeartbeatContact`, so a crash can never leave the floor advanced (command consumed) but the flag unchanged. **This ADR does not claim to strengthen the lock itself:** `lockNow()` is the existing best-effort keyguard nag ("v2: replace with proper PIN gate" per `PolicyEnforcer`); a kid can still unlock the screen with their own PIN. #20 delivers the **authenticated command surface + persistent, parent-readable lock state**, not the v2 hard-lock enforcement. The durable flag gives the watchdog a future hook to re-assert; wiring that re-assert is a tracked follow-up, not a silent promise. **`GET /state` reads the flag fail-closed:** if the durable store cannot be read (keystore/StrongBox error, corruption), `is_locked` reports `true` (assume locked) — it never reports a falsely-unlocked device to the parent.

**D6 — Read endpoints (`GET /state`, `GET /usage`) stay open on the LAN in v1.** They expose metadata only (policy version, lock flag, per-app foreground minutes) — never content — and the kid is entitled to see their own usage (KID_TRANSPARENCY). The ratified pairing flow establishes no read-auth credential, and a signed-nonce read-challenge is unspecified design. So #20 authenticates the **mutating** surface (the acceptance's named risk) and leaves reads open; read confidentiality (bind reads to the pinned relationship over TLS) is a tracked follow-up. `GET /usage`'s real on-device data is issue #74; this ADR does not touch it.

**D7 — Unsigned/unauthenticated mutating requests are rejected `400` (fail-closed), state untouched.** `/lock`, `/unlock`, `/policy`, `/heartbeat` reject a malformed/missing/invalid signed object without side effects, matching the existing `/policy` reject shape.

## Consequences

**Good:** one signing rule end-to-end (ADR-015) — commands reuse the same canonicalize-then-Ed25519 path as bundles and heartbeats; no new secret, no new pairing artifact; the dangerous `/lock` (open today) and the missing `/unlock` become parent-authenticated; lock state is durable, atomic with the replay floor, and parent-readable; authentication is transport-independent so issue #21 (TLS) and ADR-028 (Bluetooth) inherit it for free.

**Bad / accepted limits:** within the freshness window a captured command is *repeatably* replayable on plaintext LAN — a sniffed `unlock` defeats any earlier-stamped lock for the whole 5-min window, bounded in time but not in count (D4 residual — fixed by issue #21 TLS, disclosed); the lock's *enforcement* is still best-effort `lockNow()` (D5 — not regressed, not upgraded, honestly scoped); reads remain open on the LAN (D6); a per-command freshness window adds a (clock-restricted, fail-closed) wall-clock dependency the heartbeat path intentionally avoided — justified because commands, unlike the keep-alive, must be bounded against capture-and-delay.

## Test plan (binds the implementation)

`CommandAdmission` unit tests (pure, deterministic, mirror `HeartbeatAdmissionTest`):
- **Accept:** valid `lock`, valid `unlock` (well-formed, audience-matched, pinned key, in-window, `issued_at > floor`) ⇒ `Accept`.
- **Reject (each, fail-closed, no state mutation):** `v != 1`; `issued_at` outside JCS-safe range; unknown `type`; empty/mismatched `child_device_id`; `pinnedParentPubkey == null`; bad signature (`SIG_FAIL`); replay (`issued_at <= commandFloor`); stale (`now − issued_at > window`); future (`issued_at − now > window`).
- **Replay/floor:** admitting `lock(t=100)` advances the shared floor; a later `unlock(t=99)` is rejected; `unlock(t=101)` accepts.
- **Atomicity:** `admitCommand` advances the floor AND sets/clears the lock flag in one commit; a simulated commit failure leaves BOTH untouched (floor + flag) and the command not-admitted.
- **State:** the gate sets/clears the durable lock flag (`CommandGateTest`); `GET /state` reads it fail-closed (`CommandDispatch.isLockedFailClosed` — unreadable store ⇒ `true`).
- **Endpoint shaping** (pure `CommandDispatch`, since the repo has no Ktor test harness): unparseable body ⇒ `400 MALFORMED`; a fail-closed durable-write failure inside the gate ⇒ `400 REJECTED` (not `500`); `Accept(lock/unlock)` ⇒ `200 locked/unlocked`; `Reject` ⇒ `400 REJECTED` with reason.

## Cross-refs
- [ADR-015](015-event-log-crypto-primitives.md) / [ADR-019](019-canonical-signing-invariant.md) — the one signing rule reused here
- [ADR-017](017-replay-rollback-resistance.md) — replay floor + audience binding + two-phase commit (the command floor mirrors the policy floor)
- [ADR-024](024-no-contact-ratchet-strict-baseline.md) D4 — `SignedHeartbeat`, the template for `SignedCommand`
- [ADR-025](025-pairing-handshake-direction-attestation-sas.md) — pairing pins the parent Ed25519 key these commands verify against
- docs/PROTOCOL.md §5 (replay/freshness), §7 (pairing); docs/ATTACKS.md §1 (threat model — kid with adb, no JTAG)
- Issue #20 (this surface); issue #21 (TLS/SPKI pin — the D4/D6 confidentiality follow-up); issue #74 (`GET /usage` real data — D6)

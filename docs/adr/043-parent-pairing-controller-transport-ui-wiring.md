# ADR-043: Wire the parent pairing half end-to-end — orchestration controller + transport lifecycle + Compose SAS/QR UI (parent slice f)

Status: Accepted
Date: 2026-06-25
Implements: **docs/PROTOCOL.md §7** (the full parent half of the handshake: §7.1 display QR → §7.2 receive POST → §7.3 attestation verify → §7.4 six-emoji compare → §7.5/§7.4-closing-clause pin) by **joining** the already-merged slices (a)–(e) into one runnable flow. Adds **no** new wire field and **no** new crypto — it is pure orchestration + lifecycle + UI over the existing seams.
Discharges: the **ADR-039 D5a** disclosed residual — slices (a)–(e) shipped "the coordinator + the pin **without** (i) the Compose screen that renders the six emojis and captures the Match/Mismatch tap, and (ii) the `PairingServer` change that drives `endpoint → derive → coordinator.confirmAndPin` under the shared `sessionLock`." This slice adds exactly those two things plus the controller that owns them.
Relates: ADR-035 (session+QR / `PairingSessionManager`), ADR-036 (endpoint + the `sessionLock` cross-thread contract this controller honors), ADR-037 (`Section73AttestationVerifier`, wrapped here), ADR-038 (`PairingSasStage` — its `confirm()` finally gets a production caller), ADR-039 (`PairingPinCoordinator` — `confirmAndPin()` finally gets instantiated + called), ADR-025 (the ratified handshake; this is the parent-side realization of D5(a)-(e)); docs/ATTACKS.md H3 (pubkey substitution, CRITICAL — the SAS compare + pin this UI drives is the defense), docs/DEFENSES.md #4 (identity pinning).
Maintainer-approved: attended agent-blocked review, 2026-06-25 (scope = the controller state machine + transport-lifecycle seam (host-proven) + the real Ktor binding + Compose QR/SAS screen (build-verified) + a QR-encode dependency; the **child** pairing half and parent-side **mDNS advertising** stay out, disclosed in D6).

## Context

Slices (a)–(e) (#94–#98) built every parent-side pairing primitive and stopped, by design, one wire short of a running flow. Today an inbound `POST /pair` travels:

```
PairingServer.handle → synchronized(sessionLock) { PairingEndpoint.handle } → verifier.verify() → Accepted → HTTP 202 ... STOPS
```

Everything downstream of `Accepted` is unreachable from a running app: nothing calls `PairingSasStage.derive()`, no screen renders the six §7.4 emojis or captures the human's Match/Mismatch tap, `PairingPinCoordinator.confirmAndPin()` is **never instantiated**, and `PairingSessionManager.start()` / `PairingServer.start()` are **never called**. The merged crypto chain is dead weight until something joins `Accepted → derive → human-compare → confirmAndPin → pin` and starts/stops the listener around an attempt. That join is the H3 defense actually reaching a parent's thumb: the six-emoji compare is the only thing standing between a pinned key and a MITM's substituted key (ATTACKS H3), and until a human can *see* the emojis and *tap*, the defense is inert.

The non-obvious structural fact that shapes the design: **the parent never tells the child the SAS result over HTTP.** Per §7.4 both peers derive the same six emojis independently and the human compares them on **both** screens; each side pins on its **own** Match tap (parent pins the child keys, §7.4 closing clause / ADR-039; child pins the parent keys, §7.5 — child side is future work). So the parent transport is one-shot: receive the §7.2 POST, return a bare `202 received`, then the *local* UI drives derive → compare → pin. No response channel back to the child is needed, which is why this slice needs no protocol change.

## Decision

**D1 — A single `commonMain` `PairingController` is the orchestration brain; it is the one production caller of the (b)–(e) seams.** It owns `PairingSessionManager` + `PairingSasStage` + `PairingPinCoordinator` and the real `AttestationVerifier`, and exposes a `StateFlow<PairingPhase>`:

```
Idle ── begin() ──▶ ShowingQr(qrPayloadJson)            (or NotProvisioned if no root key, ADR-035 D7)
ShowingQr ── child POST Accepted ──▶ AwaitingSas(emojis)  (verify() derives the §7.4 SAS)
ShowingQr ── child POST Refused  ──▶ Aborted(ATTESTATION_FAILED)   (fail-closed; nonce already burned, ADR-037 D2)
AwaitingSas ── confirm(true)  ──▶ Pinned                  (PinOutcome.Pinned)
AwaitingSas ── confirm(false) ──▶ Aborted(SAS_MISMATCH)   (PinOutcome.Aborted; nonce burned, ADR-036 D4)
AwaitingSas ── confirm on stale/already-paired ──▶ Aborted(STALE | ALREADY_PAIRED)
any ── cancel() ──▶ Idle                                  (burns the pending session)
```

`begin`/`confirm`/`cancel` change no other module's shipped behavior — they only *call* the existing seams in the existing order. The controller pins nothing itself; pinning stays entirely inside `PairingPinCoordinator` (ADR-039), which stays write-once and recovery-gated.

**D2 — `verify()` is the derive seam, captured at exactly the right moment under the existing lock.** The controller **implements `AttestationVerifier`** and is the verifier handed to `PairingEndpoint`. Its `verify(post)` delegates to the real `Section73AttestationVerifier`; on `Accepted` it calls `sasStage.derive(post)` and publishes `AwaitingSas(challenge.emojis)`; on `Refused` it publishes `Aborted(ATTESTATION_FAILED)`. Because `PairingEndpoint.handle` already runs inside `PairingServer`'s `synchronized(sessionLock)` (ADR-036 D5), `verify()` — and the `derive()` + `pendingChallenge` write it performs — execute **under that same lock**, with no new locking added on the network path. This is why the `ValidatedPairingPost` is available to derive against: it is live exactly at the verifier seam and nowhere else.

**D3 — One monitor serializes the network thread and the UI thread; it is host-injectable.** `commonMain` has no `synchronized` (the pieces are single-thread-confined by contract). The controller takes a `PairingMonitor` seam: `begin`/`confirm`/`cancel` run inside `monitor.runGuarded { … }`. The Android wiring passes `PairingMonitor { synchronized(sessionLock) { it() } }` over the **same** `sessionLock` object the `PairingServer` holds, so a UI `confirm()` and a network `verify()` are mutually exclusive (every `PairingSessionManager`/stage/store/`pendingChallenge` touch is under one monitor — ADR-036 D5 satisfied for the whole flow, not just the endpoint). Host tests pass `PairingMonitor.Direct` and drive it single-threaded, exactly like the existing `*Test` doubles.

**D4 — The listener lifecycle is a host-provable seam, not buried in androidMain.** The controller takes a `PairingTransport { start(); stop() }`. `begin()` calls `transport.start()` only after a session was minted (never on `NotProvisioned`); every terminal phase (`Pinned`, `Aborted`) and `cancel()` calls `transport.stop()`. So "the server runs exactly during a live attempt and is torn down on every exit" is asserted deterministically in `commonTest` against a fake transport. The real androidMain impl wraps `PairingServer.start()/stop()` (ADR-036's "the caller starts it when a pairing attempt begins and stops it when the attempt ends"). Fail-closed: a transport that is stopped on every terminal path cannot leave an orphaned `/pair` listener bound after a pair aborts or completes.

**D5 — Fail-closed is preserved end-to-end; the controller only *reflects* the seams' verdicts, it never softens them.** A pin happens only on `PinOutcome.Pinned` (after attestation `Accepted` **and** human Match); `Aborted`/`Stale`/`AlreadyPaired`/`ATTESTATION_FAILED` and `NotProvisioned` pin nothing. The nonce burns stay owned by the seams that already burn (attestation refusal — ADR-037 D2; SAS mismatch — ADR-036 D4; successful pin consume — ADR-039 D4); the controller adds only `cancel()`-on-user-abort. The internal attestation refusal reason string is **not** surfaced in `PairingPhase` (the UI shows the generic §7.3 "device failed hardware attestation" — no probing-oracle value is added locally, matching ADR-036 D3's wire stance). `AlreadyPaired` is surfaced as its **own** abort reason, distinct from a `SAS_MISMATCH`, exactly as ADR-039 D3 mandates the future UI must.

**D6 — Disclosed residual (the next agent-blocked issues).** This slice deliberately ships *without*:
- **The child pairing half** — QR scan/decode, the §7.2 HTTP client POST, the child-side independent SAS compute, and the child pinning the *parent* keys on its own Match (ADR-025 D5 residual; §7.5). The child crypto foundation (`K_bind`/`K_id`/`K_enc`, `ChildKeyBinding` sign+verify, mDNS advertiser) already exists (#22); the human-facing + transport child glue is the sibling of this slice. Until it lands, the parent half is exercised by a synthetic child POST (host tests) / the live emulator bed.
- **Parent-side mDNS advertising** of the `/pair` listener (ADR-036's own residual): the child reaches the parent by address until the parent advertises `_openwarden._tcp`. Unchanged here.
- **Unit-tested Compose / real Ktor binding.** The controller state machine + the monitor + the transport lifecycle are host-proven in `commonTest`; the Compose screen, the QR bit-matrix rendering, the `AndroidPairingFactory` assembly, and the `MainActivity` nav entry are **build-verified only** (no Compose UI test harness exists in this repo yet — the established idiom tests the `commonMain` presenter, not the `androidApp` glue). The real `EncryptedSharedPreferences` pin round-trip remains the instrumented HARD pre-prod gate inherited from ADR-039.
- **Config-change retention (Codex F6).** ✅ **Resolved (#119).** The controller now lives in a retained `PairingViewModel`; entry calls the idempotent `PairingController.ensureStarted()` (begins only from `Idle`), `DisposableEffect`-on-dispose no longer cancels, and teardown is `ViewModel.onCleared()` (real finish) or an explicit Back/Cancel. A rotation no longer burns the in-flight attempt.
- **Latent `AttestationPolicy` empty-root sentinel (crypto review MED-1).** This slice removes its *own* reliance on the empty-pin coincidence (D-note below), but the general hardening — `AttestationPolicy`/`Section73AttestationVerifier` rejecting a zero-length pinned root rather than treating empty-vs-empty as a match — is a defense-in-depth follow-up on the merged slice-(c) crypto.

**Disabled-gate note (crypto review MED-1):** rather than rely on `AttestationPolicy.tier1(ByteArray(0))` refusing by an empty-SPKI-vs-empty-pin coincidence, `AndroidPairingFactory` wires an **explicit refuse-all-with-burn** verifier whenever `googleRootSpkiDer` is empty. Until the real Google-root SPKI is committed, every attestation refuses fail-closed — by construction, not by accident.

**D7 — A QR-encode dependency is added (`com.google.zxing:core`).** Rendering a *scannable* §7.1 QR is the whole point of `ShowingQr`; the repo had no QR encoder. ZXing core is pure-JVM, Apache-2.0, no network and no telemetry (non-negotiables intact). It is confined to the `androidApp` UI layer (encode `qrPayloadJson` → `BitMatrix` → Compose `Canvas`, 4-module quiet zone); the `:shared` crypto/protocol modules gain no dependency. A real QR (not a JSON placeholder) is required for the child to actually scan, so deferring it would ship a non-functional screen.

**D8 — Review hardening (dual + Codex adversarial pass, 2026-06-25).** The slice-(f) review caught fail-closed gaps the orchestration must close — folded in before merge:
- **Single-challenge invariant (Codex F1, CRITICAL — H3).** The per-session attempt cap lets several POSTs reach the verifier; the first version overwrote `pendingChallenge` on each `Accepted`, so a second POST could swap in keys the human never compared and a later Match would pin **those**. Fixed: once a SAS is awaiting the tap, `verify()` refuses any further post **without** touching the pending challenge/emojis — the first Accepted wins and is the one pinned. (Two new abort reasons land with the hardening — see below.)
- **Fail-closed on throw (Codex F2/F3/F4).** A verifier/`derive()` throw, a pin/commit failure (ADR-039 D2 throws), or a listener-bind failure now each burn the session and resolve to `Aborted(INTERNAL_ERROR)` rather than stranding a live half-state. `confirm()` clears `pendingChallenge` and burns on a pin throw (the coordinator does **not** consume on a failed pin); `begin()` rolls back if the bind fails.
- **Lock discipline (Codex F5).** The blocking Ktor `start()`/`stop()` run **outside** the monitor (UI thread), and `verify()` no longer calls `stop()` at all — the verifier's burn-on-refuse already makes the endpoint reject every further POST (`NO_SESSION`), so the listener is inert and is torn down by the screen's `cancel()` on exit. This keeps the blocking server lifecycle off the `sessionLock` an in-flight POST handler holds.

The `PairingAbortReason` set is therefore: `ATTESTATION_FAILED`, `SAS_MISMATCH`, `STALE`, `ALREADY_PAIRED` (ADR-039 D3, distinct), `NO_LIVE_ATTEMPT` (a confirm with nothing awaiting — defensive no-op), and `INTERNAL_ERROR` (the F2/F3/F4 fail-closed catch-all). All abort paths pin nothing.

## Why this is safe (fail-closed analysis)

- **No new trust, no new key handling.** The controller routes existing seams; the only persistence is `PairingPinCoordinator` → `AndroidPairedChildStore`, unchanged and write-once.
- **H3 (pubkey substitution) defense reaches the human.** Before this slice the six-emoji compare was uncallable; now a substituted key (incl. either X25519 key, which §7.3 attestation does not bind — ADR-025 D2a) changes the emojis the parent sees, and a Mismatch tap aborts + burns. The defense is now *operable*, which is the point.
- **No partial-apply / half-pin window introduced.** Pinning atomicity + write-once live in ADR-039's coordinator/store, untouched. The controller's terminal transitions are pure flow updates; a crash mid-flow leaves nothing pinned (no pin call was reached) and the next `begin()` mints a fresh nonce.
- **One lock, whole flow.** D3 extends the ADR-036 `sessionLock` discipline from "just the endpoint" to "endpoint + derive + confirm + pin + start/cancel", closing the cross-thread item ADR-035/036 deferred.
- **Listener cannot leak.** D4's stop-on-every-terminal-path means an aborted/finished pair never leaves the pre-auth `/pair` front door bound.

## Tests

`commonTest` `PairingControllerTest` (deterministic, injected fakes — the established idiom):
- `begin()` with a provisioned root key ⇒ `ShowingQr` carrying the §7.1 JSON + `transport.start()` called once.
- `begin()` with no root key ⇒ `NotProvisioned`, **no** `transport.start()`, no session.
- a child POST that the verifier Accepts ⇒ `verify()` returns `Accepted` **and** phase becomes `AwaitingSas` with the derived emojis.
- a child POST the verifier Refuses ⇒ `verify()` returns `Refused`, phase `Aborted(ATTESTATION_FAILED)`, nothing pinned, `transport.stop()` called.
- `confirm(true)` after `AwaitingSas` ⇒ `Pinned`, both child keys in the store (byte-equal), session burned, `transport.stop()` called.
- `confirm(false)` ⇒ `Aborted(SAS_MISMATCH)`, nothing pinned, nonce burned, `transport.stop()`.
- a stale confirm (attempt replaced) ⇒ `Aborted(STALE)`, the fresh attempt left live, nothing burned.
- an already-paired store ⇒ `Aborted(ALREADY_PAIRED)`, the original pin un-overwritten, surfaced distinctly from `SAS_MISMATCH` (ADR-039 D3).
- `cancel()` ⇒ `Idle`, session burned, `transport.stop()`.
- monitor: every mutating entry routes through `PairingMonitor` (a counting fake proves each `begin/confirm/cancel` is guarded).

Inherited HARD pre-prod gate (ADR-039): the instrumented `AndroidPairedChildStore` `EncryptedSharedPreferences` round-trip on a real device.

## References

- [docs/PROTOCOL.md](../PROTOCOL.md) §7 (the parent half this realizes end-to-end)
- [ADR-035](035-parent-pairing-session-nonce-qr.md), [ADR-036](036-parent-pairing-endpoint-pre-auth.md) (the `sessionLock` contract honored here), [ADR-037](037-parent-attestation-verifier-slice-c.md) (verifier wrapped by D2), [ADR-038](038-six-emoji-sas-encoding.md) (`PairingSasStage.confirm()` gets its caller), [ADR-039](039-parent-pin-child-on-sas-match.md) D5a (the residual discharged here), [ADR-025](025-pairing-handshake-direction-attestation-sas.md) D5(a)-(e)
- docs/ATTACKS.md H3 (pubkey substitution), docs/DEFENSES.md #4 (identity pinning)
- issue #98 / #23 (pairing); the child-half + parent-mDNS follow-ups (D6)

package com.openwarden.parent.pairing

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Slice (f) of the parent pairing flow (ADR-043): the one orchestration brain that **joins** the merged
 * slices (a)–(e) into a runnable §7 handshake and is the production caller ADR-039 D5a said was missing.
 *
 * It owns the session/SAS/pin seams and drives the state machine the UI renders:
 * ```
 * Idle ── begin() ─▶ ShowingQr | NotProvisioned
 * ShowingQr ── child POST Accepted ─▶ AwaitingSas      (verify() derives the §7.4 SAS)
 * ShowingQr ── child POST Refused  ─▶ Aborted(ATTESTATION_FAILED)
 * AwaitingSas ── confirm(true)  ─▶ Pinned
 * AwaitingSas ── confirm(false) ─▶ Aborted(SAS_MISMATCH)
 * AwaitingSas ── stale / already-paired ─▶ Aborted(STALE | ALREADY_PAIRED)
 * any ── cancel() ─▶ Idle
 * ```
 *
 * **The controller pins nothing itself** (ADR-043 D1/D5): a pin happens only inside
 * [PairingPinCoordinator] on [PinOutcome.Pinned] (attestation Accepted **and** human Match). Every other
 * path pins nothing and the controller only *reflects* the seams' verdicts — it never softens fail-closed.
 *
 * **Derive seam (ADR-043 D2):** the controller **is** the [AttestationVerifier] handed to
 * [PairingEndpoint]. Its [verify] delegates to the real [Section73AttestationVerifier]; on `Accepted` it
 * derives the §7.4 SAS *right there* (the [ValidatedPairingPost] is live exactly at this seam) and
 * publishes [PairingPhase.AwaitingSas]. Because the endpoint runs under the [PairingServer]'s
 * `sessionLock`, that derive + the [pendingChallenge] write are already serialized on the network path.
 *
 * **One monitor (ADR-043 D3):** `commonMain` has no `synchronized`, so [begin]/[confirm]/[cancel] run
 * inside [PairingMonitor.runGuarded]. The Android wiring passes a monitor over the **same** `sessionLock`
 * the server holds, making a UI tap and a network POST mutually exclusive across every session/stage/
 * store touch. Host tests pass [PairingMonitor.Direct] and drive it single-threaded.
 *
 * **Listener lifecycle (ADR-043 D4):** [begin] starts the [PairingTransport] only after a session is
 * minted; every terminal phase and [cancel] stops it — so the `/pair` listener runs exactly during a live
 * attempt and can never leak past an abort/finish.
 *
 * Not thread-safe on its own (single-thread-confined, like the (b)–(e) pieces); the monitor is the whole
 * cross-thread contract.
 */
class PairingController(
    private val sessions: PairingSessionManager,
    private val sasStage: PairingSasStage,
    private val coordinator: PairingPinCoordinator,
    private val attestation: AttestationVerifier,
    private val transport: PairingTransport = PairingTransport.None,
    private val monitor: PairingMonitor = PairingMonitor.Direct,
) : AttestationVerifier {
    private val _phase = MutableStateFlow<PairingPhase>(PairingPhase.Idle)

    /** The current §7 pairing phase the UI renders. Updated on every [begin]/[verify]/[confirm]/[cancel]. */
    val phase: StateFlow<PairingPhase> = _phase.asStateFlow()

    /** The derived-but-not-yet-confirmed SAS for the live attempt, or `null` when none is awaiting a tap. */
    private var pendingChallenge: SasChallenge? = null

    /**
     * Begin a pairing attempt: mint the §7.1 nonce + QR, publish [PairingPhase.ShowingQr], then bind the
     * [PairingTransport] so the child can POST. If the parent has no confirmed root key the attempt is
     * refused fail-closed ([PairingPhase.NotProvisioned], ADR-035 D7) and the transport is **not** started.
     *
     * The blocking listener bind runs **outside** the monitor (Codex F5 / ADR-043 D3 — never run the Ktor
     * lifecycle under `sessionLock`, where an in-flight POST handler also waits on it). If the bind fails,
     * the attempt rolls back fail-closed (burn the session, [PairingPhase.Aborted]) rather than stranding a
     * scannable QR with no listener (Codex F4).
     */
    fun begin() {
        var startListener = false
        monitor.runGuarded {
            pendingChallenge = null
            when (val result = sessions.start()) {
                is PairingStartResult.Started -> {
                    _phase.value = PairingPhase.ShowingQr(result.session.payloadJson)
                    // Start the listener only once a real attempt exists (ADR-043 D4) — never on the
                    // NotProvisioned path, so a not-yet-provisioned parent never opens the /pair port.
                    startListener = true
                }

                PairingStartResult.NotProvisioned -> {
                    _phase.value = PairingPhase.NotProvisioned
                }
            }
        }
        if (startListener) {
            try {
                transport.start()
            } catch (t: Throwable) {
                // Bind failed: roll back fail-closed — no stranded QR over a session with no listener.
                monitor.runGuarded {
                    pendingChallenge = null
                    sessions.cancel()
                    _phase.value = PairingPhase.Aborted(PairingAbortReason.INTERNAL_ERROR)
                }
                runCatching { transport.stop() }
            }
        }
    }

    /**
     * The slice-(c) [AttestationVerifier] seam (ADR-043 D2). Called by [PairingEndpoint] on the network
     * thread, already under the server's `sessionLock`. Delegates to the real verifier; on `Accepted`
     * derives the §7.4 SAS and moves to [PairingPhase.AwaitingSas]. Returns the verifier's verdict verbatim
     * so the endpoint's HTTP mapping is unchanged.
     *
     * **Single-challenge invariant (Codex F1, CRITICAL — the H3 defense):** once a SAS is already awaiting
     * the human's tap, any further post is refused **without** overwriting [pendingChallenge] or the
     * displayed emojis. Otherwise a second POST (the per-session attempt cap allows several) could swap in
     * keys the human never compared, and a later Match would pin **those** — the exact pubkey-substitution
     * (ATTACKS H3) the six-emoji compare exists to stop. The first Accepted wins; the human compares and
     * pins exactly it.
     *
     * **Fail-closed on throw (Codex F2):** a verifier or `derive()` exception burns the nonce, aborts, and
     * refuses generically — never leaves the attempt live. (`derive()` throws only on a malformed
     * parent-authored QR, a build-invariant break, but we still refuse rather than strand a live session.)
     *
     * NOTE: this is the one entry point **not** wrapped in [monitor] — it is already serialized by the
     * caller (the server holds `sessionLock`, the same monitor object), so re-guarding would be redundant.
     * It deliberately does **not** stop the transport: the verifier's burn-on-refuse (ADR-037 D2) already
     * makes the endpoint reject every further POST (`NO_SESSION`), so the listener is inert and is torn
     * down when the parent leaves the screen — running the blocking Ktor stop here, under the server lock
     * from inside a request handler, is exactly what Codex F5 warns against.
     */
    override fun verify(post: ValidatedPairingPost): AttestationOutcome {
        // F1: do not disturb an in-flight, human-visible challenge — refuse extra posts, pin only the first.
        if (pendingChallenge != null) {
            return AttestationOutcome.Refused("pairing already awaiting six-emoji confirmation")
        }
        return try {
            val outcome = attestation.verify(post)
            when (outcome) {
                is AttestationOutcome.Accepted -> {
                    // The post is live exactly here (ADR-043 D2): derive the SAS for both sides to compare.
                    val challenge = sasStage.derive(post)
                    pendingChallenge = challenge
                    _phase.value = PairingPhase.AwaitingSas(challenge.emojis)
                }

                is AttestationOutcome.Refused -> {
                    // Fail-closed: the verifier already burned the single-use nonce (ADR-037 D2); the
                    // attempt is dead. Internal reason not echoed (the UI shows the generic §7.3 message —
                    // ADR-043 D5 / ADR-036 D3 oracle stance).
                    pendingChallenge = null
                    _phase.value = PairingPhase.Aborted(PairingAbortReason.ATTESTATION_FAILED)
                }
            }
            outcome
        } catch (t: Throwable) {
            // F2: never leave a live session behind a verifier/derive throw. Burn + abort + refuse.
            pendingChallenge = null
            sessions.cancel()
            _phase.value = PairingPhase.Aborted(PairingAbortReason.INTERNAL_ERROR)
            AttestationOutcome.Refused("pairing internal error")
        }
    }

    /**
     * Record the human's §7.4 verdict for the awaiting SAS. Delegates to [PairingPinCoordinator]:
     * Match pins both child keys (write-once) + burns the session; Mismatch/Stale/AlreadyPaired pin
     * nothing. Maps the [PinOutcome] to a terminal [PairingPhase], then stops the listener **outside** the
     * monitor (Codex F5). A `confirm` with no awaiting challenge resolves to
     * [PairingAbortReason.NO_LIVE_ATTEMPT] (fail-closed no-op).
     *
     * **Fail-closed on pin throw (Codex F3):** a store/commit failure (ADR-039 D2 throws) does not consume
     * the session, so the controller burns it here and aborts — never a stuck half-trusted state. Nothing
     * is pinned (the store throws before/at commit).
     */
    fun confirm(matched: Boolean) {
        monitor.runGuarded {
            val challenge = pendingChallenge
            if (challenge == null) {
                _phase.value = PairingPhase.Aborted(PairingAbortReason.NO_LIVE_ATTEMPT)
                return@runGuarded
            }
            pendingChallenge = null
            _phase.value =
                try {
                    when (coordinator.confirmAndPin(challenge, matched)) {
                        is PinOutcome.Pinned -> PairingPhase.Pinned

                        PinOutcome.Aborted -> PairingPhase.Aborted(PairingAbortReason.SAS_MISMATCH)

                        PinOutcome.Stale -> PairingPhase.Aborted(PairingAbortReason.STALE)

                        // ADR-039 D3: surfaced distinctly from a SAS mismatch (it is a recovery-gated
                        // rotation, not a MITM warning) so the UI can route to the right message.
                        PinOutcome.AlreadyPaired -> PairingPhase.Aborted(PairingAbortReason.ALREADY_PAIRED)
                    }
                } catch (t: Throwable) {
                    // F3: the coordinator did NOT consume on a failed pin, so the session is still live —
                    // burn it so nothing is left half-trusted. Nothing was pinned (the store throws).
                    sessions.cancel()
                    PairingPhase.Aborted(PairingAbortReason.INTERNAL_ERROR)
                }
        }
        transport.stop()
    }

    /**
     * Abandon the in-flight attempt (the parent backed out of the QR/SAS screen). Burns the pending
     * session (a fresh QR is required to retry) and returns to [PairingPhase.Idle]; the listener is stopped
     * **outside** the monitor (Codex F5).
     */
    fun cancel() {
        monitor.runGuarded {
            pendingChallenge = null
            sessions.cancel()
            _phase.value = PairingPhase.Idle
        }
        transport.stop()
    }
}

/** The §7 pairing phase the UI renders (ADR-043 D1). */
sealed interface PairingPhase {
    /** No attempt in flight. */
    data object Idle : PairingPhase

    /** Display the §7.1 QR ([qrPayloadJson] is the exact JSON the QR encodes) and listen for the child POST. */
    data class ShowingQr(
        val qrPayloadJson: String,
    ) : PairingPhase

    /** The child passed §7.3 attestation; display the six §7.4 [emojis] for the human to compare + tap. */
    data class AwaitingSas(
        val emojis: List<String>,
    ) : PairingPhase

    /** The human confirmed Match and the child was pinned (ADR-039). Terminal success. */
    data object Pinned : PairingPhase

    /** The pair failed fail-closed; [reason] routes the parent-facing message. Terminal. */
    data class Aborted(
        val reason: PairingAbortReason,
    ) : PairingPhase

    /** No confirmed parent root key yet (ADR-033/ADR-035 D7) — no QR can be shown. Terminal. */
    data object NotProvisioned : PairingPhase
}

/** Why a pair aborted (ADR-043 D5). Distinct reasons so the UI can show the right message + next step. */
enum class PairingAbortReason {
    /** §7.3 hardware attestation refused the device. Generic message; the internal reason is not surfaced. */
    ATTESTATION_FAILED,

    /** §7.4 emojis differed — possible MITM (ATTACKS H3). Surface the warning; require a fresh QR. */
    SAS_MISMATCH,

    /** The confirmed attempt was already replaced/expired — the tap hit a dead challenge (ADR-038). */
    STALE,

    /** A child is already pinned; re-pair is a recovery-gated rotation (§7.5/D8), not offered here (ADR-039 D3). */
    ALREADY_PAIRED,

    /** A confirm arrived with no SAS awaiting a tap (defensive fail-closed no-op). */
    NO_LIVE_ATTEMPT,

    /**
     * An unexpected failure mid-flow (a verifier/derive throw, a pin/commit failure, or a listener-bind
     * failure) — burned + aborted fail-closed rather than left in a live half-state (Codex F2/F3/F4).
     */
    INTERNAL_ERROR,
}

/**
 * Serializes the pairing-controller entry points across the network thread (the endpoint's `verify`) and
 * the UI thread (`begin`/`confirm`/`cancel`) — ADR-043 D3. `commonMain` has no `synchronized`, so the
 * Android wiring injects `PairingMonitor { synchronized(sessionLock) { it() } }` over the **same**
 * `sessionLock` the [PairingServer] holds; host tests use [Direct] and drive single-threaded.
 */
fun interface PairingMonitor {
    /** Run [block] under the shared pairing monitor. */
    fun runGuarded(block: () -> Unit)

    companion object {
        /** No real lock — direct call. Host tests only (single-threaded). */
        val Direct: PairingMonitor = PairingMonitor { it() }
    }
}

/**
 * The pairing listener lifecycle as a host-provable seam (ADR-043 D4): [start] when an attempt begins,
 * [stop] on every terminal path. The Android impl wraps [PairingServer.start]/[PairingServer.stop]; tests
 * use a fake that counts the calls to prove the listener never leaks past an abort/finish.
 */
interface PairingTransport {
    /** Begin listening for the child's §7.2 POST (bind the `/pair` port). */
    fun start()

    /** Tear down the listener (idempotent). */
    fun stop()

    companion object {
        /** No-op transport (the controller default; used where the listener lifecycle is out of scope). */
        val None: PairingTransport =
            object : PairingTransport {
                override fun start() {}

                override fun stop() {}
            }
    }
}

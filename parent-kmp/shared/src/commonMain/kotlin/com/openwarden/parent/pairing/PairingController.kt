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
     * Begin a pairing attempt: mint the §7.1 nonce + QR and publish [PairingPhase.ShowingQr], starting the
     * [PairingTransport] so the child can POST. If the parent has no confirmed root key the attempt is
     * refused fail-closed ([PairingPhase.NotProvisioned], ADR-035 D7) and the transport is **not** started.
     */
    fun begin(): Unit =
        monitor.runGuarded {
            pendingChallenge = null
            when (val result = sessions.start()) {
                is PairingStartResult.Started -> {
                    _phase.value = PairingPhase.ShowingQr(result.session.payloadJson)
                    // Start the listener only once a real attempt exists (ADR-043 D4) — never on the
                    // NotProvisioned path, so a not-yet-provisioned parent never opens the /pair port.
                    transport.start()
                }

                PairingStartResult.NotProvisioned -> {
                    _phase.value = PairingPhase.NotProvisioned
                }
            }
        }

    /**
     * The slice-(c) [AttestationVerifier] seam (ADR-043 D2). Called by [PairingEndpoint] on the network
     * thread, already under the server's `sessionLock`. Delegates to the real verifier; on `Accepted`
     * derives the §7.4 SAS and moves to [PairingPhase.AwaitingSas]; on `Refused` moves to
     * [PairingPhase.Aborted] (fail-closed — the nonce was already burned by the verifier, ADR-037 D2) and
     * stops the listener. Returns the verifier's verdict verbatim so the endpoint's HTTP mapping is unchanged.
     *
     * NOTE: this is the one entry point **not** wrapped in [monitor] — it is already serialized by the
     * caller (the server holds `sessionLock`, the same monitor object), so re-guarding would be redundant.
     */
    override fun verify(post: ValidatedPairingPost): AttestationOutcome {
        val outcome = attestation.verify(post)
        when (outcome) {
            is AttestationOutcome.Accepted -> {
                // The post is live exactly here (ADR-043 D2): derive the SAS for both sides to compare.
                val challenge = sasStage.derive(post)
                pendingChallenge = challenge
                _phase.value = PairingPhase.AwaitingSas(challenge.emojis)
            }

            is AttestationOutcome.Refused -> {
                // Fail-closed: the verifier already burned the single-use nonce (ADR-037 D2); the attempt
                // is dead, so surface the abort and tear the listener down. Internal reason not echoed
                // (no local oracle value; the UI shows the generic §7.3 message — ADR-043 D5).
                pendingChallenge = null
                _phase.value = PairingPhase.Aborted(PairingAbortReason.ATTESTATION_FAILED)
                transport.stop()
            }
        }
        return outcome
    }

    /**
     * Record the human's §7.4 verdict for the awaiting SAS. Delegates to [PairingPinCoordinator]:
     * Match pins both child keys (write-once) + burns the session; Mismatch/Stale/AlreadyPaired pin
     * nothing. Maps the [PinOutcome] to a terminal [PairingPhase] and stops the listener. A `confirm`
     * with no awaiting challenge resolves to [PairingAbortReason.NO_LIVE_ATTEMPT] (fail-closed no-op).
     */
    fun confirm(matched: Boolean): Unit =
        monitor.runGuarded {
            val challenge =
                pendingChallenge ?: run {
                    _phase.value = PairingPhase.Aborted(PairingAbortReason.NO_LIVE_ATTEMPT)
                    transport.stop()
                    return@runGuarded
                }
            pendingChallenge = null
            _phase.value =
                when (coordinator.confirmAndPin(challenge, matched)) {
                    is PinOutcome.Pinned -> PairingPhase.Pinned

                    PinOutcome.Aborted -> PairingPhase.Aborted(PairingAbortReason.SAS_MISMATCH)

                    PinOutcome.Stale -> PairingPhase.Aborted(PairingAbortReason.STALE)

                    // ADR-039 D3: surfaced distinctly from a SAS mismatch (it is a recovery-gated rotation,
                    // not a MITM warning) so the UI can route to the right message.
                    PinOutcome.AlreadyPaired -> PairingPhase.Aborted(PairingAbortReason.ALREADY_PAIRED)
                }
            transport.stop()
        }

    /**
     * Abandon the in-flight attempt (the parent backed out of the QR/SAS screen). Burns the pending
     * session (a fresh QR is required to retry), stops the listener, and returns to [PairingPhase.Idle].
     */
    fun cancel(): Unit =
        monitor.runGuarded {
            pendingChallenge = null
            sessions.cancel()
            transport.stop()
            _phase.value = PairingPhase.Idle
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

package com.openwarden.parent.pairing

import com.openwarden.parent.crypto.RootKeyProvider
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Slice (f) contract (ADR-043): [PairingController] joins the merged (a)–(e) seams into the §7 state
 * machine and is the production caller ADR-039 D5a said was missing. Proven here deterministically
 * against the same kind of injected fakes the (b)–(e) tests use (the Compose screen + real Ktor binding
 * are the build-verified residual, ADR-043 D6):
 *
 *  - begin ⇒ ShowingQr (+ transport started) | NotProvisioned (no start, fail-closed);
 *  - a verifier Accept ⇒ AwaitingSas with the derived emojis (and verify() returns Accepted verbatim);
 *  - a verifier Refuse ⇒ Aborted(ATTESTATION_FAILED), nothing pinned, nonce burned;
 *  - confirm(true)/false/stale/already-paired ⇒ the right terminal phase, fail-closed pinning unchanged;
 *  - cancel ⇒ Idle (+ session burned + transport stopped);
 *  - the fail-closed gaps Codex flagged: a 2nd Accepted post can't swap the human-compared challenge (F1),
 *    and a verifier throw / pin-write failure / listener-bind failure all abort fail-closed (F2/F3/F4);
 *  - every mutating entry routes through the monitor (ADR-043 D3).
 */
class PairingControllerTest {
    private val parentEd = ByteArray(32) { 1 }
    private val parentX = ByteArray(32) { 2 }
    private val childEd = ByteArray(32) { 3 }
    private val childX = ByteArray(32) { 4 }
    private val nonce = ByteArray(32) { 9 }

    /** Root keys present unless [provisioned] is false (then everything is null → NotProvisioned). */
    private class FakeRootKeys(
        private val ed: ByteArray?,
        private val x: ByteArray?,
    ) : RootKeyProvider {
        override fun rootPublicKey(): ByteArray? = ed

        override fun encryptionPublicKey(): ByteArray? = x

        override fun sign(message: ByteArray): ByteArray? = null
    }

    private inner class FakeNonceSource : PairingNonceSource {
        override fun freshNonce(): ByteArray = nonce.copyOf()
    }

    /** Write-once atomic [PinnedChildStore] double (same shape as PairingPinCoordinatorTest). */
    private class FakePinnedChildStore(
        var pinned: PinnedChild? = null,
        private val failWrite: Boolean = false,
    ) : PinnedChildStore {
        var pinCalls = 0
            private set

        override fun pinnedChild(): PinnedChild? = pinned

        override fun pin(child: PinnedChild) {
            pinCalls += 1
            check(pinned == null) { "child already pinned (write-once)" }
            if (failWrite) error("simulated commit() failure (fail-closed)")
            pinned = child
        }
    }

    /** A verifier double: ACCEPT, REFUSE (burns the nonce, ADR-037 D2), or THROW (verifier seam blows up). */
    private class FakeVerifier(
        private val mode: Mode,
        private val burner: PairingNonceBurner,
    ) : AttestationVerifier {
        enum class Mode { ACCEPT, REFUSE, THROW }

        override fun verify(post: ValidatedPairingPost): AttestationOutcome =
            when (mode) {
                Mode.ACCEPT -> {
                    AttestationOutcome.Accepted
                }

                Mode.REFUSE -> {
                    burner.burn()
                    AttestationOutcome.Refused("attestation: test-refuse")
                }

                Mode.THROW -> {
                    error("verifier boom")
                }
            }
    }

    /** Counts listener start/stop so the lifecycle (ADR-043 D4) is asserted; can simulate a bind failure. */
    private class FakeTransport(
        private val failStart: Boolean = false,
    ) : PairingTransport {
        var starts = 0
            private set
        var stops = 0
            private set

        override fun start() {
            starts += 1
            if (failStart) error("simulated bind failure")
        }

        override fun stop() {
            stops += 1
        }
    }

    /** Counts guarded entries so we prove begin/confirm/cancel route through the monitor (ADR-043 D3). */
    private class CountingMonitor : PairingMonitor {
        var guarded = 0
            private set

        override fun runGuarded(block: () -> Unit) {
            guarded += 1
            block()
        }
    }

    /** The full wiring under test: a real session manager + the (b)–(e) seams over one fake store. */
    private inner class Harness(
        provisioned: Boolean = true,
        verifierMode: FakeVerifier.Mode = FakeVerifier.Mode.ACCEPT,
        prePinned: PinnedChild? = null,
        storeFailsWrite: Boolean = false,
        transportFailsStart: Boolean = false,
    ) {
        val manager =
            PairingSessionManager(
                rootKeys = FakeRootKeys(if (provisioned) parentEd else null, if (provisioned) parentX else null),
                nonceSource = FakeNonceSource(),
                nowMs = { 0L },
            )
        val access = DirectSessionAccess(manager)
        val burner = PairingNonceBurner { manager.cancel() }
        val stage = PairingSasStage(access, SixEmojiSas { _, _, _, length -> ByteArray(length) }, burner)
        val store = FakePinnedChildStore(pinned = prePinned, failWrite = storeFailsWrite)
        val coordinator = PairingPinCoordinator(stage, store, access)
        val verifier = FakeVerifier(verifierMode, burner)
        val transport = FakeTransport(failStart = transportFailsStart)
        val monitor = CountingMonitor()
        val controller =
            PairingController(manager, stage, coordinator, verifier, transport, monitor)

        /** The shape-validated §7.2 post bound to the manager's currently-live session (default child keys). */
        fun livePost(
            ed: ByteArray = childEd,
            x: ByteArray = childX,
        ): ValidatedPairingPost {
            val session = manager.active()!!
            val response =
                ChildPairingResponse(
                    v = 1,
                    childEd25519Pub = Base64Url.encode(ed),
                    childX25519Pub = Base64Url.encode(x),
                    childAttestationCertChain = listOf("Y2VydA"),
                    childBindingSig = "ab".repeat(35),
                )
            return ValidatedPairingPost(session, response, ed, x)
        }
    }

    @Test
    fun beginShowsQrAndStartsListener() {
        val h = Harness()
        h.controller.begin()

        val phase = h.controller.phase.value
        assertTrue(phase is PairingPhase.ShowingQr, "a provisioned parent shows the §7.1 QR")
        assertTrue(phase.qrPayloadJson.contains("parent_ed25519_pub"), "the QR payload is the §7.1 JSON")
        assertEquals(1, h.transport.starts, "the listener starts for a real attempt (ADR-043 D4)")
        assertEquals(0, h.transport.stops)
    }

    @Test
    fun beginWithNoRootKeyIsNotProvisionedAndStartsNothing() {
        val h = Harness(provisioned = false)
        h.controller.begin()

        assertEquals(PairingPhase.NotProvisioned, h.controller.phase.value, "no root key ⇒ NotProvisioned (ADR-035 D7)")
        assertEquals(0, h.transport.starts, "the /pair listener never opens for an unprovisioned parent (fail-closed)")
        assertNull(h.manager.active(), "no session is minted")
    }

    @Test
    fun acceptedPostDerivesSasAndAwaitsTap() {
        val h = Harness()
        h.controller.begin()

        val outcome = h.controller.verify(h.livePost())

        assertSame(AttestationOutcome.Accepted, outcome, "verify() returns the verdict verbatim for the endpoint")
        val phase = h.controller.phase.value
        assertTrue(phase is PairingPhase.AwaitingSas, "an Accepted post moves to the §7.4 SAS compare")
        assertEquals(SasEmojiTable.EMOJI_COUNT, phase.emojis.size, "six emojis are derived for the human compare")
    }

    @Test
    fun refusedPostAbortsFailClosedAndBurnsNonce() {
        val h = Harness(verifierMode = FakeVerifier.Mode.REFUSE)
        h.controller.begin()

        val outcome = h.controller.verify(h.livePost())

        assertTrue(outcome is AttestationOutcome.Refused, "verify() returns the refusal verbatim")
        assertEquals(
            PairingPhase.Aborted(PairingAbortReason.ATTESTATION_FAILED),
            h.controller.phase.value,
            "a §7.3 attestation failure aborts the pair (fail-closed)",
        )
        assertNull(h.store.pinned, "nothing is pinned on an attestation failure")
        assertNull(h.manager.active(), "the verifier burned the single-use nonce (ADR-037 D2)")
        // verify() runs on the network thread under the server lock; it does NOT run the blocking Ktor stop
        // (Codex F5). The burned session makes the endpoint inert; the UI's cancel() stops the listener.
        assertEquals(0, h.transport.stops, "verify() never runs the blocking listener stop (Codex F5)")
    }

    @Test
    fun secondAcceptedPostDoesNotSwapTheHumanComparedChallenge() {
        // Codex F1 (CRITICAL, H3): once a SAS is awaiting the tap, a later POST with DIFFERENT child keys
        // must be refused WITHOUT overwriting the pending challenge — confirm() must pin the FIRST keys the
        // human actually compared, never the second post's substituted keys.
        val attackerEd = ByteArray(32) { 88 }
        val attackerX = ByteArray(32) { 99 }
        val h = Harness()
        h.controller.begin()

        h.controller.verify(h.livePost()) // post 1: the legit child keys, now AwaitingSas
        val second = h.controller.verify(h.livePost(ed = attackerEd, x = attackerX)) // post 2: substituted keys

        assertTrue(second is AttestationOutcome.Refused, "a second post during AwaitingSas is refused (F1)")
        assertTrue(h.controller.phase.value is PairingPhase.AwaitingSas, "the displayed challenge is unchanged")

        h.controller.confirm(matched = true)

        assertContentEquals(childEd, h.store.pinned!!.ed25519Pub, "the FIRST (human-compared) key is pinned, not the swap")
        assertContentEquals(childX, h.store.pinned!!.x25519Pub)
    }

    @Test
    fun verifierThrowAbortsFailClosed() {
        // Codex F2: a verifier/derive exception must not leave a live session/listener — burn + abort + refuse.
        val h = Harness(verifierMode = FakeVerifier.Mode.THROW)
        h.controller.begin()

        val outcome = h.controller.verify(h.livePost())

        assertTrue(outcome is AttestationOutcome.Refused, "a thrown verifier resolves to a generic refusal")
        assertEquals(
            PairingPhase.Aborted(PairingAbortReason.INTERNAL_ERROR),
            h.controller.phase.value,
            "a verifier throw aborts fail-closed (Codex F2)",
        )
        assertNull(h.store.pinned, "nothing is pinned")
        assertNull(h.manager.active(), "the session is burned on the throw (no live attempt left)")
    }

    @Test
    fun pinWriteFailureAbortsFailClosedAndBurnsSession() {
        // Codex F3: a store/commit failure (ADR-039 D2 throws) must not strand a live half-state.
        val h = Harness(storeFailsWrite = true)
        h.controller.begin()
        h.controller.verify(h.livePost())

        h.controller.confirm(matched = true)

        assertEquals(
            PairingPhase.Aborted(PairingAbortReason.INTERNAL_ERROR),
            h.controller.phase.value,
            "a pin/commit failure aborts fail-closed (Codex F3)",
        )
        assertNull(h.store.pinned, "a failed write leaves NOTHING pinned (no half-pin)")
        assertNull(h.manager.active(), "the still-live session is burned on the failed pin (no stuck attempt)")
        assertEquals(1, h.transport.stops, "the listener is stopped on the failure")
    }

    @Test
    fun listenerBindFailureRollsBackFailClosed() {
        // Codex F4: if the Ktor bind fails, do not strand a scannable QR over a session with no listener.
        val h = Harness(transportFailsStart = true)

        h.controller.begin()

        assertEquals(
            PairingPhase.Aborted(PairingAbortReason.INTERNAL_ERROR),
            h.controller.phase.value,
            "a listener-bind failure rolls back to a fail-closed abort (Codex F4)",
        )
        assertNull(h.manager.active(), "the session is burned — no stranded QR over a dead listener")
    }

    @Test
    fun matchPinsBothKeysBurnsSessionAndStopsListener() {
        val h = Harness()
        h.controller.begin()
        h.controller.verify(h.livePost())

        h.controller.confirm(matched = true)

        assertEquals(PairingPhase.Pinned, h.controller.phase.value, "Match pins the child")
        val pinned = h.store.pinned!!
        assertContentEquals(childEd, pinned.ed25519Pub, "child_ed25519_pub pinned")
        assertContentEquals(childX, pinned.x25519Pub, "child_x25519_pub pinned (sealed-box audience, ADR-015)")
        assertNull(h.manager.active(), "the single-use session is burned after the pin (ADR-039 D4)")
        assertEquals(1, h.transport.stops, "the listener stops on the terminal Pinned phase")
    }

    @Test
    fun mismatchAbortsPinsNothingAndStopsListener() {
        val h = Harness()
        h.controller.begin()
        h.controller.verify(h.livePost())

        h.controller.confirm(matched = false)

        assertEquals(
            PairingPhase.Aborted(PairingAbortReason.SAS_MISMATCH),
            h.controller.phase.value,
            "a SAS mismatch surfaces a MITM-distinct abort (ATTACKS H3)",
        )
        assertNull(h.store.pinned, "nothing is pinned on a mismatch")
        assertEquals(0, h.store.pinCalls)
        assertNull(h.manager.active(), "the mismatch burns the nonce (ADR-036 D4)")
        assertEquals(1, h.transport.stops)
    }

    @Test
    fun staleConfirmAbortsAndLeavesTheFreshAttemptLive() {
        val h = Harness()
        h.controller.begin()
        h.controller.verify(h.livePost()) // AwaitingSas, challenge bound to attempt 1

        // A fresh QR replaces attempt 1 while the human dawdled on the old emojis.
        h.manager.start()
        val attempt2 = h.manager.active()

        h.controller.confirm(matched = true) // late tap on the now-stale challenge

        assertEquals(
            PairingPhase.Aborted(PairingAbortReason.STALE),
            h.controller.phase.value,
            "a tap on a replaced attempt resolves Stale (ADR-038 session-bound confirm)",
        )
        assertNull(h.store.pinned, "a stale tap pins nothing")
        assertSame(attempt2, h.manager.active(), "the fresh attempt stays live (the stale tap burns nothing)")
    }

    @Test
    fun alreadyPairedAbortsDistinctlyAndDoesNotOverwrite() {
        val firstEd = ByteArray(32) { 7 }
        val firstX = ByteArray(32) { 8 }
        val h = Harness(prePinned = PinnedChild(firstEd, firstX))
        h.controller.begin()
        h.controller.verify(h.livePost())

        h.controller.confirm(matched = true)

        assertEquals(
            PairingPhase.Aborted(PairingAbortReason.ALREADY_PAIRED),
            h.controller.phase.value,
            "a second pin is refused distinctly from a mismatch (ADR-039 D3)",
        )
        assertContentEquals(firstEd, h.store.pinned!!.ed25519Pub, "the original pin is NOT overwritten")
        assertEquals(0, h.store.pinCalls, "the coordinator refuses before calling store.pin()")
    }

    @Test
    fun confirmWithNoLiveAttemptIsAFailClosedNoOp() {
        val h = Harness()
        h.controller.begin() // ShowingQr, but no SAS has been derived yet

        h.controller.confirm(matched = true)

        assertEquals(
            PairingPhase.Aborted(PairingAbortReason.NO_LIVE_ATTEMPT),
            h.controller.phase.value,
            "a confirm with nothing awaiting a tap is a defensive no-op",
        )
        assertNull(h.store.pinned, "nothing is pinned")
    }

    @Test
    fun cancelBurnsSessionStopsListenerAndReturnsToIdle() {
        val h = Harness()
        h.controller.begin()

        h.controller.cancel()

        assertEquals(PairingPhase.Idle, h.controller.phase.value, "cancel returns to Idle")
        assertNull(h.manager.active(), "cancel burns the pending session (a fresh QR is required to retry)")
        assertEquals(1, h.transport.stops, "cancel tears the listener down")
    }

    @Test
    fun mutatingEntriesRouteThroughTheMonitor() {
        val h = Harness()

        h.controller.begin() // 1
        h.controller.verify(h.livePost()) // verify() is guarded by the caller's lock, NOT the monitor (ADR-043 D2)
        h.controller.confirm(matched = true) // 2
        h.controller.cancel() // 3

        assertEquals(3, h.monitor.guarded, "begin + confirm + cancel each run under the monitor; verify does not")
    }
}

package com.openwarden.parent.pairing

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Slice (e) contract (ADR-039, ADR-025 D5e / §7.5): [PairingPinCoordinator] drives
 * [PairingSasStage.confirm] (the production caller ADR-038 D4a said was missing) and, on Match, pins
 * **both** child keys atomically + write-once, then burns the single-use session. Proven here
 * deterministically against the [PinnedChildStore] + [PairingSessionConsumer] seams (the
 * `EncryptedSharedPreferences` round-trip is the Android adapter's instrumented concern; this is the
 * fail-closed *contract*):
 *
 *  - Match ⇒ both keys pinned (byte-equal) + session burned;
 *  - every failure branch (Mismatch / Stale / write failure / already-paired) ⇒ **nothing pinned**;
 *  - half-pin is impossible (a write failure throws and burns nothing — the attempt stays unpaired);
 *  - double-pin / replay is refused without overwrite.
 */
class PairingPinCoordinatorTest {
    private val parentEd = ByteArray(32) { 1 }
    private val parentX = ByteArray(32) { 2 }
    private val childEd = ByteArray(32) { 3 }
    private val childX = ByteArray(32) { 4 }
    private val nonce = ByteArray(32) { 9 }

    /** A live session holder that is BOTH the stage's [SessionAccess] and the coordinator's consumer. */
    private class FakeSessions(
        var session: PairingSession?,
    ) : SessionAccess,
        PairingSessionConsumer {
        var cancels = 0
            private set
        var consumes = 0
            private set

        override fun active(): PairingSession? = session

        override fun cancel() {
            cancels += 1
            session = null
        }

        override fun consume(): PairingSession? {
            val s = session ?: return null
            consumes += 1
            session = null
            return s
        }
    }

    /** Write-once, atomic [PinnedChildStore] double; can simulate a commit failure (fail-closed). */
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
            pinned = child // atomic: the fake either fully sets or threw — no half-pin.
        }
    }

    private fun liveSession(): PairingSession {
        val qr =
            PairingQrPayload(
                v = 1,
                parentEd25519Pub = Base64Url.encode(parentEd),
                parentX25519Pub = Base64Url.encode(parentX),
                provisioningNonce = Base64Url.encode(nonce),
                transportHints = TransportHints(mdns = "_openwarden._tcp.local"),
            )
        return PairingSession(qr.toJson(), nonce, createdAtMs = 0L, ttlMs = 1_000L)
    }

    private fun post(session: PairingSession): ValidatedPairingPost {
        val response =
            ChildPairingResponse(
                v = 1,
                childEd25519Pub = Base64Url.encode(childEd),
                childX25519Pub = Base64Url.encode(childX),
                childAttestationCertChain = listOf("Y2VydA"),
                childBindingSig = "ab".repeat(35),
            )
        return ValidatedPairingPost(session, response, childEd, childX)
    }

    /** A stage wired so its mismatch-burner cancels the SAME session holder (the coordinator's shape). */
    private fun stage(sessions: FakeSessions) =
        PairingSasStage(sessions, SixEmojiSas { _, _, _, length -> ByteArray(length) }, PairingNonceBurner { sessions.cancel() })

    private fun coordinator(
        sessions: FakeSessions,
        store: PinnedChildStore,
    ) = PairingPinCoordinator(stage(sessions), store, sessions)

    /** Derive a SAS challenge bound to the holder's current live session. */
    private fun deriveChallenge(sessions: FakeSessions): SasChallenge = stage(sessions).derive(post(sessions.session!!))

    @Test
    fun matchPinsBothKeysAtomicallyAndBurnsSession() {
        val sessions = FakeSessions(liveSession())
        val store = FakePinnedChildStore()
        val coord = coordinator(sessions, store)
        val challenge = deriveChallenge(sessions)

        val outcome = coord.confirmAndPin(challenge, matched = true)

        assertTrue(outcome is PinOutcome.Pinned, "Match pins the child")
        val pinned = store.pinned!!
        assertContentEquals(childEd, pinned.ed25519Pub, "child_ed25519_pub pinned")
        assertContentEquals(childX, pinned.x25519Pub, "child_x25519_pub pinned (sealed-box audience, ADR-015)")
        assertContentEquals(childEd, outcome.childEd25519Pub)
        assertContentEquals(childX, outcome.childX25519Pub)
        assertEquals(1, sessions.consumes, "the single-use session is burned exactly once after the pin (ADR-039 D4)")
        assertEquals(0, sessions.cancels, "a successful pin never goes through the mismatch-burn path")
        assertNull(sessions.active(), "session is dead after a successful pin")
    }

    @Test
    fun mismatchPinsNothingAndAborts() {
        val sessions = FakeSessions(liveSession())
        val store = FakePinnedChildStore()
        val coord = coordinator(sessions, store)
        val challenge = deriveChallenge(sessions)

        val outcome = coord.confirmAndPin(challenge, matched = false)

        assertTrue(outcome is PinOutcome.Aborted, "a SAS mismatch aborts the pair")
        assertNull(store.pinned, "nothing is pinned on Mismatch")
        assertEquals(0, store.pinCalls, "pin() is never called on Mismatch")
        assertEquals(1, sessions.cancels, "Mismatch burns the nonce inside confirm() (ADR-036 D4)")
        assertEquals(0, sessions.consumes, "the success-consume path is not taken on Mismatch")
        assertNull(sessions.active(), "session is dead after a Mismatch")
    }

    @Test
    fun staleChallengePinsNothingAndLeavesTheNewAttemptLive() {
        val sessions = FakeSessions(liveSession()) // attempt 1
        val store = FakePinnedChildStore()
        val coord = coordinator(sessions, store)
        val staleChallenge = deriveChallenge(sessions) // bound to attempt 1

        val attempt2 = liveSession()
        sessions.session = attempt2 // attempt 1 abandoned; a fresh attempt is live

        val outcome = coord.confirmAndPin(staleChallenge, matched = true) // late tap on the stale challenge

        assertTrue(outcome is PinOutcome.Stale, "a stale challenge resolves to Stale")
        assertNull(store.pinned, "a stale challenge pins nothing")
        assertEquals(0, store.pinCalls)
        assertEquals(0, sessions.cancels, "a stale challenge must NOT burn the fresh live attempt")
        assertEquals(0, sessions.consumes)
        assertSame(attempt2, sessions.active(), "attempt 2 stays live and pinnable")
    }

    @Test
    fun alreadyPairedRefusesAndDoesNotOverwrite() {
        val firstEd = ByteArray(32) { 7 }
        val firstX = ByteArray(32) { 8 }
        val sessions = FakeSessions(liveSession())
        val store = FakePinnedChildStore(pinned = PinnedChild(firstEd, firstX))
        val coord = coordinator(sessions, store)
        val challenge = deriveChallenge(sessions)

        val outcome = coord.confirmAndPin(challenge, matched = true)

        assertTrue(outcome is PinOutcome.AlreadyPaired, "a second pin is refused (write-once, rotation is recovery-gated)")
        assertContentEquals(firstEd, store.pinned!!.ed25519Pub, "the original pin is NOT overwritten")
        assertContentEquals(firstX, store.pinned!!.x25519Pub)
        assertEquals(0, store.pinCalls, "the coordinator refuses before calling store.pin()")
        assertEquals(1, sessions.consumes, "the spent attempt is still burned (single-use)")
    }

    @Test
    fun pinThenReplayIsRejected() {
        val sessions = FakeSessions(liveSession())
        val store = FakePinnedChildStore()
        val coord = coordinator(sessions, store)

        val first = coord.confirmAndPin(deriveChallenge(sessions), matched = true)
        assertTrue(first is PinOutcome.Pinned)

        // A replayed/fresh attempt with the same child keys must not re-pin.
        sessions.session = liveSession()
        val replay = coord.confirmAndPin(deriveChallenge(sessions), matched = true)

        assertTrue(replay is PinOutcome.AlreadyPaired, "double-pin / replay is rejected")
        assertContentEquals(childEd, store.pinned!!.ed25519Pub, "the first pin stands")
    }

    @Test
    fun pinWriteFailureLeavesUnpairedAndSessionLive() {
        val sessions = FakeSessions(liveSession())
        val store = FakePinnedChildStore(failWrite = true)
        val coord = coordinator(sessions, store)
        val challenge = deriveChallenge(sessions)

        assertFailsWith<IllegalStateException>("a store write failure is fail-closed (throws)") {
            coord.confirmAndPin(challenge, matched = true)
        }

        assertNull(store.pinned, "a failed write leaves NOTHING pinned (no half-pin)")
        assertEquals(0, sessions.consumes, "the session is NOT consumed when the pin failed")
        assertEquals(0, sessions.cancels)
        assertSame(challenge.session, sessions.active(), "the attempt stays live (fail-closed → unpaired, not stuck)")
    }

    @Test
    fun matchPinsAndBurnsSoEndpointSeesNoSession() {
        // Integration twin of PairingSasBurnIntegrationTest: the coordinator's consume() must globally
        // kill the session so the very next POST the endpoint serves is NO_SESSION (fresh QR required).
        val sessions = FakeSessions(liveSession())
        val store = FakePinnedChildStore()
        val coord = coordinator(sessions, store)

        assertTrue(coord.confirmAndPin(deriveChallenge(sessions), matched = true) is PinOutcome.Pinned)
        assertContentEquals(childEd, store.pinned!!.ed25519Pub)

        val endpoint = PairingEndpoint(sessions, RefuseAllAttestationVerifier, PairingRateLimiter({ 0L }, maxPerWindow = 1_000))
        val next = endpoint.handle(PairingRequest("10.0.0.9", wireBody()))
        assertEquals(PairingPostResult.Refused(RefusalReason.NO_SESSION), next, "no POST is served after the pin burned the session")
    }

    private fun wireBody(): String =
        "{\"v\":1,\"child_ed25519_pub\":\"${Base64Url.encode(childEd)}\"," +
            "\"child_x25519_pub\":\"${Base64Url.encode(childX)}\"," +
            "\"child_attestation_cert_chain\":[\"Y2VydA\"],\"child_binding_sig\":\"${"ab".repeat(35)}\"}"
}

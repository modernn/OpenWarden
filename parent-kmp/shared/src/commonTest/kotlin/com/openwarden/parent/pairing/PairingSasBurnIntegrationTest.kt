package com.openwarden.parent.pairing

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Integration regression for the §7.4 burn-across-the-slice-boundary invariant (ADR-036 D4 HARD
 * criterion; crypto-review HIGH-1): a SAS **Mismatch** must *globally* kill the pairing session, so the
 * very next POST the endpoint serves is `NO_SESSION`. This is the SAS-path twin of
 * [PairingBurnIntegrationTest] (which proves it for an attestation refusal).
 *
 * It wires a real [PairingSasStage] to a [PairingNonceBurner] that cancels the **same** [SessionAccess]
 * the [PairingEndpoint] reads — the exact shape the (not-yet-built) pairing coordinator will assemble
 * when it drives derive → human-compare → confirm. The SAS values themselves are irrelevant to the burn,
 * so a trivial [SasKdf] stands in for the device HKDF (the real-crypto KAT lives in
 * `SixEmojiSasRealKdfTest`); what this pins is that `confirm(matched = false)` reaches the live session.
 */
class PairingSasBurnIntegrationTest {
    private val parentEd = ByteArray(32) { 1 }
    private val parentX = ByteArray(32) { 2 }
    private val childEd = ByteArray(32) { 3 }
    private val childX = ByteArray(32) { 4 }
    private val nonce = ByteArray(32) { 9 }

    private class MutableSession(
        var session: PairingSession?,
    ) : SessionAccess {
        var cancels = 0
            private set

        override fun active(): PairingSession? = session

        override fun cancel() {
            cancels += 1
            session = null
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

    private fun wireBody(): String =
        "{\"v\":1,\"child_ed25519_pub\":\"${Base64Url.encode(childEd)}\"," +
            "\"child_x25519_pub\":\"${Base64Url.encode(childX)}\"," +
            "\"child_attestation_cert_chain\":[\"Y2VydA\"],\"child_binding_sig\":\"${"ab".repeat(35)}\"}"

    private fun stage(sessions: MutableSession) =
        // The stage's burner cancels the SAME SessionAccess the endpoint reads (the coordinator's shape).
        PairingSasStage(sessions, SixEmojiSas { _, _, _, length -> ByteArray(length) }, PairingNonceBurner { sessions.cancel() })

    @Test
    fun sasMismatchBurnsSessionSoNextPostSeesNoSession() {
        val sessions = MutableSession(liveSession())
        val stage = stage(sessions)

        val challenge = stage.derive(post(sessions.session!!))
        val outcome = stage.confirm(challenge, matched = false)

        assertTrue(outcome is SasOutcome.Mismatch, "a SAS mismatch aborts the pair")
        assertEquals(1, sessions.cancels, "the mismatch burned the live session exactly once (ADR-036 D4)")
        assertNull(sessions.active(), "the session is globally dead after a SAS mismatch")

        // The invariant: a subsequent child POST to the live endpoint now sees NO_SESSION (fresh QR required).
        val endpoint = PairingEndpoint(sessions, RefuseAllAttestationVerifier, PairingRateLimiter({ 0L }, maxPerWindow = 1_000))
        val next = endpoint.handle(PairingRequest("10.0.0.9", wireBody()))
        assertEquals(PairingPostResult.Refused(RefusalReason.NO_SESSION), next, "no POST is served on a burned session")
    }

    @Test
    fun sasMatchLeavesSessionLiveForPinningSlice() {
        val sessions = MutableSession(liveSession())
        val stage = stage(sessions)

        val outcome = stage.confirm(stage.derive(post(sessions.session!!)), matched = true)

        assertTrue(outcome is SasOutcome.Match, "a SAS match proceeds toward pinning")
        assertEquals(0, sessions.cancels, "a match never burns the session")
        assertEquals(sessions.session, sessions.active(), "the session stays live for slice (e) to pin + consume")
    }

    @Test
    fun staleChallengeMismatchDoesNotBurnANewLiveAttempt() {
        val sessions = MutableSession(liveSession()) // attempt 1
        val stage = stage(sessions)
        val staleChallenge = stage.derive(post(sessions.session!!)) // bound to attempt 1

        // Attempt 1 is abandoned; a fresh attempt 2 becomes the live session (its own nonce).
        val attempt2 = liveSession()
        sessions.session = attempt2

        val outcome = stage.confirm(staleChallenge, matched = false) // late Mismatch tap on the stale challenge

        assertTrue(outcome is SasOutcome.Stale, "a stale challenge resolves to Stale")
        assertEquals(0, sessions.cancels, "a stale challenge's mismatch must NOT burn the fresh live attempt's nonce")
        assertEquals(attempt2, sessions.active(), "attempt 2 stays live and pinnable")
    }
}

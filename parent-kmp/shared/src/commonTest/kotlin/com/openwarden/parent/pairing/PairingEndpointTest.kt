package com.openwarden.parent.pairing

import com.openwarden.parent.policy.FakeRootKeyProvider
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** A controllable [SessionAccess] for the endpoint matrix. */
private class FakeSessionAccess(
    var session: PairingSession?,
) : SessionAccess {
    var cancelCount = 0
        private set

    override fun active(): PairingSession? = session

    override fun cancel() {
        cancelCount += 1
        session = null
    }
}

/** Records the handed-off post and returns a fixed [outcome]. */
private class CapturingVerifier(
    private val outcome: AttestationOutcome = AttestationOutcome.Accepted,
) : AttestationVerifier {
    var calls = 0
        private set
    var last: ValidatedPairingPost? = null
        private set

    override fun verify(post: ValidatedPairingPost): AttestationOutcome {
        calls += 1
        last = post
        return outcome
    }
}

private class FixedNonce(
    private val b: Byte = 5,
) : PairingNonceSource {
    override fun freshNonce(): ByteArray = ByteArray(32) { b }
}

class PairingEndpointTest {
    private val edValid = Base64Url.encode(ByteArray(32) { 3 })
    private val xValid = Base64Url.encode(ByteArray(32) { 4 })
    private val sigValid = "ab".repeat(35) // even-length hex

    private fun session(nonceByte: Byte = 9) =
        PairingSession(payloadJson = "{}", nonceBytes = ByteArray(32) { nonceByte }, createdAtMs = 0L, ttlMs = 1_000L)

    private fun body(
        v: Int = 1,
        ed: String = edValid,
        x: String = xValid,
        chain: String = "[\"Y2VydA\"]",
        sig: String = sigValid,
    ): String =
        "{\"v\":$v,\"child_ed25519_pub\":\"$ed\",\"child_x25519_pub\":\"$x\"," +
            "\"child_attestation_cert_chain\":$chain,\"child_binding_sig\":\"$sig\"}"

    private fun req(
        body: String,
        source: String = "10.0.0.9",
    ) = PairingRequest(source, body)

    private fun endpoint(
        sessions: SessionAccess,
        verifier: AttestationVerifier = CapturingVerifier(),
        rate: PairingRateLimiter = PairingRateLimiter({ 0L }, maxPerWindow = 1_000),
        maxAttempts: Int = 5,
        maxBody: Int = 16 * 1024,
    ) = PairingEndpoint(sessions, verifier, rate, maxAttempts, maxBody)

    /** Acceptance: a valid POST against a live session is parsed, byte-validated, and handed to (c). */
    @Test
    fun validPostHandedToVerifierWithDecodedKeys() {
        val s = session()
        val v = CapturingVerifier(AttestationOutcome.Accepted)
        val result = endpoint(FakeSessionAccess(s), v).handle(req(body()))

        assertTrue(result is PairingPostResult.HandedOff)
        assertTrue(result.outcome is AttestationOutcome.Accepted)
        assertEquals(1, v.calls)
        val handed = assertNotNull(v.last)
        assertSame(s, handed.session)
        assertTrue(handed.childEd25519Pub.contentEquals(ByteArray(32) { 3 }), "ed decoded to 32 bytes")
        assertTrue(handed.childX25519Pub.contentEquals(ByteArray(32) { 4 }), "x decoded to 32 bytes")
    }

    /** Fail-closed default: the shipped RefuseAll verifier refuses every well-formed pair (nothing pins). */
    @Test
    fun refuseAllDefaultRefusesValidPost() {
        val result = endpoint(FakeSessionAccess(session()), RefuseAllAttestationVerifier).handle(req(body()))
        assertTrue(result is PairingPostResult.HandedOff)
        assertTrue(result.outcome is AttestationOutcome.Refused)
    }

    /** No live session ⇒ refuse, and the verifier is never reached. */
    @Test
    fun noSessionRefused() {
        val v = CapturingVerifier()
        val result = endpoint(FakeSessionAccess(null), v).handle(req(body()))
        assertEquals(PairingPostResult.Refused(RefusalReason.NO_SESSION), result)
        assertEquals(0, v.calls)
    }

    /** An expired session (real manager via DirectSessionAccess) reads as no session. */
    @Test
    fun expiredSessionRefused() {
        var now = 0L
        val mgr =
            PairingSessionManager(
                rootKeys = FakeRootKeyProvider(provisioned = true),
                nonceSource = FixedNonce(),
                nowMs = { now },
                ttlMs = 100L,
            )
        mgr.start()
        now = 100L // == createdAt + ttl ⇒ expired
        val result = endpoint(DirectSessionAccess(mgr)).handle(req(body()))
        assertEquals(PairingPostResult.Refused(RefusalReason.NO_SESSION), result)
    }

    /** Replay after the session is burned (e.g. consumed by slice (e)) ⇒ refuse. */
    @Test
    fun replayAfterBurnRefused() {
        val fake = FakeSessionAccess(session())
        val ep = endpoint(fake)
        assertTrue(ep.handle(req(body())) is PairingPostResult.HandedOff)
        fake.cancel() // session gone
        assertEquals(PairingPostResult.Refused(RefusalReason.NO_SESSION), ep.handle(req(body())))
    }

    /** The (N+1)th well-formed POST burns the session; subsequent POSTs see no session. */
    @Test
    fun attemptCapBurnsSession() {
        val fake = FakeSessionAccess(session())
        val ep = endpoint(fake, maxAttempts = 2)
        assertTrue(ep.handle(req(body())) is PairingPostResult.HandedOff)
        assertTrue(ep.handle(req(body())) is PairingPostResult.HandedOff)
        assertEquals(PairingPostResult.Refused(RefusalReason.TOO_MANY_ATTEMPTS), ep.handle(req(body())))
        assertEquals(1, fake.cancelCount, "session burned exactly once on cap")
        assertNull(fake.session)
        assertEquals(PairingPostResult.Refused(RefusalReason.NO_SESSION), ep.handle(req(body())))
    }

    /** A malformed-body flood does NOT count toward the cap and never burns the session. */
    @Test
    fun malformedDoesNotBurnSession() {
        val fake = FakeSessionAccess(session())
        val v = CapturingVerifier()
        val ep = endpoint(fake, v, maxAttempts = 2)
        repeat(10) { assertEquals(PairingPostResult.Refused(RefusalReason.MALFORMED), ep.handle(req("{not json"))) }
        assertEquals(0, v.calls)
        assertEquals(0, fake.cancelCount)
        assertNotNull(fake.session)
        // A genuine POST still goes through afterward.
        assertTrue(ep.handle(req(body())) is PairingPostResult.HandedOff)
    }

    @Test
    fun badVersionRefused() {
        val result = endpoint(FakeSessionAccess(session())).handle(req(body(v = 2)))
        assertEquals(PairingPostResult.Refused(RefusalReason.BAD_VERSION), result)
    }

    /** D6: a pubkey that is not exactly 32 decoded bytes (short, over-long, or non-base64url) ⇒ refuse. */
    @Test
    fun badPubkeyRefused() {
        val ep = { b: String -> endpoint(FakeSessionAccess(session())).handle(req(b)) }
        assertEquals(PairingPostResult.Refused(RefusalReason.BAD_PUBKEY), ep(body(ed = "tooshort")))
        assertEquals(PairingPostResult.Refused(RefusalReason.BAD_PUBKEY), ep(body(x = "tooshort")))
        // 33 decoded bytes (the ≥43-char-but-not-32-byte PR #64 gap).
        assertEquals(
            PairingPostResult.Refused(RefusalReason.BAD_PUBKEY),
            ep(body(ed = Base64Url.encode(ByteArray(33) { it.toByte() }))),
        )
        assertEquals(PairingPostResult.Refused(RefusalReason.BAD_PUBKEY), ep(body(ed = "not*base64url*here")))
    }

    @Test
    fun badCertChainRefused() {
        val ep = { b: String -> endpoint(FakeSessionAccess(session())).handle(req(b)) }
        assertEquals(PairingPostResult.Refused(RefusalReason.BAD_CERT_CHAIN), ep(body(chain = "[]")))
        assertEquals(PairingPostResult.Refused(RefusalReason.BAD_CERT_CHAIN), ep(body(chain = "[\"\"]")))
        val tooLong = (1..11).joinToString(",", "[", "]") { "\"Y2VydA\"" }
        assertEquals(PairingPostResult.Refused(RefusalReason.BAD_CERT_CHAIN), ep(body(chain = tooLong)))
    }

    @Test
    fun badSigEncodingRefused() {
        val ep = { b: String -> endpoint(FakeSessionAccess(session())).handle(req(b)) }
        assertEquals(PairingPostResult.Refused(RefusalReason.BAD_SIG_ENCODING), ep(body(sig = "")))
        assertEquals(PairingPostResult.Refused(RefusalReason.BAD_SIG_ENCODING), ep(body(sig = "abc")))
        assertEquals(PairingPostResult.Refused(RefusalReason.BAD_SIG_ENCODING), ep(body(sig = "zz")))
        // Over-length hex (would-be 16 KiB blob) refused before the verifier seam (ADR-036 D2 / Finding F).
        assertEquals(PairingPostResult.Refused(RefusalReason.BAD_SIG_ENCODING), ep(body(sig = "ab".repeat(200))))
    }

    /** An over-cap body is refused before parsing; the verifier is never reached. */
    @Test
    fun tooLargeRefused() {
        val v = CapturingVerifier()
        val ep = endpoint(FakeSessionAccess(session()), v, maxBody = 64)
        val big = body(sig = "ab".repeat(200)) // > 64 bytes
        assertEquals(PairingPostResult.Refused(RefusalReason.TOO_LARGE), ep.handle(req(big)))
        assertEquals(0, v.calls)
    }

    /** Per-source fixed-window rate limit throttles a burst, then recovers after the window. */
    @Test
    fun rateLimitedThenRecovers() {
        var now = 0L
        val rate = PairingRateLimiter({ now }, maxPerWindow = 2, windowMs = 10_000L)
        val ep = endpoint(FakeSessionAccess(session()), rate = rate)
        assertTrue(ep.handle(req(body())) is PairingPostResult.HandedOff)
        assertTrue(ep.handle(req(body())) is PairingPostResult.HandedOff)
        assertEquals(PairingPostResult.Refused(RefusalReason.RATE_LIMITED), ep.handle(req(body())))
        now = 10_000L // window rolls over
        assertTrue(ep.handle(req(body())) is PairingPostResult.HandedOff)
    }

    /** Rate limiting is per-source: throttling source A does not block source B. */
    @Test
    fun rateLimitIsPerSource() {
        val rate = PairingRateLimiter({ 0L }, maxPerWindow = 1, windowMs = 10_000L)
        val ep = endpoint(FakeSessionAccess(session()), rate = rate)
        assertTrue(ep.handle(req(body(), source = "10.0.0.1")) is PairingPostResult.HandedOff)
        assertEquals(
            PairingPostResult.Refused(RefusalReason.RATE_LIMITED),
            ep.handle(req(body(), source = "10.0.0.1")),
        )
        assertTrue(ep.handle(req(body(), source = "10.0.0.2")) is PairingPostResult.HandedOff)
    }
}

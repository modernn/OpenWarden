package com.openwarden.parent.pairing

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Integration regression for the burn-across-the-slice-boundary invariant (ADR-036 D4; crypto-review
 * MED-3): a §7.3 verifier refusal must *globally* kill the pairing session, so the very next POST the
 * endpoint serves is `NO_SESSION`. This wires the real [PairingEndpoint] to a
 * [Section73AttestationVerifier] whose burner cancels the **same** [SessionAccess] the endpoint reads
 * — the exact shape the Android `AndroidAttestationVerifierFactory` + `PairingServer` assemble — and
 * pins that the endpoint's stale `trackedSession`/`attempts` can never re-observe a live session
 * after a refuse (the staleness is inert because `active()` is the first session touch).
 */
class PairingBurnIntegrationTest {
    private val ed = Base64Url.encode(ByteArray(32) { 3 })
    private val x = Base64Url.encode(ByteArray(32) { 4 })
    private val sig = "ab".repeat(35)

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

    private fun wireBody(): String =
        "{\"v\":1,\"child_ed25519_pub\":\"$ed\",\"child_x25519_pub\":\"$x\"," +
            "\"child_attestation_cert_chain\":[\"Y2VydA\"],\"child_binding_sig\":\"$sig\"}"

    @Test
    fun verifierRefusalBurnsSessionSoNextPostSeesNoSession() {
        val sessions =
            MutableSession(
                PairingSession(payloadJson = "{}", nonceBytes = ByteArray(32) { 9 }, createdAtMs = 0L, ttlMs = 1_000L),
            )
        // A verifier that refuses (parser returns null ⇒ chain-parse), burning via the SAME SessionAccess.
        val verifier =
            Section73AttestationVerifier(
                parser =
                    object : AttestationChainParser {
                        override fun parse(certChainBase64Der: List<String>): AttestationEvidence? = null
                    },
                sigVerifier =
                    object : EcdsaP256BindingVerifier {
                        override fun verify(
                            leafSpkiDer: ByteArray,
                            signedBytes: ByteArray,
                            derSignature: ByteArray,
                        ) = true
                    },
                policy = AttestationPolicy(emptyList(), emptySet(), emptySet()),
                burner = { sessions.cancel() },
            )
        val endpoint = PairingEndpoint(sessions, verifier, PairingRateLimiter({ 0L }, maxPerWindow = 1_000))

        val first = endpoint.handle(PairingRequest("10.0.0.9", wireBody()))
        assertTrue(first is PairingPostResult.HandedOff)
        assertTrue(first.outcome is AttestationOutcome.Refused, "attestation refused")
        assertEquals(1, sessions.cancels, "the refusal burned the session")

        // The invariant: the session is globally dead, so the very next POST is NO_SESSION.
        val second = endpoint.handle(PairingRequest("10.0.0.9", wireBody()))
        assertEquals(PairingPostResult.Refused(RefusalReason.NO_SESSION), second)
    }
}

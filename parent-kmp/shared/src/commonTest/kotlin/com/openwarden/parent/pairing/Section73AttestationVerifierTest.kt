package com.openwarden.parent.pairing

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Deterministic, seam-injected matrix for the §7.3 verifier (ADR-037 D1, issue #96). With a fake
 * parser (crafted [AttestationEvidence]) + a programmable signature seam + a counting burner, every
 * one of checks 1–4b is driven to accept and to refuse — no device, no real chain, no native dep.
 *
 * Two invariants are asserted on top of each verdict:
 *  - **burn-on-failure (ADR-036 D4 HARD criterion):** the nonce is burned on *every* refusal and
 *    *never* on accept;
 *  - **check-4b wiring:** the signature seam is handed exactly `JCS{v,ed,x,nonce}` and the leaf SPKI.
 */
class Section73AttestationVerifierTest {
    private val nonce = ByteArray(32) { 9 }
    private val goodRoot = byteArrayOf(0x52, 0x6f, 0x6f, 0x74) // "Root"
    private val leafSpki = byteArrayOf(0x4c, 0x65, 0x61, 0x66) // "Leaf"
    private val ed = Base64Url.encode(ByteArray(32) { 3 })
    private val x = Base64Url.encode(ByteArray(32) { 4 })
    private val sig = "ab".repeat(35) // even-length hex

    private fun goodEvidence(
        rootSpkiDer: ByteArray? = goodRoot,
        challenge: ByteArray = nonce,
        bootState: VerifiedBootState = VerifiedBootState.VERIFIED,
        locked: Boolean = true,
        model: String? = "Pixel 7",
        level: AttestationSecurityLevel = AttestationSecurityLevel.STRONGBOX,
        leafIsEcP256: Boolean = true,
    ) = AttestationEvidence(rootSpkiDer, challenge, bootState, locked, model, level, leafSpki, leafIsEcP256)

    private val tier1Policy =
        AttestationPolicy(
            allowedRootSpkiDer = listOf(goodRoot),
            allowedModels = AttestationPolicy.PIXEL_7_MODELS,
            allowedSecurityLevels = setOf(AttestationSecurityLevel.STRONGBOX),
        )

    private class FakeParser(
        val evidence: AttestationEvidence?,
    ) : AttestationChainParser {
        var lastChain: List<String>? = null
            private set

        override fun parse(certChainBase64Der: List<String>): AttestationEvidence? {
            lastChain = certChainBase64Der
            return evidence
        }
    }

    private class CapturingSig(
        private val result: Boolean,
    ) : EcdsaP256BindingVerifier {
        var lastLeaf: ByteArray? = null
            private set
        var lastSigned: ByteArray? = null
            private set
        var lastDerSig: ByteArray? = null
            private set
        var calls = 0
            private set

        override fun verify(
            leafSpkiDer: ByteArray,
            signedBytes: ByteArray,
            derSignature: ByteArray,
        ): Boolean {
            calls += 1
            lastLeaf = leafSpkiDer
            lastSigned = signedBytes
            lastDerSig = derSignature
            return result
        }
    }

    private class CountingBurner : PairingNonceBurner {
        var burns = 0
            private set

        override fun burn() {
            burns += 1
        }
    }

    private fun post(
        v: Int = 1,
        chain: List<String> = listOf("Y2VydA"),
        bindingSig: String = sig,
    ): ValidatedPairingPost {
        val resp = ChildPairingResponse(v, ed, x, chain, bindingSig)
        val session = PairingSession(payloadJson = "{}", nonceBytes = nonce, createdAtMs = 0L, ttlMs = 1_000L)
        return ValidatedPairingPost(session, resp, ByteArray(32) { 3 }, ByteArray(32) { 4 })
    }

    private fun verifier(
        evidence: AttestationEvidence? = goodEvidence(),
        sigResult: Boolean = true,
        policy: AttestationPolicy = tier1Policy,
        burner: CountingBurner = CountingBurner(),
    ): Triple<Section73AttestationVerifier, CapturingSig, CountingBurner> {
        val sig = CapturingSig(sigResult)
        val v = Section73AttestationVerifier(FakeParser(evidence), sig, policy, burner)
        return Triple(v, sig, burner)
    }

    // ---- accept ---------------------------------------------------------------------------------

    @Test
    fun goodChainAcceptedNoBurn() {
        val (v, sigSeam, burner) = verifier()
        val result = v.verify(post())
        assertTrue(result is AttestationOutcome.Accepted)
        assertEquals(0, burner.burns, "accept must NOT burn the nonce")
        assertEquals(1, sigSeam.calls, "check 4b reached")
    }

    @Test
    fun check4bReceivesExactJcsBytesAndLeafKey() {
        val (v, sigSeam, _) = verifier()
        v.verify(post())
        val expected = ChildKeyBindingCanonical.bytes(1, ed, x, Base64Url.encode(nonce))
        assertTrue(sigSeam.lastSigned!!.contentEquals(expected), "4b signs JCS{v,ed,x,nonce} verbatim")
        assertTrue(sigSeam.lastLeaf!!.contentEquals(leafSpki), "4b verifies against the leaf K_bind")
    }

    // ---- refuse matrix (each burns) -------------------------------------------------------------

    private fun assertRefusedAndBurned(
        evidence: AttestationEvidence? = goodEvidence(),
        sigResult: Boolean = true,
        policy: AttestationPolicy = tier1Policy,
        bindingSig: String = sig,
    ) {
        val (v, _, burner) = verifier(evidence, sigResult, policy)
        val result = v.verify(post(bindingSig = bindingSig))
        assertTrue(result is AttestationOutcome.Refused, "expected refusal")
        assertEquals(1, burner.burns, "every attestation failure burns the nonce (ADR-036 D4)")
    }

    @Test fun chainParseFailureRefusesAndBurns() = assertRefusedAndBurned(evidence = null)

    @Test fun untrustedRootRefusesAndBurns() = assertRefusedAndBurned(evidence = goodEvidence(rootSpkiDer = byteArrayOf(9, 9)))

    @Test fun nullRootRefusesAndBurns() = assertRefusedAndBurned(evidence = goodEvidence(rootSpkiDer = null))

    @Test fun challengeMismatchRefusesAndBurns() = assertRefusedAndBurned(evidence = goodEvidence(challenge = ByteArray(32) { 1 }))

    @Test fun bootNotVerifiedRefusesAndBurns() = assertRefusedAndBurned(evidence = goodEvidence(bootState = VerifiedBootState.UNVERIFIED))

    @Test fun bootloaderUnlockedRefusesAndBurns() = assertRefusedAndBurned(evidence = goodEvidence(locked = false))

    @Test fun nullModelRefusesAndBurns() = assertRefusedAndBurned(evidence = goodEvidence(model = null))

    @Test fun nonAllowlistedModelRefusesAndBurns() = assertRefusedAndBurned(evidence = goodEvidence(model = "Pixel 6"))

    @Test fun nonStrongboxLevelRefusesAndBurns() =
        assertRefusedAndBurned(evidence = goodEvidence(level = AttestationSecurityLevel.TRUSTED_ENVIRONMENT))

    @Test fun softwareLevelRefusesAndBurns() = assertRefusedAndBurned(evidence = goodEvidence(level = AttestationSecurityLevel.SOFTWARE))

    @Test fun leafNotP256RefusesAndBurns() = assertRefusedAndBurned(evidence = goodEvidence(leafIsEcP256 = false))

    @Test fun bindingSigFalseRefusesAndBurns() = assertRefusedAndBurned(sigResult = false)

    @Test fun bindingSigBadHexRefusesAndBurns() = assertRefusedAndBurned(bindingSig = "abc") // odd-length hex

    @Test fun bindingSigNonHexRefusesAndBurns() = assertRefusedAndBurned(bindingSig = "zz") // non-hex nibble

    // ---- empty-SPKI sentinel hardening (ADR-037 D7, issue #120) ----------------------------------
    // A zero-length pinned root must never act as a wildcard: an empty pin matches no chain, and an
    // empty *parsed* root is refused even if the pin were empty (the contentEquals(empty,empty) trap).

    private val emptyPinPolicy =
        AttestationPolicy(
            allowedRootSpkiDer = listOf(ByteArray(0)),
            allowedModels = AttestationPolicy.PIXEL_7_MODELS,
            allowedSecurityLevels = setOf(AttestationSecurityLevel.STRONGBOX),
        )

    @Test
    fun emptyPinNeverMatchesARealChainRefusesAndBurns() =
        // AC1: a zero-length pin accepts nothing — not even otherwise-valid evidence.
        assertRefusedAndBurned(policy = emptyPinPolicy)

    @Test
    fun emptyParsedRootRefusesAndBurnsUnderTier1() =
        // AC2: an empty parsed top-SPKI is refused outright against the normal pinned policy.
        assertRefusedAndBurned(evidence = goodEvidence(rootSpkiDer = ByteArray(0)))

    @Test
    fun emptyParsedRootRefusesEvenAgainstEmptyPin() =
        // AC2 (coincidence): empty parsed root + empty pin must still refuse, never accept.
        assertRefusedAndBurned(evidence = goodEvidence(rootSpkiDer = ByteArray(0)), policy = emptyPinPolicy)

    @Test
    fun verifierRefusesEmptyParsedRootEvenIfPolicyWouldAccept() {
        // Isolates the *verifier-layer* guard (Section73AttestationVerifier `root.isEmpty()`): inject a
        // policy that accepts ANY root, so only the verifier's own empty-root refusal stands between an
        // empty parsed root and Accept. Deleting that line would let this reach Accepted ⇒ test goes red.
        // (The other empty-root tests are belt-and-suspenders: the policy layer also refuses, so they
        // would stay green if only the verifier line were reverted — this one would not.)
        val acceptAnyRoot =
            object : AttestationPolicy(
                allowedRootSpkiDer = listOf(goodRoot),
                allowedModels = AttestationPolicy.PIXEL_7_MODELS,
                allowedSecurityLevels = setOf(AttestationSecurityLevel.STRONGBOX),
            ) {
                override fun isAllowedRoot(rootSpkiDer: ByteArray): Boolean = true
            }
        assertRefusedAndBurned(evidence = goodEvidence(rootSpkiDer = ByteArray(0)), policy = acceptAnyRoot)
    }

    @Test
    fun policyDropsEmptyPinEntriesFailClosed() {
        // A pin list of only zero-length entries collapses to "no root pinned": nothing matches.
        assertFalse(emptyPinPolicy.isAllowedRoot(ByteArray(0)), "empty arg never matches an empty pin")
        assertFalse(emptyPinPolicy.isAllowedRoot(goodRoot), "a real root is not accepted by an empty pin")
        // tier1(emptyBytes) is equally fail-closed (the production wiring's empty-root path).
        assertFalse(AttestationPolicy.tier1(ByteArray(0)).isAllowedRoot(ByteArray(0)), "tier1 empty pin matches nothing")
        // A genuine pin still accepts its own root and rejects an empty probe.
        assertTrue(tier1Policy.isAllowedRoot(goodRoot), "real pin still matches its root")
        assertFalse(tier1Policy.isAllowedRoot(ByteArray(0)), "empty arg rejected even with a real pin")
    }

    // ---- first-failure ordering: a bad challenge short-circuits before check 4b -----------------

    @Test
    fun earlyCheckFailureSkipsSignatureSeam() {
        val (v, sigSeam, _) = verifier(evidence = goodEvidence(challenge = ByteArray(32) { 1 }))
        v.verify(post())
        assertEquals(0, sigSeam.calls, "check 2 failed ⇒ check 4b not reached")
    }

    // ---- the parser is handed the raw POSTed chain ----------------------------------------------

    @Test
    fun parserReceivesPostedCertChain() {
        val parser = FakeParser(goodEvidence())
        val v = Section73AttestationVerifier(parser, CapturingSig(true), tier1Policy, CountingBurner())
        v.verify(post(chain = listOf("certA", "certB")))
        assertEquals(listOf("certA", "certB"), parser.lastChain)
    }
}

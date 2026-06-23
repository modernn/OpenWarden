package com.openwarden.parent.pairing

import org.bouncycastle.asn1.ASN1Boolean
import org.bouncycastle.asn1.ASN1EncodableVector
import org.bouncycastle.asn1.ASN1Enumerated
import org.bouncycastle.asn1.ASN1Integer
import org.bouncycastle.asn1.DEROctetString
import org.bouncycastle.asn1.DERSequence
import org.bouncycastle.asn1.DERTaggedObject
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Real-crypto host tests (ADR-037 test plan, issue #96) — run on the host JVM via `androidUnitTest`
 * (the ADR-033 precedent), no device needed:
 *  - the §7.3 check-4b ECDSA-P-256 path against a real P-256 `K_bind` twin signer (proving parent↔
 *    child JCS byte-agreement and that the body actually covers each field — the highest drift risk);
 *  - the full [Section73AttestationVerifier] core driven through the **real** signature verifier;
 *  - the Bouncy-Castle KeyDescription ASN.1 extraction and the parser's fail-closed decode paths.
 */
class AttestationVerifierRealCryptoTest {
    private val nonce = ByteArray(32) { 9 }
    private val ed = Base64Url.encode(ByteArray(32) { 3 })
    private val x = Base64Url.encode(ByteArray(32) { 4 })

    private fun p256(): KeyPair = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()

    private fun signEcdsa(
        kp: KeyPair,
        body: ByteArray,
    ): ByteArray =
        Signature.getInstance("SHA256withECDSA").run {
            initSign(kp.private)
            update(body)
            sign()
        }

    private fun bindingBytes(
        edPub: String = ed,
        xPub: String = x,
        nonceBytes: ByteArray = nonce,
    ): ByteArray = ChildKeyBindingCanonical.bytes(1, edPub, xPub, Base64Url.encode(nonceBytes))

    // ---- check 4b: real ECDSA-P-256, twin signer ------------------------------------------------

    private val verifier = JdkEcdsaP256BindingVerifier()

    @Test
    fun validBindingSignatureAccepts() {
        val kp = p256()
        val body = bindingBytes()
        val sig = signEcdsa(kp, body)
        assertTrue(verifier.verify(kp.public.encoded, body, sig), "a genuine K_bind signature over JCS{v,ed,x,nonce} verifies")
    }

    @Test
    fun signatureByWrongKeyRejected() {
        val signer = p256()
        val attacker = p256()
        val body = bindingBytes()
        val sig = signEcdsa(signer, body)
        assertFalse(verifier.verify(attacker.public.encoded, body, sig), "a signature by a different P-256 key is rejected")
    }

    @Test
    fun substitutedEd25519PubRejected() {
        // Child signs over the real ed; attacker swaps it ⇒ parent re-derives a different body ⇒ fail.
        val kp = p256()
        val signed = signEcdsa(kp, bindingBytes(edPub = ed))
        val tampered = bindingBytes(edPub = Base64Url.encode(ByteArray(32) { 7 }))
        assertFalse(verifier.verify(kp.public.encoded, tampered, signed))
    }

    @Test
    fun substitutedX25519PubRejected() {
        val kp = p256()
        val signed = signEcdsa(kp, bindingBytes(xPub = x))
        val tampered = bindingBytes(xPub = Base64Url.encode(ByteArray(32) { 7 }))
        assertFalse(verifier.verify(kp.public.encoded, tampered, signed))
    }

    @Test
    fun staleNonceRejected() {
        val kp = p256()
        val signed = signEcdsa(kp, bindingBytes(nonceBytes = ByteArray(32) { 1 })) // a prior attempt's nonce
        val current = bindingBytes(nonceBytes = nonce)
        assertFalse(verifier.verify(kp.public.encoded, current, signed))
    }

    @Test
    fun malformedSignatureBytesRejected() {
        val kp = p256()
        assertFalse(verifier.verify(kp.public.encoded, bindingBytes(), byteArrayOf(1, 2, 3)))
    }

    @Test
    fun malformedLeafKeyRejected() {
        val kp = p256()
        val sig = signEcdsa(kp, bindingBytes())
        assertFalse(verifier.verify(byteArrayOf(1, 2, 3), bindingBytes(), sig))
    }

    // ---- full core through the REAL signature verifier ------------------------------------------

    private fun post(
        bindingSigHex: String,
        chain: List<String> = listOf("Y2VydA"),
    ): ValidatedPairingPost {
        val resp = ChildPairingResponse(1, ed, x, chain, bindingSigHex)
        val session = PairingSession(payloadJson = "{}", nonceBytes = nonce, createdAtMs = 0L, ttlMs = 1_000L)
        return ValidatedPairingPost(session, resp, ByteArray(32) { 3 }, ByteArray(32) { 4 })
    }

    private fun hex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it) }

    @Test
    fun fullCoreAcceptsRealSignatureAndRefusesTamperedOne() {
        val kp = p256()
        val root = byteArrayOf(0x52, 0x6f, 0x6f, 0x74)
        val evidence =
            AttestationEvidence(
                rootSpkiDer = root,
                attestationChallenge = nonce,
                verifiedBootState = VerifiedBootState.VERIFIED,
                bootloaderLocked = true,
                deviceModel = "Pixel 7",
                securityLevel = AttestationSecurityLevel.STRONGBOX,
                leafSpkiDer = kp.public.encoded, // the leaf K_bind the real verifier checks against
                leafIsEcP256 = true,
            )
        val parser =
            object : AttestationChainParser {
                override fun parse(certChainBase64Der: List<String>) = evidence
            }
        val policy = AttestationPolicy.tier1(root)

        var burns = 0
        val core = Section73AttestationVerifier(parser, verifier, policy, { burns += 1 })

        val goodSig = hex(signEcdsa(kp, bindingBytes()))
        assertTrue(core.verify(post(goodSig)) is AttestationOutcome.Accepted, "real ECDSA over the right body accepts")
        assertEquals(0, burns)

        // Same chain/evidence but a signature by an attacker key ⇒ check 4b fails ⇒ refuse + burn.
        val attackerSig = hex(signEcdsa(p256(), bindingBytes()))
        assertTrue(core.verify(post(attackerSig)) is AttestationOutcome.Refused)
        assertEquals(1, burns, "a failed binding signature burns the nonce (ADR-036 D4)")
    }

    // ---- ASN.1 KeyDescription extraction (bcprov-built synthetic extension) ----------------------

    /** Build the raw leaf extension value: DER OCTET STRING wrapping a KeyDescription SEQUENCE. */
    private fun keyDescriptionExtension(
        securityLevel: Int,
        challenge: ByteArray,
        verifiedBootState: Int,
        deviceLocked: Boolean,
        model: String?,
    ): ByteArray {
        val rootOfTrust =
            DERSequence(
                ASN1EncodableVector().apply {
                    add(DEROctetString(ByteArray(32))) // verifiedBootKey
                    add(ASN1Boolean.getInstance(deviceLocked))
                    add(ASN1Enumerated(verifiedBootState))
                    add(DEROctetString(ByteArray(32))) // verifiedBootHash
                },
            )
        val teeEnforced =
            ASN1EncodableVector().apply {
                add(DERTaggedObject(true, 704, rootOfTrust))
                if (model != null) add(DERTaggedObject(true, 714, DEROctetString(model.toByteArray())))
            }
        val keyDesc =
            DERSequence(
                ASN1EncodableVector().apply {
                    add(ASN1Integer(3)) // attestationVersion
                    add(ASN1Enumerated(securityLevel))
                    add(ASN1Integer(4)) // keymasterVersion
                    add(ASN1Enumerated(securityLevel)) // keymasterSecurityLevel
                    add(DEROctetString(challenge))
                    add(DEROctetString(ByteArray(0))) // uniqueId
                    add(DERSequence()) // softwareEnforced (empty)
                    add(DERSequence(teeEnforced)) // teeEnforced
                },
            )
        return DEROctetString(keyDesc.encoded).encoded
    }

    @Test
    fun extractsStrongboxVerifiedLockedPixel() {
        val raw =
            keyDescriptionExtension(
                securityLevel = 2, // StrongBox
                challenge = nonce,
                verifiedBootState = 0, // Verified
                deviceLocked = true,
                model = "Pixel 7",
            )
        val f = assertNotNull(BouncyCastleAttestationChainParser.extractKeyDescription(raw))
        assertEquals(AttestationSecurityLevel.STRONGBOX, f.securityLevel)
        assertTrue(f.challenge.contentEquals(nonce))
        assertEquals(VerifiedBootState.VERIFIED, f.verifiedBootState)
        assertTrue(f.deviceLocked)
        assertEquals("Pixel 7", f.deviceModel)
    }

    @Test
    fun extractsUnlockedUnverifiedSoftwareNoModel() {
        val raw =
            keyDescriptionExtension(
                securityLevel = 0, // Software
                challenge = nonce,
                verifiedBootState = 2, // Unverified
                deviceLocked = false,
                model = null,
            )
        val f = assertNotNull(BouncyCastleAttestationChainParser.extractKeyDescription(raw))
        assertEquals(AttestationSecurityLevel.SOFTWARE, f.securityLevel)
        assertEquals(VerifiedBootState.UNVERIFIED, f.verifiedBootState)
        assertFalse(f.deviceLocked)
        assertNull(f.deviceModel)
    }

    @Test
    fun garbageExtensionYieldsNull() {
        assertNull(BouncyCastleAttestationChainParser.extractKeyDescription(byteArrayOf(1, 2, 3)))
    }

    // ---- parser fail-closed decode paths --------------------------------------------------------

    private val parser = BouncyCastleAttestationChainParser()

    @Test fun emptyChainRejected() = assertNull(parser.parse(emptyList()))

    @Test fun nonBase64CertRejected() = assertNull(parser.parse(listOf("!!! not base64 !!!")))

    @Test
    fun validBase64NonCertRejected() {
        val notACert = Base64.getEncoder().encodeToString(byteArrayOf(1, 2, 3, 4))
        assertNull(parser.parse(listOf(notACert)))
    }
}

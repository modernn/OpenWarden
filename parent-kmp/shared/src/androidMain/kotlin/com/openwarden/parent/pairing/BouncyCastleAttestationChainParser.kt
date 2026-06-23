package com.openwarden.parent.pairing

import org.bouncycastle.asn1.ASN1Boolean
import org.bouncycastle.asn1.ASN1Enumerated
import org.bouncycastle.asn1.ASN1OctetString
import org.bouncycastle.asn1.ASN1Primitive
import org.bouncycastle.asn1.ASN1Sequence
import org.bouncycastle.asn1.ASN1TaggedObject
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.security.interfaces.ECPublicKey
import java.util.Base64

/**
 * Real §7.3 attestation-chain parser (ADR-037 D4). It decodes the `base64(DER)` §7.2 cert chain,
 * validates the chain's internal signatures (each cert signed by the one above it), and extracts the
 * Android Key Attestation extension (OID `1.3.6.1.4.1.11129.2.1.17`) from the leaf into the
 * policy-relevant [AttestationEvidence]. It makes **no** trust decision — the verifier pins the root
 * and judges every field (ADR-037 D1). Any decode / parse / chain-signature failure returns `null`
 * (fail-closed).
 *
 * Trust is by **policy pin of the top cert's SPKI** (verifier check 1), not by a self-signature, so
 * the internal-link check does not require the top cert to be self-signed. The ASN.1 extraction is a
 * separately-testable [extractKeyDescription]; the full real-Pixel chain round-trip is the deferred
 * ADR-032 bench-confirm follow-up (ADR-037 D5).
 */
class BouncyCastleAttestationChainParser : AttestationChainParser {
    override fun parse(certChainBase64Der: List<String>): AttestationEvidence? {
        if (certChainBase64Der.isEmpty()) return null
        return try {
            val factory = CertificateFactory.getInstance("X.509")
            val certs =
                certChainBase64Der.map { b64 ->
                    val der = Base64.getDecoder().decode(b64) // throws on non-base64 ⇒ caught ⇒ null
                    factory.generateCertificate(der.inputStream()) as X509Certificate
                }

            // Chain integrity: each cert must be signed by the next one up (the issuer). Trust in the
            // top anchor comes from the policy pin (check 1), so the top cert is not self-verified here.
            for (i in 0 until certs.size - 1) {
                certs[i].verify(certs[i + 1].publicKey) // throws on a broken link ⇒ caught ⇒ null
            }

            val leaf = certs.first()
            val top = certs.last()
            val extnRaw = leaf.getExtensionValue(KEY_ATTESTATION_OID) ?: return null
            val fields = extractKeyDescription(extnRaw) ?: return null

            AttestationEvidence(
                rootSpkiDer = top.publicKey.encoded,
                attestationChallenge = fields.challenge,
                verifiedBootState = fields.verifiedBootState,
                bootloaderLocked = fields.deviceLocked,
                deviceModel = fields.deviceModel,
                securityLevel = fields.securityLevel,
                leafSpkiDer = leaf.publicKey.encoded,
                leafIsEcP256 = isEcP256(leaf),
            )
        } catch (e: Exception) {
            null // any DER/cert/signature failure ⇒ fail-closed.
        }
    }

    private fun isEcP256(cert: X509Certificate): Boolean {
        val pub = cert.publicKey
        return pub is ECPublicKey && pub.params.curve.field.fieldSize == 256
    }

    companion object {
        /** Android Key Attestation extension OID. */
        const val KEY_ATTESTATION_OID = "1.3.6.1.4.1.11129.2.1.17"

        // KeyDescription SEQUENCE field indices (stable across attestation versions).
        private const val IDX_ATT_SECURITY_LEVEL = 1
        private const val IDX_ATTESTATION_CHALLENGE = 4
        private const val IDX_TEE_ENFORCED = 7

        // AuthorizationList context tags (keymaster tag numbers).
        private const val TAG_ROOT_OF_TRUST = 704
        private const val TAG_ATTESTATION_ID_MODEL = 714

        // RootOfTrust SEQUENCE field indices.
        private const val ROT_IDX_DEVICE_LOCKED = 1
        private const val ROT_IDX_VERIFIED_BOOT_STATE = 2

        /**
         * Extract the policy-relevant fields from the raw leaf extension value (the DER OCTET STRING
         * that wraps the KeyDescription SEQUENCE). Returns `null` on any structural surprise
         * (fail-closed). Separately testable with bcprov-built synthetic bytes (ADR-037 test plan) so
         * the risky ASN.1 path has coverage without a full X.509 chain.
         */
        internal fun extractKeyDescription(extensionRawValue: ByteArray): KeyDescriptionFields? =
            try {
                val keyDescBytes = (ASN1Primitive.fromByteArray(extensionRawValue) as ASN1OctetString).octets
                val keyDesc = ASN1Primitive.fromByteArray(keyDescBytes) as ASN1Sequence

                val securityLevel =
                    securityLevelOf(ASN1Enumerated.getInstance(keyDesc.getObjectAt(IDX_ATT_SECURITY_LEVEL)).value.toInt())
                val challenge = ASN1OctetString.getInstance(keyDesc.getObjectAt(IDX_ATTESTATION_CHALLENGE)).octets
                val teeEnforced = ASN1Sequence.getInstance(keyDesc.getObjectAt(IDX_TEE_ENFORCED))

                var verifiedBootState = VerifiedBootState.UNKNOWN
                var deviceLocked = false
                var deviceModel: String? = null

                for (element in teeEnforced) {
                    val tagged = element as? ASN1TaggedObject ?: continue
                    when (tagged.tagNo) {
                        TAG_ROOT_OF_TRUST -> {
                            val rot = ASN1Sequence.getInstance(tagged, true)
                            deviceLocked = ASN1Boolean.getInstance(rot.getObjectAt(ROT_IDX_DEVICE_LOCKED)).isTrue
                            verifiedBootState =
                                verifiedBootStateOf(
                                    ASN1Enumerated.getInstance(rot.getObjectAt(ROT_IDX_VERIFIED_BOOT_STATE)).value.toInt(),
                                )
                        }

                        TAG_ATTESTATION_ID_MODEL -> {
                            deviceModel = String(ASN1OctetString.getInstance(tagged, true).octets, Charsets.UTF_8)
                        }
                    }
                }

                KeyDescriptionFields(securityLevel, challenge, verifiedBootState, deviceLocked, deviceModel)
            } catch (e: Exception) {
                null
            }

        private fun securityLevelOf(v: Int): AttestationSecurityLevel =
            when (v) {
                0 -> AttestationSecurityLevel.SOFTWARE
                1 -> AttestationSecurityLevel.TRUSTED_ENVIRONMENT
                2 -> AttestationSecurityLevel.STRONGBOX
                else -> AttestationSecurityLevel.UNKNOWN
            }

        private fun verifiedBootStateOf(v: Int): VerifiedBootState =
            when (v) {
                0 -> VerifiedBootState.VERIFIED
                1 -> VerifiedBootState.SELF_SIGNED
                2 -> VerifiedBootState.UNVERIFIED
                3 -> VerifiedBootState.FAILED
                else -> VerifiedBootState.UNKNOWN
            }
    }
}

/** The fields [BouncyCastleAttestationChainParser.extractKeyDescription] pulls from the leaf extension. */
internal data class KeyDescriptionFields(
    val securityLevel: AttestationSecurityLevel,
    val challenge: ByteArray,
    val verifiedBootState: VerifiedBootState,
    val deviceLocked: Boolean,
    val deviceModel: String?,
)

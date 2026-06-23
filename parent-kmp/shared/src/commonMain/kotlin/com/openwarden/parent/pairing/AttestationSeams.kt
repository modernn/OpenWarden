package com.openwarden.parent.pairing

/**
 * Slice-(c) platform seam (ADR-037 D4): parse + internally chain-validate a §7.2 attestation cert
 * chain into the policy-relevant [AttestationEvidence], or `null` on **any** parse / chain-integrity
 * failure (fail-closed). The real `androidMain` impl is `BouncyCastleAttestationChainParser`
 * (JDK `CertificateFactory` + bcprov ASN.1 for the Android Key Attestation extension); host tests
 * inject a fake that returns crafted evidence so the verifier's decision matrix is deterministic.
 *
 * Implementations MUST NOT make any allow-list / trust decision — that is the verifier's job
 * (ADR-037 D1). They only decode, structurally validate, and extract.
 */
interface AttestationChainParser {
    /**
     * @param certChainBase64Der each cert as `base64(DER)` exactly as it arrived in the §7.2 POST
     *   (`child_attestation_cert_chain`); the impl decodes the standard-base64 itself.
     * @return extracted evidence, or `null` if any cert fails to decode/parse, the chain signatures
     *   do not validate, or the leaf has no Android Key Attestation extension.
     */
    fun parse(certChainBase64Der: List<String>): AttestationEvidence?
}

/**
 * Slice-(c) platform seam (ADR-037 D4): §7.3 check 4b — verify a DER-encoded ECDSA-P-256 / SHA-256
 * signature by the leaf (`K_bind`) key over the JCS binding bytes. The real `androidMain` impl is
 * `JdkEcdsaP256BindingVerifier` (`SHA256withECDSA`); host tests inject a programmable boolean, and
 * the real impl is exercised with a P-256 twin signer in `androidUnitTest`.
 *
 * MUST return `false` — never throw — on any failure (bad key, bad signature, parse error).
 * ECDSA `(r, n−s)` malleability is irrelevant: the signature is a one-shot accept/reject, never a
 * uniqueness or replay key (ADR-032 D2).
 */
interface EcdsaP256BindingVerifier {
    fun verify(
        leafSpkiDer: ByteArray,
        signedBytes: ByteArray,
        derSignature: ByteArray,
    ): Boolean
}

/**
 * Burns the single live pairing session (the single-use `provisioning_nonce`) on a failed
 * attestation — the ADR-036 D4 HARD criterion this slice discharges (ADR-037 D2). The Android
 * wiring supplies `{ sessionAccess.cancel() }`, called inside the `sessionLock`-serialized
 * `handle()` so the burn cannot race a concurrent POST.
 */
fun interface PairingNonceBurner {
    fun burn()
}

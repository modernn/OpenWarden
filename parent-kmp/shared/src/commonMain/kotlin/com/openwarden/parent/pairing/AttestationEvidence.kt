package com.openwarden.parent.pairing

/**
 * Verified-boot state from the Android Key Attestation `RootOfTrust.verifiedBootState`
 * (KeyMint enum). Only [VERIFIED] (GREEN) passes §7.3 check 3; everything else — including
 * [UNKNOWN], used by the parser when the field is absent/unparseable — fails closed.
 */
enum class VerifiedBootState { VERIFIED, SELF_SIGNED, UNVERIFIED, FAILED, UNKNOWN }

/**
 * Attestation `securityLevel` (KeyMint enum). Tier-1 (ADR-025 D7) accepts only [STRONGBOX];
 * ADR-029 Tier-2 would additionally accept [TRUSTED_ENVIRONMENT] (not wired here, ADR-037 D3).
 * [SOFTWARE] is never accepted; [UNKNOWN] is the parser's fail-closed default.
 */
enum class AttestationSecurityLevel { SOFTWARE, TRUSTED_ENVIRONMENT, STRONGBOX, UNKNOWN }

/**
 * The policy-relevant fields a parser ([AttestationChainParser]) extracts from a parsed,
 * internally-chain-validated §7.2 attestation cert chain (the leaf attests `K_bind`, ADR-032).
 *
 * Producing this is the platform seam's only job; **all trust decisions live in**
 * [Section73AttestationVerifier] (ADR-037 D1). Every field here is raw evidence the pure
 * verifier judges against an [AttestationPolicy] — the parser makes no allow-list decision.
 */
class AttestationEvidence(
    /**
     * SubjectPublicKeyInfo (DER) of the **top** cert in the chain — the trust anchor the chain
     * validated to. The verifier (check 1) pins this against [AttestationPolicy]'s allow-listed
     * roots. `null` only if the parser could not determine it (treated as untrusted ⇒ refuse).
     */
    val rootSpkiDer: ByteArray?,
    /** Leaf attestation challenge bytes — must equal the issued `provisioning_nonce` (check 2). */
    val attestationChallenge: ByteArray,
    /** `RootOfTrust.verifiedBootState` — must be [VerifiedBootState.VERIFIED] (check 3). */
    val verifiedBootState: VerifiedBootState,
    /** `RootOfTrust.deviceLocked` — must be `true` (check 3). */
    val bootloaderLocked: Boolean,
    /** `attestationIdModel` (tag 714) string, or `null` if absent — allow-listed in check 3. */
    val deviceModel: String?,
    /** Attestation `securityLevel` — must be an allow-listed level (check 3). */
    val securityLevel: AttestationSecurityLevel,
    /** SubjectPublicKeyInfo (DER) of the **leaf** cert = `K_bind`; verifies `child_binding_sig` (check 4b). */
    val leafSpkiDer: ByteArray,
    /** `true` iff the leaf public key is a well-formed EC P-256 (secp256r1) key (check 4). */
    val leafIsEcP256: Boolean,
)

/**
 * The §7.3 trust allow-lists, injected into [Section73AttestationVerifier] (ADR-037 D3). It is
 * **data, not code** so the Tier-2 widening (ADR-029) is a different policy value + root pins,
 * never a verifier rewrite. Anything not in an allow-set is refused (fail-closed by construction).
 */
class AttestationPolicy(
    allowedRootSpkiDer: List<ByteArray>,
    private val allowedModels: Set<String>,
    private val allowedSecurityLevels: Set<AttestationSecurityLevel>,
) {
    // Defensive copies so a caller cannot mutate the pinned anchors after construction.
    private val allowedRoots: List<ByteArray> = allowedRootSpkiDer.map { it.copyOf() }

    /** Check 1: the chain's top SPKI is byte-identical to a pinned root anchor. */
    fun isAllowedRoot(rootSpkiDer: ByteArray): Boolean = allowedRoots.any { it.contentEquals(rootSpkiDer) }

    /** Check 3: the attested device model is allow-listed. */
    fun isAllowedModel(model: String): Boolean = model in allowedModels

    /** Check 3: the attestation security level is allow-listed. */
    fun isAllowedSecurityLevel(level: AttestationSecurityLevel): Boolean = level in allowedSecurityLevels

    companion object {
        /** The Pixel-7 model strings the v0.x tier accepts (ADR-001/023, ADR-025 D7). */
        val PIXEL_7_MODELS: Set<String> = setOf("Pixel 7", "Pixel 7 Pro", "Pixel 7a")

        /**
         * Tier-1 (v0.x Pixel-class): the caller-supplied **pinned Google Hardware Attestation
         * root** SPKI, `STRONGBOX` only, Pixel-7 models (ADR-037 D3). The real Google root SPKI
         * is supplied at the wiring site and rides the ADR-032 bench-confirm capture; an empty
         * root list fails closed (no allow-listed root ⇒ every chain refused).
         */
        fun tier1(googleRootSpkiDer: ByteArray): AttestationPolicy =
            AttestationPolicy(
                allowedRootSpkiDer = listOf(googleRootSpkiDer),
                allowedModels = PIXEL_7_MODELS,
                allowedSecurityLevels = setOf(AttestationSecurityLevel.STRONGBOX),
            )
    }
}

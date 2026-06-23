package com.openwarden.parent.pairing

/**
 * The §7.3 parent attestation verifier (slice c; ADR-025 D5c, ADR-032 D4, ADR-037). It implements the
 * slice-(b) [AttestationVerifier] seam: given the shape-validated, session-bound [ValidatedPairingPost]
 * that the endpoint handed off, it runs §7.3 checks 1–4 + 4b and decides whether the pair may proceed
 * toward the §7.4 SAS. It derives no SAS and pins nothing — `Accepted` means **only** "attestation
 * passed."
 *
 * Pure decision core (ADR-037 D1): all ASN.1 / X.509 / signature math is delegated to the
 * [AttestationChainParser] and [EcdsaP256BindingVerifier] seams, so the whole 1–4b matrix is
 * host-deterministic. Every check is fail-closed; the first failure refuses the pair.
 *
 * **Burn-on-failure (ADR-036 D4 HARD criterion, ADR-037 D2):** on *every* refusal the verifier calls
 * [burner] to burn the single-use nonce, forcing a fresh QR — and never burns on accept. In the
 * Android wiring [burner] is `{ sessionAccess.cancel() }`, run inside the same `sessionLock`-serialized
 * `handle()` as the endpoint, so the burn cannot race a concurrent POST.
 */
class Section73AttestationVerifier(
    private val parser: AttestationChainParser,
    private val sigVerifier: EcdsaP256BindingVerifier,
    private val policy: AttestationPolicy,
    private val burner: PairingNonceBurner,
) : AttestationVerifier {
    override fun verify(post: ValidatedPairingPost): AttestationOutcome {
        val outcome = evaluate(post)
        if (outcome is AttestationOutcome.Refused) {
            burner.burn() // single-use nonce: any attestation failure burns it (no reuse on retry).
        }
        return outcome
    }

    private fun evaluate(post: ValidatedPairingPost): AttestationOutcome {
        val nonce = post.session.nonce()

        // Check 1 (structural): parse + internally chain-validate. null = any parse/chain-integrity failure.
        val ev =
            parser.parse(post.response.childAttestationCertChain)
                ?: return refuse("chain-parse")

        // Check 1 (policy): the chain roots in an allow-listed attestation root.
        val root = ev.rootSpkiDer ?: return refuse("untrusted-root")
        if (!policy.isAllowedRoot(root)) return refuse("untrusted-root")

        // Check 2: leaf attestation challenge == the issued provisioning_nonce.
        if (!ev.attestationChallenge.contentEquals(nonce)) return refuse("challenge-mismatch")

        // Check 3: VERIFIED boot (GREEN) + locked bootloader + allow-listed model + allowed level.
        if (ev.verifiedBootState != VerifiedBootState.VERIFIED) return refuse("boot-not-verified")
        if (!ev.bootloaderLocked) return refuse("bootloader-unlocked")
        val model = ev.deviceModel ?: return refuse("model-not-allowed")
        if (!policy.isAllowedModel(model)) return refuse("model-not-allowed")
        if (!policy.isAllowedSecurityLevel(ev.securityLevel)) return refuse("security-level")

        // Check 4: the leaf (= K_bind, ADR-032 D6 — no separate field) is a well-formed EC P-256 key.
        if (!ev.leafIsEcP256) return refuse("leaf-not-p256")

        // Check 4b: child_binding_sig (ECDSA-P-256 by the leaf K_bind) over JCS{v,ed,x,nonce}.
        val signingInput =
            ChildKeyBindingCanonical.bytes(
                v = post.response.v,
                childEd25519Pub = post.response.childEd25519Pub,
                childX25519Pub = post.response.childX25519Pub,
                provisioningNonceB64Url = Base64Url.encode(nonce),
            )
        val sigDer = hexToBytes(post.response.childBindingSig) ?: return refuse("binding-sig-encoding")
        if (!sigVerifier.verify(ev.leafSpkiDer, signingInput, sigDer)) return refuse("binding-sig")

        return AttestationOutcome.Accepted
    }

    // The reason is internal (logs/tests); the transport collapses every refusal to a generic status
    // so the endpoint is not a probing oracle for which check failed (ADR-036 D3).
    private fun refuse(reason: String): AttestationOutcome.Refused = AttestationOutcome.Refused("attestation: $reason")

    /** Decode an even-length hex string to bytes; `null` on odd length or any non-hex nibble (fail-closed). */
    private fun hexToBytes(s: String): ByteArray? {
        if (s.isEmpty() || s.length % 2 != 0) return null
        val out = ByteArray(s.length / 2)
        var i = 0
        while (i < s.length) {
            val hi = nibble(s[i])
            val lo = nibble(s[i + 1])
            if (hi < 0 || lo < 0) return null
            out[i / 2] = ((hi shl 4) or lo).toByte()
            i += 2
        }
        return out
    }

    private fun nibble(c: Char): Int =
        when (c) {
            in '0'..'9' -> c - '0'
            in 'a'..'f' -> c - 'a' + 10
            in 'A'..'F' -> c - 'A' + 10
            else -> -1
        }
}

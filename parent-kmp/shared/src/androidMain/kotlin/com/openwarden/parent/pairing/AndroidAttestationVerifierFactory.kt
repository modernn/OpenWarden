package com.openwarden.parent.pairing

/**
 * Assembles the real §7.3 [Section73AttestationVerifier] from the Android crypto seams (ADR-037 D4),
 * so the slice-(e) pairing coordinator wires it into the slice-(b) `PairingServer` with one call —
 * this slice does not modify the server itself.
 *
 * The [burner] MUST burn the same session the endpoint serves, under the same `sessionLock`
 * (ADR-036 D5) — typically `PairingNonceBurner { synchronized(sessionLock) { sessionManager.cancel() } }`
 * or simply the endpoint's `SessionAccess::cancel`. The [googleRootSpkiDer] is the pinned Tier-1
 * trust anchor (ADR-037 D3); it rides the ADR-032 bench-confirm capture and an empty/wrong pin fails
 * closed (no allow-listed root ⇒ refuse).
 */
object AndroidAttestationVerifierFactory {
    fun create(
        googleRootSpkiDer: ByteArray,
        burner: PairingNonceBurner,
    ): Section73AttestationVerifier =
        Section73AttestationVerifier(
            parser = BouncyCastleAttestationChainParser(),
            sigVerifier = JdkEcdsaP256BindingVerifier(),
            policy = AttestationPolicy.tier1(googleRootSpkiDer),
            burner = burner,
        )
}

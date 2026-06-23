package com.openwarden.child

import kotlinx.serialization.Serializable

/**
 * The child's pairing key-binding (ADR-032 D2/D3) — the StrongBox **device-binding key** `K_bind`
 * (EC P-256) signs over the child's **TEE-resident** Ed25519 identity + X25519 encryption public
 * keys, plus the parent's single-use `provisioning_nonce`. This is what cryptographically ties the
 * pinned Curve25519 keys to genuine, hardware-attested Pixel hardware: StrongBox cannot *hold*
 * Curve25519 (only EC P-256/RSA/AES/HMAC), so the identity keys live in the TEE and are **bound to**
 * the StrongBox key by this signature instead of residing in it (ADR-032; resolves the
 * StrongBox/Curve25519 incompatibility that made PROTOCOL §7.2 unsatisfiable).
 *
 * It rides in the §7.2 child POST as `child_binding_sig` alongside `child_attestation_cert_chain`
 * (the chain for `K_bind`). The parent verifies `K_bind`'s STRONGBOX attestation, then this binding
 * signature (PROTOCOL §7.3 check 4b), then pins `child_ed25519_pub`/`child_x25519_pub`.
 *
 * Signed by **`K_bind` (ECDSA-P-256 / SHA-256)** over the RFC 8785 JCS canonical bytes of the object
 * minus `sig` — the same canonicalize-then-sign rule as [SpkiAssertion]/[SignedBundle]/[SignedCommand]
 * (ADR-015/019), but a **different key and a different algorithm** (P-256 ECDSA, not the child/parent
 * Ed25519), so it is domain-separated from every other signed wire object on two independent axes
 * (ADR-032 D2). `sig` is hex(DER ECDSA), matching the [SpkiAssertion] hex precedent (ADR-031).
 *
 * Wire keys are the property names (snake_case). The three byte fields are base64url(no-pad) of the
 * raw 32-byte values. Defaults exist only so a partial object still parses; an empty/short field can
 * never verify (fail-closed) — see [ChildKeyBindingVerifier.verify].
 */
@Serializable
data class ChildKeyBinding(
    val v: Int,
    val child_ed25519_pub: String = "",
    val child_x25519_pub: String = "",
    val provisioning_nonce: String = "",
    val sig: String = "",
)

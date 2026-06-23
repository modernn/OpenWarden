package com.openwarden.child

/**
 * Seam over the child's hardware-backed key material (ADR-032). Splitting this behind an interface
 * keeps the pairing/binding logic deterministically testable on the JVM (via a fake) while the real
 * implementation ([KeystoreChildKeys]) talks to the Android Keystore / StrongBox — which exists on
 * neither the JVM nor the emulator, so it is exercised only on a bench Pixel 7 (ADR-032 bench gate).
 *
 * Three keys, two homes (ADR-032 D1):
 *  - **`K_bind`** — EC P-256 in **StrongBox**, attestation-challenged with the `provisioning_nonce`.
 *    The only hardware-attestable key; signs the [ChildKeyBinding].
 *  - **`K_id`** — Ed25519 in the **TEE**. The long-term identity the parent pins; backs
 *    [IdentityKeyProvider] (so #21's [SpkiBindingSigner] works once provisioned).
 *  - **`K_enc`** — X25519 in the **TEE**. The sealed-box audience.
 *
 * Every accessor returns `null` (or [isProvisioned] `false`) before [provision] runs — fail-closed:
 * the child vouches for nothing it has no key for. Private keys never leave the keystore; only public
 * keys, signatures, and the attestation chain are exposed.
 */
interface ChildKeyStore {

    /** True once [provision] has generated the keys (survives process restart for the real store). */
    fun isProvisioned(): Boolean

    /**
     * Generate `K_bind` (StrongBox P-256, `setAttestationChallenge([nonce])`), `K_id` (TEE Ed25519),
     * and `K_enc` (TEE X25519), all `setUnlockedDeviceRequired(true)` and **no** per-use
     * `setUserAuthenticationRequired` (the keys sign autonomously server-side; a per-use biometric
     * gate would deadlock — ADR-032 key-use-auth decision).
     *
     * **Not idempotent — each call generates a fresh key set bound to [nonce]:** it deletes any prior
     * set first (re-pair regenerates; child keys are non-recoverable, CRYPTO §1) so the new attestation
     * challenge takes effect, and on any keygen failure leaves nothing half-provisioned (fail-closed,
     * ADR-032 D7). A redundant call therefore **rotates** the keys and invalidates an existing pin —
     * call it once per pairing attempt, not as a guard.
     */
    fun provision(nonce: ByteArray)

    /** `K_id` Ed25519 public key, 32 raw bytes, or `null` before provisioning. */
    fun identityPublicKey(): ByteArray?

    /** Ed25519-sign [message] with `K_id` (raw 64-byte signature), or `null` before provisioning. */
    fun signIdentity(message: ByteArray): ByteArray?

    /** `K_enc` X25519 public key, 32 raw bytes, or `null` before provisioning. */
    fun encryptionPublicKey(): ByteArray?

    /** `K_bind` P-256 public key as X.509 SubjectPublicKeyInfo (DER), or `null` before provisioning. */
    fun bindingPublicKey(): ByteArray?

    /** ECDSA-P-256-sign [message] with `K_bind` (DER signature), or `null` before provisioning. */
    fun signBinding(message: ByteArray): ByteArray?

    /** `K_bind` attestation certificate chain (each cert DER-encoded), or `null` before provisioning. */
    fun attestationChain(): List<ByteArray>?
}

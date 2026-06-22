package com.openwarden.child

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.Signature
import java.security.spec.ECGenParameterSpec

/**
 * The real [ChildKeyStore] (issue #22, ADR-032): generates and holds the child's three pairing keys in
 * the Android Keystore — `K_bind` in **StrongBox**, `K_id`/`K_enc` in the **TEE** — implementing the
 * corrected CRYPTO §3 recipe.
 *
 * **Bench-gated, untested here (ADR-032 gate).** StrongBox exists on neither the JVM nor the emulator,
 * so this class is validated only on a bench Pixel 7; the deterministic test coverage for the pairing
 * logic runs against a fake [ChildKeyStore]. The Android raw-public-key extraction below (slice the
 * trailing 32 bytes of the Curve25519 SubjectPublicKeyInfo) follows the standard RFC 8410 SPKI shape
 * (`30 2a 30 05 06 03 2b 65 {70|6e} 03 21 00 ‖ key`) and MUST be confirmed on-device per the gate.
 *
 * Fail-closed:
 *  - [provision] does **not** catch a `StrongBoxUnavailableException` for `K_bind` — on the Tier-1
 *    Pixel path we never fall back to TEE for the attestation key (ADR-032 D7); the exception aborts
 *    pairing. (Tier-2 TEE fallback per ADR-029 is deferred — see ADR-032 D1.)
 *  - every accessor returns `null` on any error or before provisioning.
 */
class KeystoreChildKeys : ChildKeyStore {

    private val ks: KeyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }

    override fun isProvisioned(): Boolean = try {
        ks.containsAlias(ALIAS_BIND) && ks.containsAlias(ALIAS_ID) && ks.containsAlias(ALIAS_ENC)
    } catch (e: Exception) {
        false
    }

    override fun provision(nonce: ByteArray) {
        if (isProvisioned()) return // idempotent

        // 1. K_bind — EC P-256 in StrongBox: the only hardware-attestable key (Curve25519 cannot be).
        KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, ANDROID_KEYSTORE).apply {
            initialize(
                KeyGenParameterSpec.Builder(ALIAS_BIND, KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY)
                    .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
                    .setDigests(KeyProperties.DIGEST_SHA256)
                    .setIsStrongBoxBacked(true) // non-negotiable for K_bind; throws if no StrongBox (no TEE fallback on Tier 1)
                    .setUnlockedDeviceRequired(true) // NO setUserAuthenticationRequired — keys sign autonomously (ADR-032)
                    .setAttestationChallenge(nonce) // -> STRONGBOX attestation cert chain bound to this pairing
                    .build(),
            )
            generateKeyPair()
        }

        // 2. K_id — Ed25519 in the TEE (StrongBox cannot hold Curve25519). The pinned identity key.
        // NB: AndroidKeyStore Curve25519 keygen uses the algorithm STRING ("Ed25519"/"XDH"); there is
        // no KeyProperties.KEY_ALGORITHM_ED25519/_XDH constant (unlike EC/RSA/AES). CRYPTO §3's sample
        // shows the constants — a doc inaccuracy to reconcile; the runnable form is the string.
        KeyPairGenerator.getInstance(ALG_ED25519, ANDROID_KEYSTORE).apply {
            initialize(
                KeyGenParameterSpec.Builder(ALIAS_ID, KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY)
                    .setUnlockedDeviceRequired(true)
                    .build(),
            )
            generateKeyPair()
        }

        // 3. K_enc — X25519 in the TEE. The sealed-box audience.
        KeyPairGenerator.getInstance(ALG_XDH, ANDROID_KEYSTORE).apply {
            initialize(
                KeyGenParameterSpec.Builder(ALIAS_ENC, KeyProperties.PURPOSE_AGREE_KEY)
                    .setUnlockedDeviceRequired(true)
                    .build(),
            )
            generateKeyPair()
        }
    }

    override fun identityPublicKey(): ByteArray? = rawCurve25519PublicKey(ALIAS_ID)

    override fun signIdentity(message: ByteArray): ByteArray? = try {
        val priv = ks.getKey(ALIAS_ID, null) as? PrivateKey ?: return null
        Signature.getInstance(SIG_ED25519).run {
            initSign(priv)
            update(message)
            sign()
        }
    } catch (e: Exception) {
        null
    }

    override fun encryptionPublicKey(): ByteArray? = rawCurve25519PublicKey(ALIAS_ENC)

    override fun bindingPublicKey(): ByteArray? = try {
        // Full X.509 SubjectPublicKeyInfo (DER) of the P-256 key — what P256.verify / the parent consume.
        ks.getCertificate(ALIAS_BIND)?.publicKey?.encoded
    } catch (e: Exception) {
        null
    }

    override fun signBinding(message: ByteArray): ByteArray? = try {
        val priv = ks.getKey(ALIAS_BIND, null) as? PrivateKey ?: return null
        Signature.getInstance(SIG_ECDSA_P256).run {
            initSign(priv)
            update(message)
            sign() // DER-encoded ECDSA signature
        }
    } catch (e: Exception) {
        null
    }

    override fun attestationChain(): List<ByteArray>? = try {
        ks.getCertificateChain(ALIAS_BIND)?.map { it.encoded }?.takeIf { it.isNotEmpty() }
    } catch (e: Exception) {
        null
    }

    /**
     * Extract the raw 32-byte key from a Curve25519 (Ed25519 / X25519) AndroidKeyStore public key. Its
     * `.encoded` is the X.509 SPKI; for these curves the key bit-string is the trailing 32 bytes
     * (RFC 8410). Returns `null` on any malformed/short encoding (fail-closed). Bench-verify per gate.
     */
    private fun rawCurve25519PublicKey(alias: String): ByteArray? = try {
        val spki = ks.getCertificate(alias)?.publicKey?.encoded ?: return null
        if (spki.size < 32) null else spki.copyOfRange(spki.size - 32, spki.size)
    } catch (e: Exception) {
        null
    }

    private companion object {
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val ALIAS_BIND = "openwarden_child_binding_p256"
        const val ALIAS_ID = "openwarden_child_ed25519"
        const val ALIAS_ENC = "openwarden_child_x25519"
        const val ALG_ED25519 = "Ed25519"
        const val ALG_XDH = "XDH"
        const val SIG_ED25519 = "Ed25519"
        const val SIG_ECDSA_P256 = "SHA256withECDSA"
    }
}

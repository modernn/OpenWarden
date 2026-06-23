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
 * logic runs against a fake [ChildKeyStore]. The Curve25519 raw-public-key extraction asserts the
 * RFC 8410 SubjectPublicKeyInfo prefix (`30 2a 30 05 06 03 2b 65 {70|6e} 03 21 00 ‖ 32-byte key`)
 * before slicing, so a deviating provider encoding is rejected rather than silently mis-extracted; the
 * exact bytes + StrongBox/string-algorithm resolution MUST still be confirmed on-device per the gate.
 *
 * Thread-safety: `java.security.KeyStore` is not thread-safe, so every method is `@Synchronized` on
 * this instance (matching the `@Synchronized` precedent on the #21 mDNS advertiser).
 *
 * Fail-closed:
 *  - [provision] is **atomic with cleanup**: it deletes any prior key set first (re-pair regenerates —
 *    child keys are non-recoverable, CRYPTO §1) and, on **any** keygen failure, deletes all three
 *    aliases before rethrowing, so nothing half-provisioned survives (ADR-032 D7 "nothing half-pinned").
 *    `K_bind` is StrongBox-only on Tier 1 — a `StrongBoxUnavailableException` aborts pairing (no TEE
 *    fallback; ADR-032 D7). (Tier-2 TEE fallback per ADR-029 is deferred — ADR-032 D1.)
 *  - every accessor returns `null` on any error or before provisioning.
 */
class KeystoreChildKeys : ChildKeyStore {

    private val ks: KeyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }

    @Synchronized
    override fun isProvisioned(): Boolean = try {
        ks.containsAlias(ALIAS_BIND) && ks.containsAlias(ALIAS_ID) && ks.containsAlias(ALIAS_ENC)
    } catch (e: Exception) {
        false
    }

    @Synchronized
    override fun provision(nonce: ByteArray) {
        // Each pairing attempt generates a fresh key set bound to its single-use nonce; clear any prior
        // set first (re-pair regenerates) so re-provision uses the new nonce and no stale key survives.
        deleteAll()
        try {
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
        } catch (e: Exception) {
            deleteAll() // fail-closed: leave nothing half-provisioned (ADR-032 D7)
            throw e
        }
    }

    @Synchronized
    override fun identityPublicKey(): ByteArray? = rawCurve25519PublicKey(ALIAS_ID, ED25519_SPKI_PREFIX)

    @Synchronized
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

    @Synchronized
    override fun encryptionPublicKey(): ByteArray? = rawCurve25519PublicKey(ALIAS_ENC, X25519_SPKI_PREFIX)

    @Synchronized
    override fun bindingPublicKey(): ByteArray? = try {
        // Full X.509 SubjectPublicKeyInfo (DER) of the P-256 key — what P256.verify / the parent consume.
        ks.getCertificate(ALIAS_BIND)?.publicKey?.encoded
    } catch (e: Exception) {
        null
    }

    @Synchronized
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

    @Synchronized
    override fun attestationChain(): List<ByteArray>? = try {
        ks.getCertificateChain(ALIAS_BIND)?.map { it.encoded }?.takeIf { it.isNotEmpty() }
    } catch (e: Exception) {
        null
    }

    /** Best-effort delete of all three aliases; swallows per-alias errors (reads stay fail-closed). */
    private fun deleteAll() {
        for (alias in listOf(ALIAS_BIND, ALIAS_ID, ALIAS_ENC)) {
            try {
                if (ks.containsAlias(alias)) ks.deleteEntry(alias)
            } catch (e: Exception) {
                // ignore — a surviving alias still fails isProvisioned()/the per-key accessors
            }
        }
    }

    /**
     * Extract the raw 32-byte key from a Curve25519 (Ed25519 / X25519) AndroidKeyStore public key. Its
     * `.encoded` is the X.509 SPKI; for these curves it is the fixed 12-byte RFC 8410 prefix
     * ([expectedPrefix], which pins the curve OID) followed by the 32-byte key. Rejects (returns `null`,
     * fail-closed) any encoding whose length or prefix does not match — so a deviating provider
     * encoding or an alias↔curve mismatch is caught, not silently mis-sliced. Bench-verify per gate.
     */
    private fun rawCurve25519PublicKey(alias: String, expectedPrefix: ByteArray): ByteArray? = try {
        val spki = ks.getCertificate(alias)?.publicKey?.encoded
        when {
            spki == null -> null
            spki.size != expectedPrefix.size + 32 -> null
            !spki.copyOfRange(0, expectedPrefix.size).contentEquals(expectedPrefix) -> null
            else -> spki.copyOfRange(spki.size - 32, spki.size)
        }
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

        // RFC 8410 SubjectPublicKeyInfo prefixes (12 bytes): SEQ, SEQ, OID 1.3.101.{112 Ed25519 | 110 X25519},
        // BIT STRING(33){0x00 ‖ 32-byte key}. The OID byte (0x70 / 0x6e) pins the curve.
        val ED25519_SPKI_PREFIX = byteArrayOf(
            0x30, 0x2a, 0x30, 0x05, 0x06, 0x03, 0x2b, 0x65, 0x70, 0x03, 0x21, 0x00,
        )
        val X25519_SPKI_PREFIX = byteArrayOf(
            0x30, 0x2a, 0x30, 0x05, 0x06, 0x03, 0x2b, 0x65, 0x6e, 0x03, 0x21, 0x00,
        )
    }
}

package com.openwarden.parent.crypto

import com.openwarden.parent.crypto.bip39.Bip39
import org.bouncycastle.crypto.digests.SHA256Digest
import org.bouncycastle.crypto.generators.Argon2BytesGenerator
import org.bouncycastle.crypto.generators.HKDFBytesGenerator
import org.bouncycastle.crypto.params.Argon2Parameters
import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters
import org.bouncycastle.crypto.params.HKDFParameters
import org.bouncycastle.crypto.params.X25519PrivateKeyParameters
import java.security.SecureRandom

/**
 * Parent root-key derivation (ADR-033 D1/D3): BIP-39 mnemonic → Argon2id seed → HKDF-SHA256 →
 * Ed25519 + X25519 key pairs. Entirely Bouncy Castle, so it runs (and its vector is ratified)
 * host-side with no device and no libsodium native lib. CRYPTO.md §2 is the spec; the parameters
 * here are canonical (ADR-033 D2).
 */
object RootKeyDerivation {
    private const val ARGON2_MEMORY_KB = 256 * 1024 // 256 MiB
    private const val ARGON2_ITERATIONS = 4
    private const val ARGON2_PARALLELISM = 2
    private const val SEED_BYTES = 64
    private const val SALT_BYTES = 16

    private val ARGON2_SALT_SOURCE = "openwarden-bip39-v1".encodeToByteArray()
    private val HKDF_EXTRACT_SALT = "openwarden-v1".encodeToByteArray()
    private val ED25519_INFO = "openwarden-parent-ed25519-v1".encodeToByteArray()
    private val X25519_INFO = "openwarden-parent-x25519-v1".encodeToByteArray()

    /** Fresh 256-bit entropy → 24-word mnemonic (RECOVERY §2: the platform CSPRNG). */
    fun generateMnemonic(random: SecureRandom = SecureRandom()): List<String> {
        val entropy = ByteArray(Bip39.ENTROPY_BYTES).also(random::nextBytes)
        return Bip39.encode(entropy)
    }

    /**
     * Argon2id seed (64 bytes) for [mnemonic] (ADR-033 D1). Validates the phrase first (fail-closed:
     * an invalid checksum throws rather than deriving a key from a typo'd phrase).
     */
    fun deriveSeed(mnemonic: List<String>): ByteArray {
        require(Bip39.isValid(mnemonic)) { "invalid BIP-39 mnemonic" }
        val salt = openwardenSha256(ARGON2_SALT_SOURCE).copyOf(SALT_BYTES)
        // NFKD == identity on the all-ASCII BIP-39 English wordlist (ADR-033 D1 note).
        val password = mnemonic.joinToString(" ").encodeToByteArray()
        val params =
            Argon2Parameters
                .Builder(Argon2Parameters.ARGON2_id)
                // Version 0x13 (v19) is LOAD-BEARING and must stay explicit: it matches libsodium's
                // only argon2 version; v0x10 would silently produce different keys (breaks recoverability).
                .withVersion(Argon2Parameters.ARGON2_VERSION_13)
                .withMemoryAsKB(ARGON2_MEMORY_KB)
                .withIterations(ARGON2_ITERATIONS)
                .withParallelism(ARGON2_PARALLELISM)
                .withSalt(salt)
                .build()
        val generator = Argon2BytesGenerator().apply { init(params) }
        val seed = ByteArray(SEED_BYTES)
        generator.generateBytes(password, seed)
        return seed
    }

    /** Full pipeline: [mnemonic] → [RootKeys] (ADR-033 D1). Deterministic; same phrase ⇒ same keys. */
    fun deriveRootKeys(mnemonic: List<String>): RootKeys {
        val seed = deriveSeed(mnemonic)
        val ed25519Priv = hkdfExpand(seed, ED25519_INFO, 32)
        val x25519Priv = hkdfExpand(seed, X25519_INFO, 32)
        // Ed25519 priv = the 32-byte RFC 8032 seed (the form both BC and libsodium accept).
        val ed25519Pub = Ed25519PrivateKeyParameters(ed25519Priv, 0).generatePublicKey().encoded
        // x25519Priv is the RAW (unclamped) HKDF output; X25519 clamps the scalar at use (RFC 7748),
        // exactly as libsodium's crypto_scalarmult_base does — so the public key is identical. Every
        // future consumer of x25519Priv MUST clamp (see CRYPTO.md §2 X25519 clamp-at-use invariant).
        val x25519Pub = X25519PrivateKeyParameters(x25519Priv, 0).generatePublicKey().encoded
        return RootKeys(
            ed25519Seed = ed25519Priv,
            ed25519Public = ed25519Pub,
            x25519Private = x25519Priv,
            x25519Public = x25519Pub,
        )
    }

    /**
     * HKDF-SHA256 (RFC 5869): Extract(salt = "openwarden-v1", ikm) then Expand(info) → [length].
     *
     * NOTE: Bouncy Castle's `HKDFParameters` constructor is `(ikm, salt, info)` — IKM first, NOT
     * `(salt, ikm, info)`. With a non-null salt `HKDFBytesGenerator` runs Extract-then-Expand in one
     * pass. Called once per info label; each call re-derives the SAME PRK from the same (ikm, salt),
     * so the two keys share the single PRK that CRYPTO.md §2 publishes (no correctness difference).
     */
    private fun hkdfExpand(
        ikm: ByteArray,
        info: ByteArray,
        length: Int,
    ): ByteArray {
        val generator = HKDFBytesGenerator(SHA256Digest())
        generator.init(HKDFParameters(ikm, HKDF_EXTRACT_SALT, info))
        val out = ByteArray(length)
        generator.generateBytes(out, 0, length)
        return out
    }
}

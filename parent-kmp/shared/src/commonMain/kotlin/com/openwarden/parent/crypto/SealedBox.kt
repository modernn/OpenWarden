package com.openwarden.parent.crypto

import com.ionspin.kotlin.crypto.box.Box

/**
 * Event-log sealing via libsodium `crypto_box_seal` — the ONLY event-channel
 * encryption primitive (ADR-015 / CRYPTO.md §4).
 *
 * Anonymous sealed box: child events are encrypted to the parent's X25519
 * pubkey; libsodium generates an ephemeral sender keypair, derives the symmetric
 * key with **BLAKE2b** (NOT BLAKE3 — see ADR-015; the BLAKE3 hash is only the log
 * chain, [com.openwarden.proto.EventEntry.prevHash]), encrypts with
 * XSalsa20-Poly1305, and **zeroizes the ephemeral private key immediately**. The
 * child therefore retains no secret capable of decrypting its own writes — the
 * load-bearing "child cannot read own log" property. No authenticated `crypto_box`,
 * no sender private key in the event path, no double-ratchet (all struck by ADR-015).
 *
 * Requires [bootstrapCrypto] to have completed. The seal/open round-trip is verified
 * on-device (`androidInstrumentedTest`) — the desktop JVM ships no libsodium.
 */
object SealedBox {
    /** `crypto_box_SEALBYTES`: 32-byte ephemeral X25519 pub ‖ 16-byte Poly1305 tag. */
    const val SEAL_OVERHEAD_BYTES: Int = 48

    /**
     * Anonymously seal [plaintext] to [recipientX25519Pub]
     * (CRYPTO.md §4: `sealed_box = ephemeral_x25519_pub || ciphertext_with_tag`).
     * The sender is anonymous; libsodium discards the ephemeral private key.
     */
    fun seal(recipientX25519Pub: UByteArray, plaintext: ByteArray): UByteArray =
        Box.seal(plaintext.toUByteArray(), recipientX25519Pub)

    /**
     * Open a sealed box with the recipient's X25519 keypair.
     *
     * Fail-CLOSED and loud: any cryptographic failure (wrong key, truncated or
     * forged ciphertext) yields [OpenResult.Failure] — never a silent fail-open,
     * never a thrown exception on crypto failure. Per CRYPTO.md the caller is
     * forced to handle the failure branch.
     */
    fun open(
        recipientX25519Pub: UByteArray,
        recipientX25519Priv: UByteArray,
        sealed: UByteArray,
    ): OpenResult =
        runCatching { Box.sealOpen(sealed, recipientX25519Pub, recipientX25519Priv) }
            .fold(
                onSuccess = { OpenResult.Success(it.toByteArray()) },
                onFailure = { OpenResult.Failure },
            )

    /** Sealed result type — forces callers to handle decrypt failure (fail-closed). */
    sealed class OpenResult {
        data class Success(val plaintext: ByteArray) : OpenResult() {
            override fun equals(other: Any?): Boolean =
                this === other || (other is Success && plaintext.contentEquals(other.plaintext))

            override fun hashCode(): Int = plaintext.contentHashCode()
        }

        data object Failure : OpenResult()
    }
}

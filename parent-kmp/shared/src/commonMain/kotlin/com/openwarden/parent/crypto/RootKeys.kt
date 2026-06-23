package com.openwarden.parent.crypto

/**
 * The derived parent root key material (ADR-033 D1). [ed25519Seed] and [x25519Private] are the
 * 32-byte private scalars (the Ed25519 seed is the RFC 8032 private key, accepted as-is by both
 * Bouncy Castle and libsodium). Serialized for at-rest storage as a fixed 128-byte layout.
 */
class RootKeys(
    val ed25519Seed: ByteArray,
    val ed25519Public: ByteArray,
    val x25519Private: ByteArray,
    val x25519Public: ByteArray,
) {
    init {
        require(ed25519Seed.size == 32 && ed25519Public.size == 32) { "ed25519 parts must be 32 bytes" }
        require(x25519Private.size == 32 && x25519Public.size == 32) { "x25519 parts must be 32 bytes" }
    }

    /** Fixed 128-byte layout: ed25519Seed ‖ x25519Private ‖ ed25519Public ‖ x25519Public. */
    fun serialize(): ByteArray = ed25519Seed + x25519Private + ed25519Public + x25519Public

    /** Best-effort zeroization of the private scalars. */
    fun wipe() {
        ed25519Seed.fill(0)
        x25519Private.fill(0)
    }

    companion object {
        const val SERIALIZED_SIZE = 128

        fun deserialize(bytes: ByteArray): RootKeys {
            require(bytes.size == SERIALIZED_SIZE) { "expected $SERIALIZED_SIZE bytes" }
            return RootKeys(
                ed25519Seed = bytes.copyOfRange(0, 32),
                x25519Private = bytes.copyOfRange(32, 64),
                ed25519Public = bytes.copyOfRange(64, 96),
                x25519Public = bytes.copyOfRange(96, 128),
            )
        }
    }
}

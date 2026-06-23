package com.openwarden.parent.crypto

/** In-memory [SecureKeyStorage] double for host-side fail-closed tests (ADR-033 D8). */
class FakeSecureKeyStorage : SecureKeyStorage {
    private var blob: ByteArray? = null

    override fun write(blob: ByteArray) {
        this.blob = blob.copyOf()
    }

    override fun read(): ByteArray? = blob?.copyOf()

    override fun clear() {
        blob = null
    }
}

internal fun ByteArray.toHexString(): String =
    joinToString("") { (it.toInt() and 0xFF).toString(16).padStart(2, '0') }

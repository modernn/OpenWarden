package com.openwarden.parent.crypto

/**
 * At-rest persistence seam for the serialized [RootKeys] (ADR-033 D6). The real Android impl wraps
 * the blob in EncryptedSharedPreferences under a StrongBox-backed MasterKey; tests inject an
 * in-memory double so the fail-closed provider semantics are provable host-side.
 */
interface SecureKeyStorage {
    fun write(blob: ByteArray)
    fun read(): ByteArray?
    fun clear()
}

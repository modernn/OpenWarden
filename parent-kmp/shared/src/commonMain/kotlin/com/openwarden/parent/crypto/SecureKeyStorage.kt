package com.openwarden.parent.crypto

/**
 * At-rest persistence seam for the serialized [RootKeys] (ADR-033 D6). The real Android impl wraps
 * the blob in EncryptedSharedPreferences under a StrongBox-backed MasterKey; tests inject an
 * in-memory double so the fail-closed provider semantics are provable host-side.
 *
 * **Fail-closed read contract (ADR-033 amendment, #144).** [read] returns `null` whenever the key
 * material is unavailable for ANY reason — not yet provisioned, OR the secure store itself cannot be
 * opened (e.g. the device has no secure lock screen, so the user-authentication-required MasterKey
 * cannot be created). It MUST NOT throw: callers ([StoredRootKeyProvider], and through it the pairing
 * and bundle-signing paths) treat `null` as "no usable root key" and fail closed gracefully. [write]
 * is the opposite direction — it MUST NOT silently succeed when it cannot persist; it throws
 * [SecureStorageUnavailableException] so provisioning never believes a key was stored when it was not.
 */
interface SecureKeyStorage {
    /** Persist [blob]. Throws [SecureStorageUnavailableException] if the secure store is unavailable. */
    fun write(blob: ByteArray)

    /** The stored blob, or `null` if not provisioned OR the secure store is unavailable. Never throws. */
    fun read(): ByteArray?

    /** Best-effort remove the stored blob. Never throws (a store that can't be opened has nothing to clear). */
    fun clear()
}

/**
 * The secure key store could not be opened to persist a key — e.g. the device has no secure lock
 * screen, so the user-authentication-required MasterKey cannot be created (#144 / ADR-033). Thrown
 * only by [SecureKeyStorage.write]; reads fail closed to `null` instead. Surfaced so a provisioning
 * flow can tell the parent to set a screen lock rather than crash on a raw keystore exception.
 */
class SecureStorageUnavailableException(
    message: String,
    cause: Throwable? = null,
) : IllegalStateException(message, cause)

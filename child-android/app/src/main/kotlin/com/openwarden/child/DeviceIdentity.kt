package com.openwarden.child

import java.security.SecureRandom

/**
 * The child's stable device identity used for ADR-017 §6 audience binding.
 *
 * ADR-017 §6: "every `PolicyBundle` MUST carry a signed `child_device_id` (the
 * child's pinned Ed25519 pubkey, or a stable id derived from it)." The child
 * rejects `MALFORMED` any bundle whose `child_device_id` ≠ its own.
 *
 * v1 uses a random, CSPRNG-generated stable id persisted once in
 * [ReplayFloorStore]. It is stable for the life of a provisioning (it lives in
 * the same TEE/StrongBox-bound encrypted store as the floor + marker).
 *
 *   >>> FOLLOW-UP (tracked): when the child Ed25519 key-pair lands (recovery-phrase
 *   >>> / key-management work, issue #15), DERIVE this id from the child pubkey
 *   >>> (e.g. hex/base64url of the 32-byte pubkey) instead of a random value, so
 *   >>> the id is cryptographically bound to the child key the parent pins at
 *   >>> pairing. The pure random id is sufficient for audience *binding* (it is a
 *   >>> unique, signed recipient name) but is not yet key-derived. Out of scope
 *   >>> for this PR (#5/#10).
 */
object DeviceIdentity {
    /** Hex of 16 CSPRNG bytes — a stable, unique recipient name for audience binding. */
    fun generateStableId(): String {
        val bytes = ByteArray(16)
        SecureRandom().nextBytes(bytes)
        return bytes.joinToString("") { "%02x".format(it) }
    }
}

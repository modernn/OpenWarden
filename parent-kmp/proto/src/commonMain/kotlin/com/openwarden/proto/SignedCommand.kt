package com.openwarden.proto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Parent-side signed lock / unlock command (ADR-030 / ADR-046 D3).
 *
 * Wire-shape-**identical** to the child's `SignedCommand` (child-android is a separate gradle module,
 * so the type is mirrored here, not shared). The Ed25519 signature in [sig] covers the RFC 8785 (JCS)
 * canonical bytes of this object with the `sig` field removed — see `CommandSigner.signingBytes` — so a
 * BouncyCastle-signed command verifies under the child's i2p Ed25519 verifier (both RFC 8032).
 *
 * `@SerialName` pins the snake_case wire keys the child reads (`child_device_id`, `issued_at`); the
 * other keys (`v`, `type`, `sig`) are already their property names. [issuedAt] is the parent wall-clock
 * (ms) and doubles as the monotonic replay floor + the freshness anchor the child checks.
 */
@Serializable
data class SignedCommand(
    val v: Int = VERSION,
    val type: String = "",
    @SerialName("child_device_id") val childDeviceId: String = "",
    @SerialName("issued_at") val issuedAt: Long = 0L,
    val sig: String = "",
) {
    companion object {
        const val VERSION = 1
        const val TYPE_LOCK = "lock"
        const val TYPE_UNLOCK = "unlock"
    }
}

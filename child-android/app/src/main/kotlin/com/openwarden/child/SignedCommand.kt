package com.openwarden.child

import kotlinx.serialization.Serializable

/**
 * A parent-authenticated lock/unlock command (ADR-030). Signed by the parent Ed25519 key over the
 * JCS-canonical body minus `sig` — the identical byte-rules as [SignedBundle] and [SignedHeartbeat]
 * (ADR-015 one-signing-rule). It lets a parent toggle the child's durable lock state over the LAN
 * server WITHOUT issuing a full policy bundle.
 *
 * Wire keys are the property names (snake_case). Defaults exist only so a partial object still
 * parses; an empty `type`, `child_device_id`, or `sig` can never verify or admit, so the defaults
 * are fail-closed.
 *
 * `issued_at` is the parent's wall-clock ms at signing. It serves two anti-replay roles in
 * [CommandAdmission]: the **monotonic replay floor** (a command at or below the floor is a replay)
 * AND a **freshness window** (a captured command is only admissible within
 * [CommandAdmission.FRESHNESS_MS] of the child's clock — the floor alone cannot bound a command
 * that was captured on the wire but never delivered; ADR-030 D4).
 */
@Serializable
data class SignedCommand(
    val v: Int,
    val type: String = "",
    val child_device_id: String = "",
    val issued_at: Long = 0L,
    val sig: String = "",
) {
    companion object {
        const val TYPE_LOCK = "lock"
        const val TYPE_UNLOCK = "unlock"

        /** The only command verbs the child admits. An unknown verb is rejected before any crypto. */
        val VALID_TYPES = setOf(TYPE_LOCK, TYPE_UNLOCK)
    }
}

package com.openwarden.child

import kotlinx.serialization.Serializable

/**
 * A minimal authenticated parent keep-alive (ADR-024 D4). Signed by the parent Ed25519 key over
 * the JCS-canonical body minus `sig` (identical byte-rules to [SignedBundle]). It lets a parent
 * reset the child's no-contact ratchet ([ContactClock]) WITHOUT re-issuing a full policy bundle.
 *
 * Wire keys are the property names (snake_case). Defaults exist only so a partial object still
 * parses; an empty `child_device_id` or `sig` can never verify, so the defaults are fail-closed.
 *
 * `issued_at` is used ONLY as the monotonic replay floor (a captured heartbeat replayed must not
 * reset the ratchet). It is NOT the silence clock authority — silence is measured by the child's
 * own monotonic clock (ADR-024 D2/D4), so a rolled-back parent time cannot revive freshness.
 */
@Serializable
data class SignedHeartbeat(
    val v: Int,
    val child_device_id: String = "",
    val issued_at: Long = 0L,
    val sig: String = "",
)

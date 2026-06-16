package com.openwarden.proto

import kotlinx.serialization.Serializable

/**
 * Wire types shared verbatim between the parent app and the child DPC
 * (PARENT_KMP_STRUCTURE.md §1, §4).
 *
 * SCAFFOLD: field sets are an initial pass aligned with the proposed ADRs
 * (single [PolicyBundle.policySeq] per ADR-017, [PolicyBundle.childDeviceId]
 * audience binding per ADR-017, sealed-box ciphertext payload per ADR-015).
 * The frozen wire format is governed by PROTOCOL.md / CRYPTO.md once ADR-013..017
 * are accepted; treat this as not-yet-final.
 */

@Serializable
data class PolicyBundle(
    val v: Int = POLICY_BUNDLE_FORMAT_VERSION,
    val policySeq: Long,        // monotonic replay floor (ADR-017); JCS-bounded 0..2^53-1
    val childDeviceId: String,  // audience binding — reject if != this device (ADR-017)
    val notBefore: Long,
    val notAfter: Long,         // short freshness window (ADR-017)
    val policy: Policy,
    val sig: String? = null,    // Ed25519 over JCS(bundle without "sig") (ADR-015)
)

@Serializable
data class Policy(
    val allowlist: List<String> = emptyList(),
    val blocklist: List<String> = emptyList(),
    val windows: List<TimeWindow> = emptyList(),
)

@Serializable
data class TimeWindow(
    val label: String,
    val tz: String,             // evaluated against signed tz, never device TZ (red-team T2)
    val startMinuteOfDay: Int,
    val endMinuteOfDay: Int,
    val daysOfWeek: List<Int> = (1..7).toList(),
)

@Serializable
data class EventEntry(
    val v: Int = POLICY_BUNDLE_FORMAT_VERSION,
    val seq: Long,
    val prevHash: String,       // BLAKE3 log chain (ADR-015 leaves the chain on BLAKE3)
    val issuedAt: Long,
    val payloadType: String,
    val payload: String,        // SealedEvent: base64 crypto_box_seal ciphertext (ADR-015)
    val sig: String? = null,    // Ed25519 over JCS(entry without "sig") (ADR-015)
)

@Serializable
data class AckEntry(
    val policySeq: Long,
    val status: String,         // "applied" | "rejected:<reason>"
)

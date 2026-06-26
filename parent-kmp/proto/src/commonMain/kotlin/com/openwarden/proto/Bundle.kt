package com.openwarden.proto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Wire types shared verbatim between the parent app and the child DPC
 * (PROTOCOL.md §2 — the single canonical PolicyBundle field set).
 *
 * The Kotlin property names are camelCase; the **wire** (canonical JCS) names are
 * the snake_case names PROTOCOL.md §2 dictates, pinned with [SerialName]. The
 * signer (parent) and the verifier (child `SignedBundle`) MUST emit byte-identical
 * canonical JSON for the same logical bundle, so both sides serialize to the §2
 * names and types: snake_case keys, u53-bounded integer timestamps/seq (ms, NOT
 * ISO-8601 strings). `sig` is excluded from the signed bytes on both sides.
 */

@Serializable
data class PolicyBundle(
    val v: Int = POLICY_BUNDLE_FORMAT_VERSION,
    @SerialName("policy_seq")
    val policySeq: Long, // monotonic replay floor (ADR-017); u53-bounded 0..2^53-1
    @SerialName("child_device_id")
    val childDeviceId: String, // audience binding — reject if != this device (ADR-017 §6)
    @SerialName("issued_at")
    val issuedAt: Long, // parent's claimed authorship time, ms (u53-bounded)
    @SerialName("not_before")
    val notBefore: Long, // earliest legal application time, ms (u53-bounded)
    @SerialName("not_after")
    val notAfter: Long, // latest legal application time, ms — short freshness window (ADR-017)
    val nonce: String, // CSPRNG-fresh per bundle, hex (32 chars / 16 bytes)
    val policy: Policy,
    val sig: String? = null, // Ed25519 over JCS(bundle without "sig") (ADR-015)
)

@Serializable
data class Policy(
    val allowlist: List<String> = emptyList(),
    val blocklist: List<String> = emptyList(),
    val windows: List<TimeWindow> = emptyList(),
    val restrictions: List<String> = emptyList(),
    @SerialName("private_dns")
    val privateDns: String? = null,
    @SerialName("frp_account_email")
    val frpAccountEmail: String? = null,
)

/**
 * Per-app time window (PROTOCOL.md §2 `policy.windows[]`). The wire shape is the §2
 * string form: `{"pkg","allow":"16:00-18:00","days":"Mon,Tue,...","tz"}`. `tz` is
 * the signed tz, never the device TZ (red-team T2).
 */
@Serializable
data class TimeWindow(
    val pkg: String,
    val allow: String, // "HH:MM-HH:MM" local window
    val days: String, // comma-separated day names, e.g. "Mon,Tue,Wed,Thu,Fri"
    val tz: String,
)

@Serializable
data class EventEntry(
    val v: Int = POLICY_BUNDLE_FORMAT_VERSION,
    val seq: Long,
    val prevHash: String, // BLAKE3 log chain (ADR-015 leaves the chain on BLAKE3)
    val issuedAt: Long,
    val payloadType: String,
    val payload: String, // SealedEvent: base64 crypto_box_seal ciphertext (ADR-015)
    val sig: String? = null, // Ed25519 over JCS(entry without "sig") (ADR-015)
)

@Serializable
data class AckEntry(
    val policySeq: Long,
    val status: String, // "applied" | "rejected:<reason>"
)

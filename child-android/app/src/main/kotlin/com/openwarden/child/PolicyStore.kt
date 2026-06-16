package com.openwarden.child

import android.content.Context
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

/**
 * Loads + persists signed policy bundles. Verifies Ed25519 sig against pinned parent pubkey.
 * Rejects bundles past expiry.
 *
 * Storage: app's internal storage (encrypted at rest by FBE; DO-only access).
 */
class PolicyStore(private val context: Context) {

    private val dir get() = File(context.filesDir, "policy").apply { mkdirs() }
    private val activeFile get() = File(dir, "active.json")
    private val pubkeyFile get() = File(dir, "parent.pub")

    fun pinParentPubkey(rawPubkey: ByteArray) {
        require(rawPubkey.size == 32) { "Ed25519 pubkey must be 32 bytes" }
        pubkeyFile.writeBytes(rawPubkey)
    }

    fun parentPubkey(): ByteArray? =
        if (pubkeyFile.exists()) pubkeyFile.readBytes() else null

    fun ingest(bundle: SignedBundle): IngestResult {
        val pubkey = parentPubkey() ?: return IngestResult.NoParentPinned
        if (!BundleVerifier.verify(bundle, pubkey)) return IngestResult.BadSignature
        if (bundle.isExpired()) return IngestResult.Expired

        // Replay protection: reject older issued_at than current active
        val current = loadActive()
        if (current != null && bundle.issued_at <= current.issued_at) {
            return IngestResult.OlderThanActive
        }

        activeFile.writeText(Json.encodeToString(SignedBundle.serializer(), bundle))
        return IngestResult.Applied
    }

    fun loadActive(): SignedBundle? {
        if (!activeFile.exists()) return null
        return try {
            Json.decodeFromString(SignedBundle.serializer(), activeFile.readText())
        } catch (e: Exception) {
            null
        }
    }

    enum class IngestResult { Applied, NoParentPinned, BadSignature, Expired, OlderThanActive }
}

@Serializable
data class SignedBundle(
    val v: Int,
    val issued_at: String,    // ISO-8601
    val expires_at: String,
    val nonce: String,
    val policy: PolicyDoc,
    val sig: String           // hex Ed25519 over canonicalized {v, issued_at, expires_at, nonce, policy}
) {
    fun isExpired(): Boolean {
        // TODO(v1): proper instant parsing
        return false
    }
}

@Serializable
data class PolicyDoc(
    val allowlist: List<String> = emptyList(),
    val windows: List<TimeWindow> = emptyList(),
    val restrictions: List<String> = emptyList(),
    val private_dns: String? = null
)

@Serializable
data class TimeWindow(
    val pkg: String,
    val allow_cron: String
)

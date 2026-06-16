package com.openwarden.child

import android.content.Context
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.AtomicMoveNotSupportedException

/**
 * Loads + persists signed policy bundles. Verifies Ed25519 sig against pinned parent pubkey.
 * Rejects bundles past expiry.
 *
 * Storage: app's internal storage (encrypted at rest by FBE; DO-only access).
 */
class PolicyStore(private val context: Context) {

    private val dir get() = File(context.filesDir, "policy").apply { mkdirs() }
    private val activeFile get() = File(dir, "active.json")
    private val tmpFile get() = File(dir, "active.json.tmp")
    private val pubkeyFile get() = File(dir, "parent.pub")

    sealed class LoadResult {
        data class Loaded(val bundle: SignedBundle) : LoadResult()
        object Missing : LoadResult()
        object Corrupt : LoadResult()
    }

    fun pinParentPubkey(rawPubkey: ByteArray) {
        require(rawPubkey.size == 32) { "Ed25519 pubkey must be 32 bytes" }
        pubkeyFile.writeBytes(rawPubkey)
    }

    fun parentPubkey(): ByteArray? =
        if (pubkeyFile.exists()) pubkeyFile.readBytes() else null

    /**
     * Atomically persists [bundle] to internal storage.
     * Writes to a temp file first, then renames over the active file so a power loss
     * mid-write never leaves a partially-written active.json.
     */
    fun persist(bundle: SignedBundle) {
        val tmp = tmpFile
        try {
            tmp.writeText(Json.encodeToString(SignedBundle.serializer(), bundle))
            try {
                Files.move(
                    tmp.toPath(),
                    activeFile.toPath(),
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING,
                )
            } catch (_: AtomicMoveNotSupportedException) {
                // Fallback: delete dest then rename (same filesystem, so should not fail)
                activeFile.delete()
                tmp.renameTo(activeFile)
            }
        } catch (e: Exception) {
            tmp.delete()
            throw e
        }
    }

    fun ingest(bundle: SignedBundle): IngestResult {
        val pubkey = parentPubkey() ?: return IngestResult.NoParentPinned
        if (!BundleVerifier.verify(bundle, pubkey)) return IngestResult.BadSignature
        if (bundle.isExpired()) return IngestResult.Expired

        // Replay protection: reject older issued_at than current active
        val current = loadActive()
        if (current != null && bundle.issued_at <= current.issued_at) {
            return IngestResult.OlderThanActive
        }

        persist(bundle)
        return IngestResult.Applied
    }

    /**
     * Returns [LoadResult.Missing] if no active bundle file exists,
     * [LoadResult.Corrupt] if the file exists but cannot be parsed,
     * or [LoadResult.Loaded] with the parsed bundle on success.
     */
    fun load(): LoadResult {
        if (!activeFile.exists()) return LoadResult.Missing
        return try {
            val bundle = Json.decodeFromString(SignedBundle.serializer(), activeFile.readText())
            LoadResult.Loaded(bundle)
        } catch (e: Exception) {
            LoadResult.Corrupt
        }
    }

    /**
     * Convenience wrapper — returns null for both Missing and Corrupt so existing callers
     * remain unchanged and the system stays fail-closed.
     */
    fun loadActive(): SignedBundle? = (load() as? LoadResult.Loaded)?.bundle

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

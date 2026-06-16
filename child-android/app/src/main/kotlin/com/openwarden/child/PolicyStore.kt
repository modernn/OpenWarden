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
     *
     * Uses a unique temp file per call (via [File.createTempFile]) so concurrent calls
     * can never collide on a shared ".tmp" path. The move sequence:
     *   1. Primary: ATOMIC_MOVE + REPLACE_EXISTING — a single kernel rename(2), fail-closed.
     *   2. Fallback (cross-fs edge): plain renameTo() WITHOUT pre-deleting the destination —
     *      on Android same-filesystem rename(2) replaces atomically; deleting first would open
     *      a fail-open window where active.json is gone but not yet replaced.
     *   3. Last resort: Files.move with REPLACE_EXISTING (no ATOMIC guarantee, but safe).
     * The finally block ensures the temp file is always cleaned up; after a successful move
     * it no longer exists, so the delete is a harmless no-op.
     */
    fun persist(bundle: SignedBundle) {
        val tmp = File.createTempFile("active", ".tmp", dir)
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
                // Same-filesystem fallback: rename(2) atomically replaces — do NOT delete
                // destination first (that delete→rename gap is the fail-open bug we closed).
                if (!tmp.renameTo(activeFile)) {
                    // Last resort: non-atomic but still overwrites the destination.
                    Files.move(tmp.toPath(), activeFile.toPath(), StandardCopyOption.REPLACE_EXISTING)
                }
            }
        } finally {
            // After a successful move tmp no longer exists; this is a safe no-op then.
            if (tmp.exists()) tmp.delete()
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

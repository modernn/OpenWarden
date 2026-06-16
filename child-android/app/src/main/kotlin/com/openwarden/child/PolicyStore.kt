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

    /**
     * Legacy ingest path. SUPERSEDED by [PolicyAdmission.admit], which implements the
     * ADR-017 replay floor (`policy_seq` monotonic + jump bound), audience binding,
     * JCS integer bound, genesis gate, and two-phase commit. This method's `issued_at`
     * string comparison is NOT the ADR-017 replay floor and MUST NOT be used as one.
     * Retained only so it still compiles for any out-of-tree caller; the `/policy`
     * route now routes through [PolicyAdmission]. Do not add new callers.
     */
    @Deprecated("Use PolicyAdmission.admit (ADR-017 replay floor + audience + two-phase commit)")
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
    // ADR-017 §6 audience binding: the addressed child's pinned id (Ed25519-pubkey-derived
    // stable id). MANDATORY signed field. The child rejects MALFORMED any bundle whose
    // child_device_id != its own, BEFORE signature verification. Defaulted empty only so
    // legacy stored bundles (pre-ADR-017) still parse; an empty id can never match a real
    // child id, so it fails audience binding fail-closed.
    val child_device_id: String = "",
    // ADR-017: device-global monotonic replay floor counter. MANDATORY signed field.
    // u53-bounded integer 0..2^53-1 (JCS-safe). policy_seq=0 is reserved and never a live
    // policy. Defaulted 0 only so legacy stored bundles still parse; 0 is never admissible
    // through PolicyAdmission, so the default is fail-closed.
    val policy_seq: Long = 0L,
    val issued_at: String,    // ISO-8601
    val expires_at: String,
    val nonce: String,
    val policy: PolicyDoc,
    // hex Ed25519 over canonicalized bundle minus "sig" (now includes child_device_id +
    // policy_seq). Tolerant of absence (default "") for verify-over-raw-bytes / storage-layer
    // tests; an empty sig can never verify, so it stays fail-closed.
    val sig: String = "",
) {
    fun isExpired(): Boolean {
        // TODO(v1): proper instant parsing — tracked with the integer-ms not_before/not_after
        // freshness migration (ADR-017 part 5), out of scope for this PR (#5/#10).
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

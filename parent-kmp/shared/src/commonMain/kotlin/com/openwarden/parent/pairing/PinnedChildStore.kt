package com.openwarden.parent.pairing

/**
 * The pinned child identity committed at pairing (ADR-039 D1, ADR-025 D5e / §7.5): **both** the
 * `child_ed25519_pub` (identity / `child_device_id`, ADR-034 D4) and the `child_x25519_pub` (the
 * sealed-box event audience, ADR-015). Both keys are exactly 32 bytes; a wrong length is a
 * fail-closed construction error (no `PinnedChild` with a malformed key can exist). Defensive copies —
 * the carrier never hands out mutable internals.
 */
class PinnedChild(
    ed25519Pub: ByteArray,
    x25519Pub: ByteArray,
) {
    init {
        require(ed25519Pub.size == KEY_BYTES) { "child_ed25519_pub MUST be $KEY_BYTES bytes, got ${ed25519Pub.size}" }
        require(x25519Pub.size == KEY_BYTES) { "child_x25519_pub MUST be $KEY_BYTES bytes, got ${x25519Pub.size}" }
    }

    private val ed = ed25519Pub.copyOf()
    private val x = x25519Pub.copyOf()

    val ed25519Pub: ByteArray get() = ed.copyOf()
    val x25519Pub: ByteArray get() = x.copyOf()

    companion object {
        const val KEY_BYTES: Int = 32
    }
}

/**
 * Persists the single pinned child (ADR-039). The pin is the pairing **trust anchor** (§7.5; closes
 * H3), so the contract is fail-closed and **write-once**:
 *
 *  - [pinnedChild] returns the pinned child, or `null` if unpaired. It returns non-null **only** when
 *    both keys are present and decode to exactly 32 bytes (ADR-039 D2/D6) — a lone or malformed key
 *    reads as unpaired, never a half-pin.
 *  - [pin] commits both keys **atomically** (one write, all-or-nothing) and is **write-once**: it MUST
 *    refuse (throw) if a child is already pinned and MUST throw on any write failure (ADR-039 D2/D3).
 *    Replacing a pinned child is a recovery-gated rotation (§7.5/D8), **not** an overwrite this seam
 *    offers.
 *
 * The real `androidMain` impl is [com.openwarden.parent.policy.AndroidPairedChildStore] over the
 * parent's StrongBox-backed `EncryptedSharedPreferences`; host tests inject a fake.
 */
interface PinnedChildStore {
    /** The pinned child, or `null` if unpaired (or only one / a malformed key is present — fail-closed). */
    fun pinnedChild(): PinnedChild?

    /**
     * Atomically pin [child] (both keys, one write). Write-once: throws if a child is already pinned,
     * and throws on any write failure (fail-closed). Never leaves a half-pin.
     */
    fun pin(child: PinnedChild)
}

/**
 * Takes + burns the single live pairing session on the success handoff (ADR-039 D4) — the
 * `PairingSessionManager.consume()` half that slices (a)–(d) reserved for slice (e). Separate from
 * slice (b)'s [SessionAccess] (active/cancel) so adding the success-burn does not change that shipped
 * seam; the Android adapter implements both over the same manager under the one `sessionLock`
 * (ADR-036 D5).
 */
fun interface PairingSessionConsumer {
    /** Take + burn the live session (or `null` if none/expired). Single-use (§7.1). */
    fun consume(): PairingSession?
}

package com.openwarden.parent.pairing

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * In-memory store for the single pinned child peer (PROTOCOL.md §7.5 — parent
 * side). Exposes a [StateFlow] so the UI can observe pairing state reactively.
 *
 * v1 is intentionally in-memory; durable storage (EncryptedSharedPreferences /
 * Keychain) is wired at the platform layer once the crypto bootstrap is complete.
 * The store is designed so the platform persistence adapter can wrap it:
 * on startup the adapter reads persisted bytes and calls [pin], then the StateFlow
 * drives the UI without any further platform bridging.
 *
 * Fail-closed: [pin] is the only mutation path. Nothing calls [pin] unless
 * [PairingPayloadParser.parse] returned [PairingParseResult.Success].
 */
class PinnedPeerStore {
    private val _peer = MutableStateFlow<PinnedPeer?>(null)

    /** The currently pinned peer, or null if not yet paired. Null = unpaired (fail-closed). */
    val peer: StateFlow<PinnedPeer?> = _peer.asStateFlow()

    /** True iff a valid peer has been pinned. */
    val isPaired: Boolean get() = _peer.value != null

    /**
     * Persist [peer] as the pinned child identity. Replaces any previously pinned peer.
     *
     * Only call after a successful [PairingPayloadParser.parse] result.
     */
    fun pin(peer: PinnedPeer) {
        _peer.value = peer
    }

    /** Clear the pinned peer (for factory-reset / re-pair flows). */
    fun clear() {
        _peer.value = null
    }
}

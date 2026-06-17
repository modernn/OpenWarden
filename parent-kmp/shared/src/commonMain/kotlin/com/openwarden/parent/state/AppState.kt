package com.openwarden.parent.state

import com.openwarden.parent.pairing.PinnedPeer
import com.openwarden.parent.pairing.PinnedPeerStore
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.SharingStarted

enum class PairingStatus { UNPAIRED, PAIRED }

/**
 * Top-level cross-platform app state (PARENT_KMP_STRUCTURE.md §1 state/).
 *
 * [peerStore] is the authoritative pairing store. [pairing] is a derived
 * [StateFlow] for UI consumers that only need the PAIRED/UNPAIRED status.
 */
class AppState(
    val peerStore: PinnedPeerStore = PinnedPeerStore(),
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** Reactive pairing status derived from [peerStore]. */
    val pairing: StateFlow<PairingStatus> = peerStore.peer
        .map { peer -> if (peer != null) PairingStatus.PAIRED else PairingStatus.UNPAIRED }
        .stateIn(scope, SharingStarted.Eagerly, PairingStatus.UNPAIRED)

    /** Pin the child peer after a successful QR parse + confirmation. */
    fun setPeer(peer: PinnedPeer) { peerStore.pin(peer) }

    /** Factory-reset back to UNPAIRED. */
    fun reset() { peerStore.clear() }
}

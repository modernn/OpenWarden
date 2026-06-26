package com.openwarden.parent.state

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class PairingStatus { UNPAIRED, PAIRED }

/**
 * Child lock state as last confirmed by a successful command round-trip or a state poll.
 * UNKNOWN is the initial / error-recovery state; it is treated fail-closed (show no
 * affordance to assume the child is unlocked).
 */
enum class LockState { UNKNOWN, LOCKED, UNLOCKED }

/** Top-level cross-platform app state (PARENT_KMP_STRUCTURE.md §1 state/). */
class AppState {
    private val _pairing = MutableStateFlow(PairingStatus.UNPAIRED)
    val pairing: StateFlow<PairingStatus> = _pairing.asStateFlow()

    private val _lockState = MutableStateFlow(LockState.UNKNOWN)
    val lockState: StateFlow<LockState> = _lockState.asStateFlow()

    fun setPaired() {
        _pairing.value = PairingStatus.PAIRED
    }

    fun reset() {
        _pairing.value = PairingStatus.UNPAIRED
        _lockState.value = LockState.UNKNOWN
    }

    fun setLockState(state: LockState) {
        _lockState.value = state
    }
}

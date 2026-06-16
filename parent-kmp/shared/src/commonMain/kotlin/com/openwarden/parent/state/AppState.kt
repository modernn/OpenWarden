package com.openwarden.parent.state

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class PairingStatus { UNPAIRED, PAIRED }

/** Top-level cross-platform app state (PARENT_KMP_STRUCTURE.md §1 state/). */
class AppState {
    private val _pairing = MutableStateFlow(PairingStatus.UNPAIRED)
    val pairing: StateFlow<PairingStatus> = _pairing.asStateFlow()

    fun setPaired() { _pairing.value = PairingStatus.PAIRED }
    fun reset() { _pairing.value = PairingStatus.UNPAIRED }
}

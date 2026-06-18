package com.openwarden.parent.command

import com.openwarden.parent.state.AppState
import com.openwarden.parent.state.LockState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Presenter for Lock-now / Unlock-now (issue #28).
 *
 * Platform-agnostic: lives in commonMain and holds no Android / iOS imports.
 * The UI layer (Compose on Android, SwiftUI on iOS) observes [uiState] and
 * calls [lockNow] / [unlockNow].
 *
 * Fail-closed: any [LockCommandResult.Failure] leaves [AppState.lockState] as
 * [LockState.UNKNOWN] so the UI cannot falsely show "child is unlocked".
 */
class LockPresenter(
    private val appState: AppState,
    private val sender: LockCommandSender,
) {
    data class UiState(
        val lockState: LockState = LockState.UNKNOWN,
        val isBusy: Boolean = false,
        val lastError: String? = null,
    )

    private val _uiState = MutableStateFlow(UiState(lockState = appState.lockState.value))
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    /**
     * Issue a lock command.  Updates [UiState.isBusy] during the round-trip.
     * On success, persists [LockState.LOCKED] into [AppState].
     * On failure, resets to [LockState.UNKNOWN] (fail-closed) and surfaces the error.
     */
    suspend fun lockNow() {
        if (_uiState.value.isBusy) return
        _uiState.value = _uiState.value.copy(isBusy = true, lastError = null)
        when (val result = sender.sendLock()) {
            is LockCommandResult.Success -> {
                appState.setLockState(LockState.LOCKED)
                _uiState.value = UiState(lockState = LockState.LOCKED, isBusy = false)
            }
            is LockCommandResult.Failure -> {
                appState.setLockState(LockState.UNKNOWN)
                _uiState.value = UiState(
                    lockState = LockState.UNKNOWN,
                    isBusy = false,
                    lastError = result.message,
                )
            }
        }
    }

    /**
     * Issue an unlock command.  Updates [UiState.isBusy] during the round-trip.
     * On success, persists [LockState.UNLOCKED] into [AppState].
     * On failure, resets to [LockState.UNKNOWN] (fail-closed) and surfaces the error.
     */
    suspend fun unlockNow() {
        if (_uiState.value.isBusy) return
        _uiState.value = _uiState.value.copy(isBusy = true, lastError = null)
        when (val result = sender.sendUnlock()) {
            is LockCommandResult.Success -> {
                appState.setLockState(LockState.UNLOCKED)
                _uiState.value = UiState(lockState = LockState.UNLOCKED, isBusy = false)
            }
            is LockCommandResult.Failure -> {
                appState.setLockState(LockState.UNKNOWN)
                _uiState.value = UiState(
                    lockState = LockState.UNKNOWN,
                    isBusy = false,
                    lastError = result.message,
                )
            }
        }
    }
}

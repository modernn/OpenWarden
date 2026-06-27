package com.openwarden.parent.command

import com.openwarden.parent.state.AppState
import com.openwarden.parent.state.LockState
import kotlinx.coroutines.CancellationException
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
 * Fail-closed: any [LockCommandResult.Failure] OR any thrown exception leaves
 * [AppState.lockState] as [LockState.UNKNOWN] so the UI cannot falsely show
 * "child is unlocked".
 *
 * Reentrancy: [lockNow] / [unlockNow] use [MutableStateFlow.compareAndSet] to
 * atomically claim the busy flag.  Concurrent callers that lose the race return
 * immediately without invoking the seam.
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
     * On failure (result or exception), resets to [LockState.UNKNOWN] (fail-closed)
     * and surfaces the error.  [isBusy] is always cleared on exit.
     */
    suspend fun lockNow() {
        val idle = _uiState.value
        if (idle.isBusy) return
        // Atomic check-and-set: only one coroutine proceeds past this point.
        if (!_uiState.compareAndSet(idle, idle.copy(isBusy = true, lastError = null))) return

        try {
            when (val result = sender.sendLock()) {
                is LockCommandResult.Success -> {
                    appState.setLockState(LockState.LOCKED)
                    _uiState.value = UiState(lockState = LockState.LOCKED, isBusy = false)
                }

                is LockCommandResult.Failure -> {
                    appState.setLockState(LockState.UNKNOWN)
                    _uiState.value =
                        UiState(
                            lockState = LockState.UNKNOWN,
                            isBusy = false,
                            lastError = result.message,
                        )
                }
            }
        } catch (e: CancellationException) {
            // Coroutine is being cancelled — clear busy and rethrow so the
            // structured-concurrency machinery can propagate the cancellation.
            appState.setLockState(LockState.UNKNOWN)
            _uiState.value = _uiState.value.copy(isBusy = false)
            throw e
        } catch (e: Exception) {
            // Unexpected throw from the seam — fail-closed: UNKNOWN.
            appState.setLockState(LockState.UNKNOWN)
            _uiState.value =
                UiState(
                    lockState = LockState.UNKNOWN,
                    isBusy = false,
                    lastError = e.message ?: "unexpected error",
                )
        }
    }

    /**
     * Issue an unlock command.  Updates [UiState.isBusy] during the round-trip.
     * On success, persists [LockState.UNLOCKED] into [AppState].
     * On failure (result or exception), resets to [LockState.UNKNOWN] (fail-closed)
     * and surfaces the error.  [isBusy] is always cleared on exit.
     */
    suspend fun unlockNow() {
        val idle = _uiState.value
        if (idle.isBusy) return
        // Atomic check-and-set: only one coroutine proceeds past this point.
        if (!_uiState.compareAndSet(idle, idle.copy(isBusy = true, lastError = null))) return

        try {
            when (val result = sender.sendUnlock()) {
                is LockCommandResult.Success -> {
                    appState.setLockState(LockState.UNLOCKED)
                    _uiState.value = UiState(lockState = LockState.UNLOCKED, isBusy = false)
                }

                is LockCommandResult.Failure -> {
                    appState.setLockState(LockState.UNKNOWN)
                    _uiState.value =
                        UiState(
                            lockState = LockState.UNKNOWN,
                            isBusy = false,
                            lastError = result.message,
                        )
                }
            }
        } catch (e: CancellationException) {
            // Coroutine is being cancelled — clear busy and rethrow so the
            // structured-concurrency machinery can propagate the cancellation.
            appState.setLockState(LockState.UNKNOWN)
            _uiState.value = _uiState.value.copy(isBusy = false)
            throw e
        } catch (e: Exception) {
            // Unexpected throw from the seam — fail-closed: UNKNOWN.
            appState.setLockState(LockState.UNKNOWN)
            _uiState.value =
                UiState(
                    lockState = LockState.UNKNOWN,
                    isBusy = false,
                    lastError = e.message ?: "unexpected error",
                )
        }
    }
}

package com.openwarden.parent.dashboard

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Sealed UI-state for the dashboard.
 *
 * Fail-closed rule: [Error] and any stale / missing data degrade to showing the
 * child as [ChildOnlineStatus.OFFLINE_OR_UNKNOWN], never optimistically online.
 */
sealed class DashboardUiState {
    /** Initial state before the first load completes. */
    data object Loading : DashboardUiState()

    /**
     * Data loaded successfully.
     * [snapshot.onlineStatus] may still be [ChildOnlineStatus.OFFLINE_OR_UNKNOWN]
     * if the child reported offline; that is an honest, non-error state.
     */
    data class Success(val snapshot: ChildDashboardSnapshot) : DashboardUiState()

    /**
     * The repository threw or returned an unrecoverable error.
     * ALWAYS treated as offline/unknown — callers MUST NOT show last-known "online"
     * when in this state.
     */
    data class Error(val message: String) : DashboardUiState()
}

/**
 * Dashboard ViewModel — cross-platform (commonMain), no Android lifecycle dependency.
 *
 * State mapping contract:
 * - Repository success + online child → [DashboardUiState.Success] with
 *   [ChildOnlineStatus.ONLINE]
 * - Repository success + offline child → [DashboardUiState.Success] with
 *   [ChildOnlineStatus.OFFLINE_OR_UNKNOWN]  (honest, not an error)
 * - Repository throws / parse error → [DashboardUiState.Error]; caller shows
 *   child as OFFLINE_OR_UNKNOWN
 *
 * The Android-side wrapper (DashboardAndroidViewModel) bridges this into
 * [androidx.lifecycle.ViewModel].
 */
class DashboardViewModel(
    private val repository: ChildStateRepository,
    private val scope: CoroutineScope,
) {
    private val _uiState = MutableStateFlow<DashboardUiState>(DashboardUiState.Loading)
    val uiState: StateFlow<DashboardUiState> = _uiState.asStateFlow()

    /** Trigger a refresh.  Safe to call multiple times; in-flight request is replaced. */
    fun refresh() {
        _uiState.value = DashboardUiState.Loading
        scope.launch {
            _uiState.value = try {
                DashboardUiState.Success(repository.fetchSnapshot())
            } catch (e: Exception) {
                // Any uncaught exception from the repository must degrade to Error /
                // offline-unknown.  Never surface last-known-good as live.
                DashboardUiState.Error(e.message ?: "Unknown error")
            }
        }
    }
}

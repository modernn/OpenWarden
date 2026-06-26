package com.openwarden.parent.android.ui.dashboard

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.openwarden.parent.dashboard.ChildStateRepository
import com.openwarden.parent.dashboard.DashboardViewModel

/**
 * Android [ViewModel] wrapper around the cross-platform [DashboardViewModel].
 *
 * Bridges [viewModelScope] (lifecycle-aware) into the platform-agnostic VM.
 * The real [ChildStateRepository] (HTTP to child /state + /usage, issue #20)
 * is injected via [Factory]; the fake is used until #20 lands.
 */
class DashboardAndroidViewModel(
    repository: ChildStateRepository,
) : ViewModel() {
    private val delegate =
        DashboardViewModel(
            repository = repository,
            scope = viewModelScope,
        )

    val uiState = delegate.uiState

    fun refresh() = delegate.refresh()

    class Factory(
        private val repository: ChildStateRepository,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = DashboardAndroidViewModel(repository) as T
    }
}

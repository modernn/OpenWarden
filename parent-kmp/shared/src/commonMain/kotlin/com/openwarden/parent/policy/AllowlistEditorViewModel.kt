package com.openwarden.parent.policy

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * UI state for the allowlist editor screen (PARENT_KMP_STRUCTURE.md §1 state/).
 *
 * Loading   — initial fetch in progress; list is empty.
 * Ready     — list populated; [PolicyModel.allowlist] reflects toggled state.
 * Error     — fetch failed; [errorMessage] shown to parent; allowlist frozen
 *             fail-closed (no change from last persisted set).
 */
data class AllowlistEditorState(
    val loading: Boolean = false,
    val apps: List<AppInfo> = emptyList(),
    val allowlist: Set<String> = emptySet(),
    val errorMessage: String? = null,
)

/**
 * Presenter for [AllowlistEditorScreen] / SwiftUI [PolicyEditorView].
 *
 * - [load] fetches the installed-app list from [AllowlistRepository] and
 *   seeds the allowlist from the last-persisted snapshot.
 * - [toggle] flips a single package in the in-memory allowlist and
 *   persists the new snapshot immediately.
 * - [currentAllowlist] returns the live set for bundle-assembly callers (#27).
 *
 * Signing is INTENTIONALLY absent — that belongs in the bundle-send flow.
 */
class AllowlistEditorViewModel(
    private val repo: AllowlistRepository,
) {
    private val _state = MutableStateFlow(AllowlistEditorState())
    val state: StateFlow<AllowlistEditorState> = _state.asStateFlow()

    /** Returns the current in-memory allowlist for bundle assembly. */
    fun currentAllowlist(): Set<String> = _state.value.allowlist

    /**
     * Fetch installed apps from the child and restore the last-saved allowlist.
     * Fail-closed: on [FetchAppsResult.Error] the allowlist is restored from
     * persisted storage but the app list is shown as empty with an error banner.
     */
    suspend fun load() {
        _state.update { it.copy(loading = true, errorMessage = null) }
        val saved = repo.loadAllowlist()
        when (val result = repo.fetchInstalledApps()) {
            is FetchAppsResult.Success -> {
                _state.update {
                    it.copy(
                        loading = false,
                        apps = result.apps,
                        allowlist = saved,
                        errorMessage = null,
                    )
                }
            }
            is FetchAppsResult.Error -> {
                // Fail-closed: restore saved allowlist, show error, do NOT wipe allowlist.
                _state.update {
                    it.copy(
                        loading = false,
                        apps = emptyList(),
                        allowlist = saved,
                        errorMessage = result.message,
                    )
                }
            }
        }
    }

    /**
     * Toggle [packageName] in the allowlist and immediately persist the new snapshot.
     * Idempotent: calling twice returns to the original state.
     */
    fun toggle(packageName: String) {
        _state.update { s ->
            val next =
                if (packageName in s.allowlist) s.allowlist - packageName
                else s.allowlist + packageName
            s.copy(allowlist = next)
        }
        repo.saveAllowlist(_state.value.allowlist)
    }
}

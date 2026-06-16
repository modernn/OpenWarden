package com.openwarden.parent.policy

import com.openwarden.proto.Policy
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

data class AppInfo(
    val packageName: String,
    val label: String,
)

data class PolicyModel(
    val installedApps: List<AppInfo> = emptyList(),
    val allowlist: Set<String> = emptySet(),
)

/**
 * Cross-platform policy editor state (PARENT_KMP_STRUCTURE.md §1 state/, §6).
 * UI (Compose / SwiftUI) binds to [state]; intents call [toggleAllowlist].
 */
class PolicyEditor {
    private val _state = MutableStateFlow(PolicyModel())
    val state: StateFlow<PolicyModel> = _state.asStateFlow()

    fun setInstalledApps(apps: List<AppInfo>) {
        _state.update { it.copy(installedApps = apps) }
    }

    fun toggleAllowlist(pkg: String) {
        _state.update { m ->
            val next = if (pkg in m.allowlist) m.allowlist - pkg else m.allowlist + pkg
            m.copy(allowlist = next)
        }
    }

    /** Project the current editor state into the shared wire type. */
    fun toProtoPolicy(): Policy = Policy(allowlist = _state.value.allowlist.sorted())
}

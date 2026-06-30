package com.openwarden.parent.policy

import com.openwarden.parent.dashboard.AppCategory
import com.openwarden.proto.Policy
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.updateAndGet

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
    /** Current state of the "Apply to child" send operation. Null = idle. */
    val applyState: ApplyState? = null,
)

/**
 * State machine for the "Apply to child" operation.
 *
 * Fail-closed: only [Applied] represents a successful send confirmed by the child.
 * Every other terminal state is an error that must be surfaced to the parent.
 */
sealed interface ApplyState {
    /** Send in progress — disable the Apply button. */
    data object Sending : ApplyState

    /** Child confirmed the policy bundle was applied. */
    data class Applied(
        val policySeq: Long,
    ) : ApplyState

    /** Child rejected the bundle (e.g. REGRESSION/EXPIRED/SIG_FAIL). */
    data class Rejected(
        val reason: String,
    ) : ApplyState

    /** Transport failure — the bundle was signed but not delivered. */
    data class TransportFailed(
        val message: String,
    ) : ApplyState

    /** Root key not provisioned — parent must complete recovery-key setup first. */
    data object NotProvisioned : ApplyState

    /** No child paired yet — parent must complete pairing first. */
    data object NotPaired : ApplyState
}

/**
 * A category group for the AllowlistEditor — one per [AppCategory] value that has at
 * least one app. Apps within each group are sorted alphabetically by display label
 * (falling back to package name for blank labels). Groups are ordered by [AppCategory]
 * enum declaration order, with OTHER then UNKNOWN last (matching their enum positions).
 */
data class AppCategoryGroup(
    val category: AppCategory,
    val apps: List<AppInfo>,
)

/**
 * Presenter for [AllowlistEditorScreen] / SwiftUI [PolicyEditorView].
 *
 * - [load] fetches the installed-app list from [AllowlistRepository] and
 *   seeds the allowlist from the last-persisted snapshot.
 * - [toggle] flips a single package in the in-memory allowlist and
 *   persists the new snapshot immediately.
 * - [currentAllowlist] returns the live set for bundle-assembly callers (#27).
 * - [apply] sends the current allowlist to the child via [sendPolicy] (Part B).
 *   If [sendPolicy] is null the call maps immediately to [ApplyState.NotProvisioned]
 *   (avoids crashes when wiring is not complete in older demo contexts).
 *
 * [sendPolicy] is a `suspend (Policy) -> SendResult` lambda rather than a direct
 * [PolicySender] reference. This keeps the ViewModel agnostic of the concrete
 * [PolicySender] (which is a `class`, not an interface) and makes unit-testing
 * trivially easy: tests inject `{ policy -> FakeResult }` without subclassing or
 * modifying [PolicySender].
 *
 * Signing is INTENTIONALLY absent from this class — it delegates entirely to
 * [PolicySender] / [SignedBundleAssembler], which are CODEOWNERS-gated crypto.
 *
 * CRYPTO NOTE: [apply] calls [sendPolicy] which in production is wired to
 * [PolicySender.send]. All signing, bundle construction, and nonce generation live
 * in [PolicySender] and its collaborators — nothing crypto-adjacent lives here.
 */
class AllowlistEditorViewModel(
    private val repo: AllowlistRepository,
    /**
     * Optional send function. In production pass `{ policy -> policySender.send(policy) }`.
     * When null, [apply] maps immediately to [ApplyState.NotProvisioned].
     */
    private val sendPolicy: (suspend (Policy) -> SendResult)? = null,
) {
    private val _state = MutableStateFlow(AllowlistEditorState())
    val state: StateFlow<AllowlistEditorState> = _state.asStateFlow()

    /** Returns the current in-memory allowlist for bundle assembly. */
    fun currentAllowlist(): Set<String> = _state.value.allowlist

    /**
     * The installed apps grouped by [AppCategory] and sorted for display.
     *
     * Groups are in [AppCategory] enum declaration order (OTHER, UNKNOWN last).
     * Apps within each group are sorted by label ascending, falling back to packageName
     * for blank labels.
     *
     * Returns an empty list when no apps have been loaded yet.
     */
    fun groupedApps(): List<AppCategoryGroup> = buildGroupedApps(_state.value.apps)

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
     *
     * Uses [MutableStateFlow.updateAndGet] so the value passed to [AllowlistRepository.saveAllowlist]
     * is atomically the same state that was committed — not a subsequent read of [_state.value]
     * which could be stale under concurrent calls (e.g. two rapid toggles in quick succession).
     */
    fun toggle(packageName: String) {
        val committed =
            _state.updateAndGet { s ->
                val next =
                    if (packageName in s.allowlist) {
                        s.allowlist - packageName
                    } else {
                        s.allowlist + packageName
                    }
                s.copy(allowlist = next)
            }
        repo.saveAllowlist(committed.allowlist)
    }

    /**
     * Send the current allowlist to the child as a signed policy bundle.
     *
     * Builds a [Policy] from the current allowlist (sorted), calls [sendPolicy], and maps
     * every [SendResult] variant to a fail-closed [ApplyState]:
     *
     * - [SendResult.Sent]           → [ApplyState.Applied] (only success path)
     * - [SendResult.Rejected]       → [ApplyState.Rejected] (child refused the bundle)
     * - [SendResult.TransportFailed]→ [ApplyState.TransportFailed] (signed but not delivered)
     * - [SendResult.NotProvisioned] → [ApplyState.NotProvisioned] (no root key yet)
     * - [SendResult.NotPaired]      → [ApplyState.NotPaired] (no child paired yet)
     *
     * If [sendPolicy] was not injected (null), maps immediately to [ApplyState.NotProvisioned]
     * rather than crashing — contexts that have not wired a sender still compile and
     * show the correct "set up recovery key first" prompt.
     */
    suspend fun apply() {
        if (sendPolicy == null) {
            _state.update { it.copy(applyState = ApplyState.NotProvisioned) }
            return
        }
        _state.update { it.copy(applyState = ApplyState.Sending) }
        val policy = Policy(allowlist = _state.value.allowlist.sorted())
        val result = sendPolicy.invoke(policy)
        val nextApplyState =
            when (result) {
                is SendResult.Sent -> ApplyState.Applied(result.policySeq)
                is SendResult.Rejected -> ApplyState.Rejected(result.reason)
                is SendResult.TransportFailed -> ApplyState.TransportFailed(result.message)
                SendResult.NotProvisioned -> ApplyState.NotProvisioned
                SendResult.NotPaired -> ApplyState.NotPaired
            }
        _state.update { it.copy(applyState = nextApplyState) }
    }

    /** Clear the [ApplyState] banner (e.g. after the parent has acknowledged the result). */
    fun clearApplyState() {
        _state.update { it.copy(applyState = null) }
    }
}

// ---------------------------------------------------------------------------
// Internal grouping helper — pure function, testable in isolation
// ---------------------------------------------------------------------------

/**
 * Group [apps] by category, enforce enum ordering (OTHER, UNKNOWN last), and sort each
 * group's entries by display label (blank labels fall back to packageName).
 *
 * Exposed as an internal top-level function so both [AllowlistEditorViewModel.groupedApps]
 * and the ViewModel grouping unit tests can call it directly without instantiating a
 * full ViewModel.
 */
internal fun buildGroupedApps(apps: List<AppInfo>): List<AppCategoryGroup> =
    apps
        .groupBy { it.category }
        .entries
        .sortedBy { (cat, _) -> cat.ordinal }
        .map { (cat, entries) ->
            AppCategoryGroup(
                category = cat,
                apps = entries.sortedBy { it.label.ifBlank { it.packageName } },
            )
        }

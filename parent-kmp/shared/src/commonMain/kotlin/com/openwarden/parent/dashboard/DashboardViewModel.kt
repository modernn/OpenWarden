package com.openwarden.parent.dashboard

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

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
     * [onlineStatus] may still be [ChildOnlineStatus.OFFLINE_OR_UNKNOWN] if the child
     * is unreachable or reported a stale timestamp — that is an honest, non-error state.
     * [todayUsage] and [blocksData] use sealed types so the UI cannot accidentally
     * display zeros for an unknown state (H3).
     */
    data class Success(
        val onlineStatus: ChildOnlineStatus,
        val todayUsage: TodayUsage,
        val blocksData: BlocksData,
    ) : DashboardUiState()

    /**
     * The repository threw or returned an unrecoverable error.
     * ALWAYS treated as offline/unknown — callers MUST NOT show last-known "online"
     * when in this state.
     */
    data class Error(
        val message: String,
    ) : DashboardUiState()
}

/**
 * Dashboard ViewModel — cross-platform (commonMain), no Android lifecycle dependency.
 *
 * ### Freshness-derived online status (H2)
 *
 * The ONLINE/OFFLINE_OR_UNKNOWN decision is made HERE in the trusted domain layer,
 * not in the repository or network layer.  The decision rule:
 *   - [ChildDashboardSnapshot.reportedAt] is null → OFFLINE_OR_UNKNOWN (fail-closed).
 *   - [ChildDashboardSnapshot.reportedAt] is older than [FRESHNESS_WINDOW] → OFFLINE_OR_UNKNOWN.
 *   - Otherwise → ONLINE.
 *
 * The repository supplies [reportedAt] as the child's self-reported timestamp; it does
 * not make an online/offline assertion itself.  The real `reportedAt` is populated by the
 * HTTP client in issue #20.  The DECISION stays here permanently.
 *
 * ### Refresh-race prevention (MED)
 *
 * [refresh] cancels any in-flight fetch before launching a new one.  This prevents an
 * older result from overwriting a newer one when refresh() is called concurrently.
 * Cancellation leaves the UI in Loading (set at the top of refresh()), which is
 * fail-closed — it never strands the UI in a false-online state.
 *
 * ### Contract contradiction resolution (H1)
 *
 * The repository contract is authoritative: fetchSnapshot() never throws.
 * The try/catch below is a defense-in-depth backstop for any unexpected exception that
 * escapes the repository (e.g., a buggy future impl).  It maps to [DashboardUiState.Error]
 * which the UI displays as OFFLINE_OR_UNKNOWN — fail-closed.
 *
 * State mapping contract:
 * - Repository returns snapshot with fresh reportedAt → [DashboardUiState.Success] with ONLINE
 * - Repository returns snapshot with null/stale reportedAt → [DashboardUiState.Success] with
 *   OFFLINE_OR_UNKNOWN; todayUsage/blocksData carry [TodayUsage.Unknown]/[BlocksData.Unknown]
 * - Repository throws (backstop) → [DashboardUiState.Error]; caller shows child as
 *   OFFLINE_OR_UNKNOWN
 */
class DashboardViewModel(
    private val repository: ChildStateRepository,
    private val scope: CoroutineScope,
    /**
     * Clock used for freshness evaluation. Injected for testability — tests supply a
     * fixed clock so freshness assertions are deterministic without sleeping.
     */
    private val clock: Clock = Clock.System,
    /**
     * A snapshot is considered fresh (ONLINE) if its [ChildDashboardSnapshot.reportedAt]
     * is within this window of [clock.now()].
     *
     * 90 seconds: conservative enough that a missed poll (30s interval) still qualifies,
     * but stale-enough data (>2 missed polls) is correctly flagged offline.
     * Document: if the poll interval changes in #20, revisit this constant.
     */
    private val freshnessWindow: Duration = FRESHNESS_WINDOW,
) {
    private val _uiState = MutableStateFlow<DashboardUiState>(DashboardUiState.Loading)
    val uiState: StateFlow<DashboardUiState> = _uiState.asStateFlow()

    /** Tracks the in-flight fetch job so concurrent refresh() calls cancel the prior one. */
    private var fetchJob: Job? = null

    /**
     * Trigger a refresh.  Cancels any in-flight request before launching a new fetch,
     * preventing older results from overwriting newer ones.
     * Fail-closed: cancellation leaves the UI in Loading (set below) — never in a
     * false-online state.
     */
    fun refresh() {
        fetchJob?.cancel()
        _uiState.value = DashboardUiState.Loading
        fetchJob =
            scope.launch {
                _uiState.value =
                    try {
                        val snapshot = repository.fetchSnapshot()
                        mapSnapshot(snapshot)
                    } catch (e: Exception) {
                        // Defense-in-depth backstop: the repository contract says never throw,
                        // but if an implementation violates that contract we still fail closed.
                        // Never surface last-known-good as live.
                        DashboardUiState.Error(e.message ?: "Unknown error")
                    }
            }
    }

    /**
     * Maps a raw [ChildDashboardSnapshot] from the repository to [DashboardUiState.Success].
     *
     * This is the single location that derives online status from freshness.
     * If [ChildDashboardSnapshot.reportedAt] is null or older than [freshnessWindow],
     * the status is OFFLINE_OR_UNKNOWN — regardless of any flag the repository carries.
     * Fail-closed: uncertainty always maps to OFFLINE_OR_UNKNOWN.
     */
    private fun mapSnapshot(snapshot: ChildDashboardSnapshot): DashboardUiState.Success {
        val now: Instant = clock.now()
        val reportedAt: Instant? = snapshot.reportedAt

        val onlineStatus: ChildOnlineStatus =
            when {
                reportedAt == null -> ChildOnlineStatus.OFFLINE_OR_UNKNOWN

                // Fail-closed on BOTH sides. reportedAt is untrusted, network-sourced data (#20):
                // too old → stale → offline; implausibly in the FUTURE (clock skew, or a spoofed/
                // compromised child clock) is equally untrustworthy and must NOT read ONLINE. A
                // one-sided check let a future timestamp pin ONLINE forever even after the child
                // went dark. Online only inside [now - window, now + window].
                (now - reportedAt) > freshnessWindow -> ChildOnlineStatus.OFFLINE_OR_UNKNOWN

                (reportedAt - now) > freshnessWindow -> ChildOnlineStatus.OFFLINE_OR_UNKNOWN

                else -> ChildOnlineStatus.ONLINE
            }

        // When offline, usage and blocks are unknown — even if the snapshot carried data,
        // stale data is not authoritative and must not be shown as current readings.
        val todayUsage: TodayUsage =
            when (onlineStatus) {
                ChildOnlineStatus.ONLINE -> snapshot.todayUsage
                ChildOnlineStatus.OFFLINE_OR_UNKNOWN -> TodayUsage.Unknown
            }
        val blocksData: BlocksData =
            when (onlineStatus) {
                ChildOnlineStatus.ONLINE -> snapshot.blocksData
                ChildOnlineStatus.OFFLINE_OR_UNKNOWN -> BlocksData.Unknown
            }

        return DashboardUiState.Success(
            onlineStatus = onlineStatus,
            todayUsage = todayUsage,
            blocksData = blocksData,
        )
    }

    companion object {
        /**
         * Freshness window for online-status derivation.
         * A child snapshot is ONLINE only if reportedAt is within this duration of now().
         * 90s = 3× a 30s poll interval — conservative, fail-closed.
         */
        val FRESHNESS_WINDOW: Duration = 90.seconds
    }
}

package com.openwarden.parent.dashboard

/**
 * Data-source seam for the parent dashboard.
 *
 * The REAL implementation (HTTP to child /state and /usage) will be wired
 * in issue #20 once the child endpoints are built.  For now this interface
 * lets the UI and ViewModel compile and be tested against [FakeChildStateRepository].
 *
 * ### Implementation contract (H1 — single canonical owner)
 *
 * The repository is the **authoritative** never-throws boundary:
 *   - MUST return a [ChildDashboardSnapshot] on every call — never throw.
 *   - On any network error, parse failure, or missing data: return a snapshot with
 *     [ChildDashboardSnapshot.reportedAt] = null (absent/stale) and
 *     [TodayUsage.Unknown] / [BlocksData.Unknown] for usage and blocks.
 *   - MUST NOT yield a snapshot whose [ChildDashboardSnapshot.reportedAt] is non-null
 *     unless that timestamp came directly from the child's self-reported payload — never
 *     fabricate a timestamp using the local clock.
 *   - The online/offline trust DECISION is made by the domain/ViewModel layer (not here)
 *     by comparing [ChildDashboardSnapshot.reportedAt] against the freshness window.
 *     This seam only supplies the raw data; it does not assert ONLINE status.
 *   - MUST NOT include any content fields (message text, URL content, photos).
 *   - Local-only: no calls to any external SaaS, analytics, or telemetry service.
 *
 * The [com.openwarden.parent.dashboard.DashboardViewModel] wraps each call in a
 * try/catch as a defense-in-depth backstop — any surprise exception is caught and
 * mapped to a fail-closed [DashboardUiState.Error].  That backstop does NOT replace
 * this contract; the repository should never throw in the first place.
 */
interface ChildStateRepository {
    /**
     * Fetch a fresh dashboard snapshot.
     *
     * Returns a snapshot on every call.  On failure, the snapshot will have
     * [ChildDashboardSnapshot.reportedAt] = null, causing the ViewModel to derive
     * [ChildOnlineStatus.OFFLINE_OR_UNKNOWN].  Never throws.
     */
    suspend fun fetchSnapshot(): ChildDashboardSnapshot
}

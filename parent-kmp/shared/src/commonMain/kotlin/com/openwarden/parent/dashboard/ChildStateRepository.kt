package com.openwarden.parent.dashboard

/**
 * Data-source seam for the parent dashboard.
 *
 * The REAL implementation (HTTP to child /state and /usage) will be wired
 * in issue #20 once the child endpoints are built.  For now this interface
 * lets the UI and ViewModel compile and be tested against [FakeChildStateRepository].
 *
 * Implementation contract:
 * - MUST return [ChildOnlineStatus.OFFLINE_OR_UNKNOWN] on any network error,
 *   parse failure, missing field, or stale data.  Never degrade to optimistic
 *   "online" when the data cannot be confirmed live.
 * - MUST NOT include any content fields (message text, URL content, photos).
 * - Local-only: no calls to any external SaaS, analytics, or telemetry service.
 */
interface ChildStateRepository {
    /**
     * Fetch a fresh dashboard snapshot.
     * Returns a snapshot with [ChildOnlineStatus.OFFLINE_OR_UNKNOWN] on any failure.
     * Never throws.
     */
    suspend fun fetchSnapshot(): ChildDashboardSnapshot
}

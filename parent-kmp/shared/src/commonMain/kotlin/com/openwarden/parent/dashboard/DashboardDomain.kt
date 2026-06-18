package com.openwarden.parent.dashboard

import kotlinx.datetime.Instant

/**
 * Domain model for the parent dashboard.
 *
 * All fields are metadata only — no message text, photo data, URL content, or
 * any other content that would violate the stalkerware boundary (CLAUDE.md §Non-negotiables).
 *
 * Timestamps are ISO-8601 / kotlinx-datetime Instant per the project convention.
 */

// ---------------------------------------------------------------------------
// Child online status
// ---------------------------------------------------------------------------

/**
 * Whether the child device is online (reachable and reporting).
 * UNKNOWN is the fail-closed default — used whenever data is stale, missing, or
 * errored.  Never show a last-known ONLINE as if live.
 */
enum class ChildOnlineStatus {
    /** Actively connected; /state returned a fresh response within the poll window. */
    ONLINE,

    /** Connection failed, stale, or data absent.  Displayed honestly as offline/unknown. */
    OFFLINE_OR_UNKNOWN,
}

// ---------------------------------------------------------------------------
// Usage summary
// ---------------------------------------------------------------------------

/**
 * Per-app usage entry — metadata only.
 * [packageName] is the Android package ID (e.g. "com.android.chrome").
 * [label] is the human-readable app name if available; fall back to packageName.
 * [foregroundMs] is total foreground time in milliseconds — no content, no
 * in-app screen captures, no search history.
 */
data class AppUsageSummary(
    val packageName: String,
    val label: String,
    val foregroundMs: Long,
)

/**
 * Today's usage roll-up.
 * [totalForegroundMs] is the sum across all apps for the current day.
 * [perApp] is sorted descending by foreground time.
 */
data class TodayUsage(
    val totalForegroundMs: Long,
    val perApp: List<AppUsageSummary>,
) {
    companion object {
        val EMPTY = TodayUsage(totalForegroundMs = 0L, perApp = emptyList())
    }
}

// ---------------------------------------------------------------------------
// Blocked attempts
// ---------------------------------------------------------------------------

/**
 * A single blocked-access attempt — metadata only.
 *
 * NEVER include:
 *   - The URL or search query that was blocked
 *   - Message text, photo, audio
 *   - Any content-level detail
 *
 * Permitted metadata fields: which app/category was blocked, when, and
 * a running count.
 */
data class BlockedAttempt(
    /** Android package name of the app that triggered the block. */
    val packageName: String,
    /** Human-readable label for the blocked app (may be null if unknown). */
    val appLabel: String?,
    /** High-level category (e.g. "ENTERTAINMENT", "SOCIAL"). No content. */
    val category: String?,
    /** ISO-8601 timestamp when the block occurred. */
    val blockedAt: Instant,
    /** Running count of blocks from this same (package, category) pair today. */
    val countToday: Int,
)

// ---------------------------------------------------------------------------
// Snapshot returned by the repository
// ---------------------------------------------------------------------------

/**
 * A complete snapshot of child state for the dashboard.
 * Assembled by [ChildStateRepository.fetchSnapshot].
 */
data class ChildDashboardSnapshot(
    val onlineStatus: ChildOnlineStatus,
    val todayUsage: TodayUsage,
    val recentBlocks: List<BlockedAttempt>,
)

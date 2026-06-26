package com.openwarden.parent.dashboard

import kotlinx.datetime.Instant

/*
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
// App category — closed enum to prevent content leaking via free-form strings
// ---------------------------------------------------------------------------

/**
 * High-level category for a blocked app/attempt.
 *
 * IMPORTANT: This is a metadata-only classification. It MUST NOT encode or hint
 * at specific URLs, search terms, message content, or any other content-layer detail.
 *
 * The #20 HTTP-client parser MUST map incoming category strings through this enum's
 * [fromRaw] chokepoint. Unknown values map to [OTHER] — they never pass through
 * as free-form strings, which would risk embedding content in a field labeled
 * "category".
 */
enum class AppCategory(
    val displayName: String,
) {
    SOCIAL("Social"),
    ENTERTAINMENT("Entertainment"),
    GAMING("Gaming"),
    COMMUNICATION("Communication"),
    PRODUCTIVITY("Productivity"),
    EDUCATION("Education"),
    SHOPPING("Shopping"),
    NEWS("News"),
    UTILITIES("Utilities"),

    /** Catch-all for any value not in the above list. */
    OTHER("Other"),

    /** Value was absent/null in the source data. */
    UNKNOWN("Unknown"),
    ;

    companion object {
        /**
         * Parse boundary chokepoint — ALL incoming category strings must pass through here.
         * Unknown/null strings map to [OTHER]; null input maps to [UNKNOWN].
         * This ensures the domain never holds an arbitrary free-form string in a metadata field.
         */
        fun fromRaw(raw: String?): AppCategory {
            if (raw == null) return UNKNOWN
            return entries.firstOrNull { it.name.equals(raw.trim(), ignoreCase = true) } ?: OTHER
        }
    }
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
 * Today's usage roll-up — distinguishes genuinely-zero from data-unavailable.
 *
 * Using a sealed type rather than a nullable or zero-value avoids the H3 bug where
 * OFFLINE snapshots and genuinely-zero usage are indistinguishable at the UI layer.
 *
 * - [Known] means the child reported usage successfully; [totalForegroundMs] may be 0
 *   (child was online but didn't use the device).
 * - [Unknown] means data is unavailable (offline, stale, or error); the UI MUST display
 *   a clearly indeterminate state ("—" / "Usage unavailable while offline") rather than "0m".
 */
sealed class TodayUsage {
    /**
     * Usage data was successfully obtained.
     * [totalForegroundMs] may be 0 for a genuinely idle device — that is a real reading.
     */
    data class Known(
        val totalForegroundMs: Long,
        val perApp: List<AppUsageSummary>,
    ) : TodayUsage()

    /**
     * Usage data cannot be determined (child offline, stale snapshot, parse error, etc.).
     * Rendered as "—" or "Usage unavailable while offline" — NEVER as "0m".
     */
    data object Unknown : TodayUsage()
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
    /**
     * High-level category.  Constrained to [AppCategory] enum — NEVER a free-form String.
     * The #20 HTTP-client parser MUST route all incoming category strings through
     * [AppCategory.fromRaw] before populating this field.
     */
    val category: AppCategory,
    /** ISO-8601 timestamp when the block occurred. */
    val blockedAt: Instant,
    /** Running count of blocks from this same (package, category) pair today. */
    val countToday: Int,
)

/**
 * Blocked-attempt list availability — distinguishes empty from unavailable.
 *
 * - [Known] carries the list (which may be empty for a child with no blocked attempts).
 * - [Unknown] means data is unavailable; displayed as "Blocked-attempt data unavailable while
 *   offline" rather than "No blocked attempts today."
 */
sealed class BlocksData {
    /** Blocked-attempt data was retrieved; [attempts] may be an empty list. */
    data class Known(
        val attempts: List<BlockedAttempt>,
    ) : BlocksData()

    /**
     * Blocked-attempt data cannot be determined (child offline, stale, or error).
     * NEVER render this as "No blocked attempts today."
     */
    data object Unknown : BlocksData()
}

// ---------------------------------------------------------------------------
// Snapshot returned by the repository
// ---------------------------------------------------------------------------

/**
 * A complete snapshot of child state for the dashboard.
 * Assembled by [ChildStateRepository.fetchSnapshot].
 *
 * ### Freshness / online-status derivation (H2)
 *
 * The repository is responsible only for supplying [reportedAt] as faithfully as
 * possible from the child's self-reported timestamp.  The TRUST DECISION — whether
 * [reportedAt] is fresh enough to qualify as [ChildOnlineStatus.ONLINE] — lives
 * permanently in [com.openwarden.parent.dashboard.DashboardViewModel].
 *
 * A repository MUST NOT yield a snapshot that the domain layer could read as ONLINE
 * without a fresh [reportedAt].  Specifically:
 *   - On network failure or parse error, return a snapshot with [reportedAt] = null.
 *   - Never fabricate a [reportedAt] timestamp (do not use the current clock here).
 *
 * The real `reportedAt` value is populated by the HTTP client built in issue #20.
 *
 * [todayUsage] and [blocksData] use sealed types so the UI cannot accidentally
 * display zeros when the real state is "data unavailable" (H3).
 */
data class ChildDashboardSnapshot(
    /**
     * Timestamp the child device last self-reported its state.
     * Null if the report was absent, failed, or could not be parsed.
     * Freshness is evaluated by the ViewModel/domain layer — not here.
     */
    val reportedAt: Instant?,
    /** Today's screen-time data, or [TodayUsage.Unknown] if unavailable. */
    val todayUsage: TodayUsage,
    /** Recent blocked attempts, or [BlocksData.Unknown] if unavailable. */
    val blocksData: BlocksData,
)

package com.openwarden.parent.android.demo

import com.openwarden.parent.dashboard.BlocksData
import com.openwarden.parent.dashboard.TodayUsage
import kotlinx.datetime.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [ApiChildStateRepository.mapToSnapshot] — the pure child-wire → dashboard-snapshot
 * mapping (#20). No network: the mapping is exercised directly so it runs in milliseconds and the
 * fail-closed / metadata-only / no-fabricated-timestamp rules are asserted deterministically.
 */
class ApiChildStateRepositoryTest {
    private fun state(reportedAt: Long) =
        ChildStateResponse(
            version = "0.1.0-dev",
            policyVersion = "none",
            policyNotAfter = "n/a",
            paired = false,
            isLocked = false,
            reportedAt = reportedAt,
        )

    @Test
    fun reportedAtPositive_mapsToInstant() {
        val snap = ApiChildStateRepository.mapToSnapshot(state(1_700_000_000_000L), emptyList())
        assertEquals(Instant.fromEpochMilliseconds(1_700_000_000_000L), snap.reportedAt)
    }

    @Test
    fun reportedAtZero_mapsToNull_soNeverFalselyOnline() {
        // 0/absent must become null reportedAt → the ViewModel derives OFFLINE_OR_UNKNOWN.
        val snap = ApiChildStateRepository.mapToSnapshot(state(0L), emptyList())
        assertNull(snap.reportedAt)
    }

    @Test
    fun nullUsage_mapsToUnknown_notZero() {
        // A failed /usage fetch is Unknown, never a fabricated "0 minutes".
        val snap = ApiChildStateRepository.mapToSnapshot(state(1L), usage = null)
        assertTrue(snap.todayUsage is TodayUsage.Unknown)
    }

    @Test
    fun usage_convertsMinutesToMs_sumsTotal_andFallsBackLabel() {
        val usage =
            listOf(
                AppUsageEntry(packageName = "com.android.chrome", foregroundMinutes = 10, label = "Chrome"),
                AppUsageEntry(packageName = "com.roblox.client", foregroundMinutes = 5, label = ""),
            )
        val snap = ApiChildStateRepository.mapToSnapshot(state(1L), usage)
        val known = snap.todayUsage as TodayUsage.Known
        assertEquals(15 * 60_000L, known.totalForegroundMs)
        assertEquals(10 * 60_000L, known.perApp[0].foregroundMs)
        assertEquals("Chrome", known.perApp[0].label)
        // Blank label falls back to the package name (never empty in the UI).
        assertEquals("com.roblox.client", known.perApp[1].label)
    }

    @Test
    fun blocksData_alwaysUnknown_noBlocksEndpointYet() {
        val snap = ApiChildStateRepository.mapToSnapshot(state(1L), emptyList())
        assertTrue(snap.blocksData is BlocksData.Unknown)
    }

    @Test
    fun offline_isFullyUnknown() {
        val snap = ApiChildStateRepository.offline()
        assertNull(snap.reportedAt)
        assertTrue(snap.todayUsage is TodayUsage.Unknown)
        assertTrue(snap.blocksData is BlocksData.Unknown)
    }
}

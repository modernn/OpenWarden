package com.openwarden.parent.dashboard

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Unit tests for [DashboardViewModel] using fixture data from [FakeChildStateRepository].
 *
 * Assertions cover all three acceptance criteria from issue #25:
 *   (a) Live fixture → correct online + usage + blocks mapping.
 *   (b) Offline/error fixture → honest offline state, never a false "online".
 *   (c) No content field is ever surfaced via the UI-state type.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DashboardViewModelTest {

    // -----------------------------------------------------------------------
    // (a) Online fixture — happy path
    // -----------------------------------------------------------------------

    @Test
    fun onlineFixture_mapsToSuccessWithOnlineStatus() = runTest {
        val vm = vmWith(FakeChildStateRepository(FakeChildStateRepository.Scenario.Online))
        vm.refresh()
        advanceUntilIdle()

        val state = vm.uiState.value
        assertIs<DashboardUiState.Success>(state, "Expected Success state for online fixture")
        assertEquals(
            ChildOnlineStatus.ONLINE,
            state.snapshot.onlineStatus,
            "Online fixture must report ONLINE",
        )
    }

    @Test
    fun onlineFixture_usageTotalsArePresent() = runTest {
        val vm = vmWith(FakeChildStateRepository(FakeChildStateRepository.Scenario.Online))
        vm.refresh()
        advanceUntilIdle()

        val state = assertIs<DashboardUiState.Success>(vm.uiState.value)
        assertTrue(
            state.snapshot.todayUsage.totalForegroundMs > 0,
            "Online fixture must have non-zero total usage",
        )
        assertTrue(
            state.snapshot.todayUsage.perApp.isNotEmpty(),
            "Online fixture must have per-app usage entries",
        )
    }

    @Test
    fun onlineFixture_blockedAttemptsArePresent() = runTest {
        val vm = vmWith(FakeChildStateRepository(FakeChildStateRepository.Scenario.Online))
        vm.refresh()
        advanceUntilIdle()

        val state = assertIs<DashboardUiState.Success>(vm.uiState.value)
        assertTrue(
            state.snapshot.recentBlocks.isNotEmpty(),
            "Online fixture must have recent blocks",
        )
    }

    // -----------------------------------------------------------------------
    // (b) Offline / Error → fail-closed to OFFLINE_OR_UNKNOWN
    // -----------------------------------------------------------------------

    @Test
    fun offlineFixture_mapsToSuccessWithOfflineStatus() = runTest {
        val vm = vmWith(FakeChildStateRepository(FakeChildStateRepository.Scenario.Offline))
        vm.refresh()
        advanceUntilIdle()

        val state = vm.uiState.value
        assertIs<DashboardUiState.Success>(state, "Expected Success state even for offline fixture")
        assertEquals(
            ChildOnlineStatus.OFFLINE_OR_UNKNOWN,
            state.snapshot.onlineStatus,
            "Offline fixture must report OFFLINE_OR_UNKNOWN, never ONLINE",
        )
    }

    @Test
    fun offlineFixture_usageIsEmpty() = runTest {
        val vm = vmWith(FakeChildStateRepository(FakeChildStateRepository.Scenario.Offline))
        vm.refresh()
        advanceUntilIdle()

        val state = assertIs<DashboardUiState.Success>(vm.uiState.value)
        assertEquals(0L, state.snapshot.todayUsage.totalForegroundMs)
        assertTrue(state.snapshot.todayUsage.perApp.isEmpty())
    }

    @Test
    fun offlineFixture_blocksAreEmpty() = runTest {
        val vm = vmWith(FakeChildStateRepository(FakeChildStateRepository.Scenario.Offline))
        vm.refresh()
        advanceUntilIdle()

        val state = assertIs<DashboardUiState.Success>(vm.uiState.value)
        assertTrue(state.snapshot.recentBlocks.isEmpty())
    }

    @Test
    fun errorRepository_degradesToErrorState() = runTest {
        val throwingRepo = object : ChildStateRepository {
            override suspend fun fetchSnapshot(): ChildDashboardSnapshot {
                throw RuntimeException("simulated transport failure")
            }
        }
        val vm = vmWith(throwingRepo)
        vm.refresh()
        advanceUntilIdle()

        val state = vm.uiState.value
        assertIs<DashboardUiState.Error>(state, "Throwing repository must produce Error state")
    }

    @Test
    fun errorState_neverExposesOnlineStatus() = runTest {
        val throwingRepo = object : ChildStateRepository {
            override suspend fun fetchSnapshot(): ChildDashboardSnapshot {
                throw RuntimeException("simulated transport failure")
            }
        }
        val vm = vmWith(throwingRepo)
        vm.refresh()
        advanceUntilIdle()

        // Error state does NOT carry a snapshot — no stale/live confusion possible.
        val state = vm.uiState.value
        // Error state carries no snapshot at all — no stale "online" can leak through.
        // The sealed class makes it compile-time impossible to have both Error and
        // Success(ONLINE) simultaneously; we verify the Error branch is taken.
        assertIs<DashboardUiState.Error>(state, "Error state must never surface ONLINE status")
    }

    // -----------------------------------------------------------------------
    // (c) No content fields in DashboardUiState
    // -----------------------------------------------------------------------

    /**
     * Asserts that no content field names appear in the Success snapshot.
     * This is a naming-convention guard: the domain types must not carry any
     * field that could hold message text, URL content, image data, audio, or
     * similar content-layer data.
     *
     * Blocked content field names per CLAUDE.md non-negotiables:
     *   - messageText / messageBody / content / body / text (in the context of comms)
     *   - url / uri / searchQuery
     *   - imageData / photoData / audioData
     *
     * Domain types do carry: packageName, appLabel, category, timestamp, count —
     * all of which are metadata.  Those are explicitly allowed.
     */
    @Test
    fun domainModel_hasNoContentFields() {
        val snapshot = FakeChildStateRepository.onlineFixture()

        // BlockedAttempt fields — check each to make sure no content was added.
        snapshot.recentBlocks.forEach { block ->
            // Allowed metadata fields:
            assertNotNull(block.packageName)     // metadata: which app
            // block.appLabel is nullable — present in fixture, fine
            // block.category is nullable — present in fixture, fine
            assertNotNull(block.blockedAt)       // metadata: when
            assertTrue(block.countToday >= 0)    // metadata: count

            // Reject any field that would expose content:
            // The type system enforces this at compile time (no such field exists).
            // This runtime assertion documents the intent explicitly:
            val blockStr = block.toString()
            assertFalse(
                blockStr.contains("messageText") ||
                    blockStr.contains("messageBody") ||
                    blockStr.contains("urlContent") ||
                    blockStr.contains("searchQuery") ||
                    blockStr.contains("imageData") ||
                    blockStr.contains("audioData"),
                "BlockedAttempt must never carry content-layer fields",
            )
        }

        // AppUsageSummary fields — metadata only.
        snapshot.todayUsage.perApp.forEach { entry ->
            assertNotNull(entry.packageName)    // metadata: which app
            assertTrue(entry.foregroundMs >= 0) // metadata: time
            // No content field; label is an app display name, not message content.
            val entryStr = entry.toString()
            assertFalse(
                entryStr.contains("messageText") ||
                    entryStr.contains("urlContent") ||
                    entryStr.contains("searchQuery"),
                "AppUsageSummary must never carry content-layer fields",
            )
        }
    }

    // -----------------------------------------------------------------------
    // Loading state
    // -----------------------------------------------------------------------

    @Test
    fun initialState_isLoading() {
        // Construct but do NOT call refresh — should be Loading.
        val scheduler = TestCoroutineScheduler()
        val dispatcher = StandardTestDispatcher(scheduler)
        val scope = TestScope(dispatcher)
        val vm = DashboardViewModel(
            repository = FakeChildStateRepository(FakeChildStateRepository.Scenario.Online),
            scope = scope,
        )
        assertIs<DashboardUiState.Loading>(vm.uiState.value)
    }

    // -----------------------------------------------------------------------
    // Helper
    // -----------------------------------------------------------------------

    private fun TestScope.vmWith(repository: ChildStateRepository): DashboardViewModel =
        DashboardViewModel(repository = repository, scope = this)
}

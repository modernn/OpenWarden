package com.openwarden.parent.android.policy

import com.openwarden.parent.policy.AppInfo
import com.openwarden.parent.policy.FetchAppsResult
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [DemoAllowlistRepository].
 *
 * The fetch path is exercised via the [internal constructor][DemoAllowlistRepository]
 * that accepts a [FetchAppsResult] lambda, so no real network calls are made and
 * tests run in milliseconds.
 */
class DemoAllowlistRepositoryTest {
    // ---------------------------------------------------------------------------
    // Allowlist persistence
    // ---------------------------------------------------------------------------

    @Test
    fun initialLoad_returnsEmptySet() {
        val repo = DemoAllowlistRepository { FetchAppsResult.Error("stub") }
        assertTrue(repo.loadAllowlist().isEmpty())
    }

    @Test
    fun saveAndLoad_roundTrips() {
        val repo = DemoAllowlistRepository { FetchAppsResult.Error("stub") }
        repo.saveAllowlist(setOf("com.a", "com.b"))
        assertEquals(setOf("com.a", "com.b"), repo.loadAllowlist())
    }

    @Test
    fun secondSave_overwritesPrevious() {
        val repo = DemoAllowlistRepository { FetchAppsResult.Error("stub") }
        repo.saveAllowlist(setOf("com.a"))
        repo.saveAllowlist(setOf("com.b", "com.c"))
        assertEquals(setOf("com.b", "com.c"), repo.loadAllowlist())
    }

    @Test
    fun saveEmpty_clearsPersistedSet() {
        val repo = DemoAllowlistRepository { FetchAppsResult.Error("stub") }
        repo.saveAllowlist(setOf("com.a"))
        repo.saveAllowlist(emptySet())
        assertTrue(repo.loadAllowlist().isEmpty())
    }

    // ---------------------------------------------------------------------------
    // Fetch — fail-closed via injected stub
    // ---------------------------------------------------------------------------

    @Test
    fun fetchInstalledApps_onError_returnsFetchAppsResultError() =
        runTest {
            val repo = DemoAllowlistRepository { FetchAppsResult.Error("Connection refused") }
            val result = repo.fetchInstalledApps()
            assertTrue(result is FetchAppsResult.Error)
            assertEquals("Connection refused", (result as FetchAppsResult.Error).message)
        }

    @Test
    fun fetchInstalledApps_onSuccess_mapsEntriesToAppInfo() =
        runTest {
            val expected =
                listOf(
                    AppInfo("com.example.one", "One"),
                    AppInfo("com.example.two", "Two"),
                )
            val repo = DemoAllowlistRepository { FetchAppsResult.Success(expected) }
            val result = repo.fetchInstalledApps()
            assertTrue(result is FetchAppsResult.Success)
            assertEquals(expected, (result as FetchAppsResult.Success).apps)
        }

    @Test
    fun fetchInstalledApps_onEmptySuccess_returnsSuccessWithEmptyList() =
        runTest {
            // An empty list from the child is a valid (though ambiguous) response — repo
            // passes it through; the ViewModel / UI layer is responsible for the warning.
            val repo = DemoAllowlistRepository { FetchAppsResult.Success(emptyList()) }
            val result = repo.fetchInstalledApps()
            assertTrue(result is FetchAppsResult.Success)
            assertTrue((result as FetchAppsResult.Success).apps.isEmpty())
        }
}

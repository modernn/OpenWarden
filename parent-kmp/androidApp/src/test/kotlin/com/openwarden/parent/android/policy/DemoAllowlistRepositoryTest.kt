package com.openwarden.parent.android.policy

import com.openwarden.parent.policy.FetchAppsResult
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [DemoAllowlistRepository] — allowlist persistence path.
 *
 * The [com.openwarden.parent.android.demo.ChildApiClient] is internal to the demo
 * package, so the fetch path is exercised via the interface stub in
 * [com.openwarden.parent.policy.AllowlistEditorViewModelTest] in commonTest.
 * These tests focus on the persistence contract that DemoAllowlistRepository owns.
 */
class DemoAllowlistRepositoryTest {

    @Test
    fun initialLoad_returnsEmptySet() {
        val repo = DemoAllowlistRepository()
        assertTrue(repo.loadAllowlist().isEmpty())
    }

    @Test
    fun saveAndLoad_roundTrips() {
        val repo = DemoAllowlistRepository()
        repo.saveAllowlist(setOf("com.a", "com.b"))
        assertEquals(setOf("com.a", "com.b"), repo.loadAllowlist())
    }

    @Test
    fun secondSave_overwritesPrevious() {
        val repo = DemoAllowlistRepository()
        repo.saveAllowlist(setOf("com.a"))
        repo.saveAllowlist(setOf("com.b", "com.c"))
        assertEquals(setOf("com.b", "com.c"), repo.loadAllowlist())
    }

    @Test
    fun saveEmpty_clearsPersistedSet() {
        val repo = DemoAllowlistRepository()
        repo.saveAllowlist(setOf("com.a"))
        repo.saveAllowlist(emptySet())
        assertTrue(repo.loadAllowlist().isEmpty())
    }

    /**
     * When the child is unreachable (as in a build without the real child running),
     * fetchInstalledApps MUST return [FetchAppsResult.Error], never a silent empty success.
     *
     * This test runs against the real DemoAllowlistRepository (which calls the real
     * ChildApiClient against a non-existent server) to verify the fail-closed mapping
     * end-to-end at the Android layer.
     */
    @Test
    fun fetchInstalledApps_whenChildUnreachable_returnsError() = runTest {
        // Real DemoAllowlistRepository pointing at the demo URL (no server running in unit test).
        val repo = DemoAllowlistRepository()
        val result = repo.fetchInstalledApps()
        // Must be Error, never Success (fail-closed).
        assertTrue(
            "Expected FetchAppsResult.Error when child is unreachable, got $result",
            result is FetchAppsResult.Error,
        )
        val msg = (result as FetchAppsResult.Error).message
        assertTrue("Error message must not be blank", msg.isNotBlank())
    }
}

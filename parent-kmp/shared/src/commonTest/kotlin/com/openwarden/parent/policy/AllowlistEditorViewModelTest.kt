package com.openwarden.parent.policy

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

// ---------------------------------------------------------------------------
// Fake repository helpers
// ---------------------------------------------------------------------------

private class FakeAllowlistRepository(
    private val fetchResult: FetchAppsResult,
    private val initialAllowlist: Set<String> = emptySet(),
) : AllowlistRepository {
    var savedAllowlist: Set<String> = initialAllowlist
        private set

    override suspend fun fetchInstalledApps(): FetchAppsResult = fetchResult

    override fun saveAllowlist(allowlist: Set<String>) {
        savedAllowlist = allowlist
    }

    override fun loadAllowlist(): Set<String> = savedAllowlist
}

private val sampleApps =
    listOf(
        AppInfo("com.example.alpha", "Alpha"),
        AppInfo("com.example.beta", "Beta"),
        AppInfo("com.example.gamma", "Gamma"),
    )

// ---------------------------------------------------------------------------
// Tests
// ---------------------------------------------------------------------------

class AllowlistEditorViewModelTest {
    // --- load: success path ---

    @Test
    fun loadSuccess_populatesAppsAndRestoresSavedAllowlist() =
        runTest {
            val repo =
                FakeAllowlistRepository(
                    fetchResult = FetchAppsResult.Success(sampleApps),
                    initialAllowlist = setOf("com.example.alpha"),
                )
            val vm = AllowlistEditorViewModel(repo)

            vm.load()

            val s = vm.state.value
            assertFalse(s.loading)
            assertNull(s.errorMessage)
            assertEquals(sampleApps, s.apps)
            assertEquals(setOf("com.example.alpha"), s.allowlist)
        }

    @Test
    fun loadSuccess_emptyList_rendersEmptyAppsNoError() =
        runTest {
            val repo = FakeAllowlistRepository(FetchAppsResult.Success(emptyList()))
            val vm = AllowlistEditorViewModel(repo)

            vm.load()

            val s = vm.state.value
            assertFalse(s.loading)
            assertNull(s.errorMessage)
            assertTrue(s.apps.isEmpty())
        }

    // --- load: error path (fail-closed) ---

    @Test
    fun loadError_setsErrorMessage_doesNotWipeAllowlist() =
        runTest {
            val repo =
                FakeAllowlistRepository(
                    fetchResult = FetchAppsResult.Error("Connection refused"),
                    initialAllowlist = setOf("com.example.alpha"),
                )
            val vm = AllowlistEditorViewModel(repo)

            vm.load()

            val s = vm.state.value
            assertFalse(s.loading)
            assertNotNull(s.errorMessage)
            assertEquals("Connection refused", s.errorMessage)
            // Allowlist MUST be preserved on error — fail-closed.
            assertEquals(setOf("com.example.alpha"), s.allowlist)
            assertTrue(s.apps.isEmpty())
        }

    @Test
    fun loadError_emptyInitialAllowlist_allowlistRemainsEmpty() =
        runTest {
            val repo = FakeAllowlistRepository(FetchAppsResult.Error("Timeout"))
            val vm = AllowlistEditorViewModel(repo)

            vm.load()

            val s = vm.state.value
            assertNotNull(s.errorMessage)
            assertTrue(s.allowlist.isEmpty(), "Allowlist must stay empty on error with no prior save")
        }

    // --- toggle ---

    @Test
    fun toggle_addsPackageThenRemovesOnSecondCall() =
        runTest {
            val repo = FakeAllowlistRepository(FetchAppsResult.Success(sampleApps))
            val vm = AllowlistEditorViewModel(repo)
            vm.load()

            vm.toggle("com.example.beta")
            assertTrue("com.example.beta" in vm.state.value.allowlist)

            vm.toggle("com.example.beta")
            assertFalse("com.example.beta" in vm.state.value.allowlist)
        }

    @Test
    fun toggle_persistsAllowlistToRepo() =
        runTest {
            val repo = FakeAllowlistRepository(FetchAppsResult.Success(sampleApps))
            val vm = AllowlistEditorViewModel(repo)
            vm.load()

            vm.toggle("com.example.alpha")
            vm.toggle("com.example.gamma")

            assertEquals(setOf("com.example.alpha", "com.example.gamma"), repo.savedAllowlist)
        }

    @Test
    fun toggle_multiplePackages_allTrackedIndependently() =
        runTest {
            val repo = FakeAllowlistRepository(FetchAppsResult.Success(sampleApps))
            val vm = AllowlistEditorViewModel(repo)
            vm.load()

            vm.toggle("com.example.alpha")
            vm.toggle("com.example.beta")

            assertTrue("com.example.alpha" in vm.state.value.allowlist)
            assertTrue("com.example.beta" in vm.state.value.allowlist)
            assertFalse("com.example.gamma" in vm.state.value.allowlist)
        }

    // --- currentAllowlist ---

    @Test
    fun currentAllowlist_reflectsLiveState() =
        runTest {
            val repo = FakeAllowlistRepository(FetchAppsResult.Success(sampleApps))
            val vm = AllowlistEditorViewModel(repo)
            vm.load()

            vm.toggle("com.example.alpha")

            assertEquals(setOf("com.example.alpha"), vm.currentAllowlist())
        }

    // --- toggle with pre-existing saved allowlist (regression for stale-read bug) ---

    /**
     * Regression: toggle() must save the committed state, not a subsequent read of _state.value.
     * This test starts with a non-empty persisted allowlist and removes a package via toggle,
     * then verifies the save received the correct reduced set — not the stale pre-toggle set.
     */
    @Test
    fun toggle_removesFromPreExistingAllowlist_saveReceivesReducedSet() =
        runTest {
            val repo =
                FakeAllowlistRepository(
                    fetchResult = FetchAppsResult.Success(sampleApps),
                    initialAllowlist = setOf("com.example.alpha", "com.example.gamma"),
                )
            val vm = AllowlistEditorViewModel(repo)
            vm.load()

            // Remove alpha from the pre-loaded allowlist.
            vm.toggle("com.example.alpha")

            assertFalse("com.example.alpha" in vm.state.value.allowlist)
            assertTrue("com.example.gamma" in vm.state.value.allowlist)
            // The save must have received the post-toggle state, not the stale pre-toggle state.
            assertEquals(setOf("com.example.gamma"), repo.savedAllowlist)
        }

    @Test
    fun toggle_removesLastEntry_saveReceivesEmptySet() =
        runTest {
            val repo =
                FakeAllowlistRepository(
                    fetchResult = FetchAppsResult.Success(sampleApps),
                    initialAllowlist = setOf("com.example.alpha"),
                )
            val vm = AllowlistEditorViewModel(repo)
            vm.load()

            vm.toggle("com.example.alpha")

            assertTrue(
                vm.state.value.allowlist
                    .isEmpty(),
            )
            assertTrue(repo.savedAllowlist.isEmpty())
        }

    // --- toProtoPolicy integration ---

    @Test
    fun allowlistMatchesProtoPolicy_sorted() =
        runTest {
            val repo = FakeAllowlistRepository(FetchAppsResult.Success(sampleApps))
            val vm = AllowlistEditorViewModel(repo)
            vm.load()

            vm.toggle("com.example.gamma")
            vm.toggle("com.example.alpha")

            // PolicyEditor lives in the same package; verify allowlist is correctly assembled.
            val editor =
                com.openwarden.parent.policy
                    .PolicyEditor()
            editor.setInstalledApps(vm.state.value.apps)
            vm.state.value.allowlist
                .forEach { editor.toggleAllowlist(it) }

            val proto = editor.toProtoPolicy()
            assertEquals(listOf("com.example.alpha", "com.example.gamma"), proto.allowlist)
        }
}

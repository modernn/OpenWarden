package com.openwarden.parent.policy

import com.openwarden.parent.dashboard.AppCategory
import com.openwarden.proto.Policy
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

// ---------------------------------------------------------------------------
// Shared fake repo
// ---------------------------------------------------------------------------

private class FakeRepo(
    private val apps: List<AppInfo> = emptyList(),
) : AllowlistRepository {
    override suspend fun fetchInstalledApps(): FetchAppsResult = FetchAppsResult.Success(apps)

    override fun saveAllowlist(allowlist: Set<String>) {}

    override fun loadAllowlist(): Set<String> = emptySet()
}

// ---------------------------------------------------------------------------
// Part A — buildGroupedApps grouping + ordering tests
// ---------------------------------------------------------------------------

/**
 * Tests for [buildGroupedApps] (the grouping helper) and [AllowlistEditorViewModel.groupedApps].
 *
 * Invariants:
 *  1. Groups ordered by [AppCategory] enum ordinal; OTHER and UNKNOWN are last.
 *  2. Apps within each group sorted by label ascending; blank label falls back to packageName.
 *  3. Categories with no apps produce no group entry.
 */
class AllowlistEditorGroupingTest {
    private fun app(
        pkg: String,
        label: String,
        category: AppCategory,
    ) = AppInfo(packageName = pkg, label = label, category = category)

    @Test
    fun groupsAreInEnumOrdinalOrder() {
        // SOCIAL(0), GAMING(2), EDUCATION(5)
        val apps =
            listOf(
                app("com.game.z", "Z Game", AppCategory.GAMING),
                app("com.social.a", "A Social", AppCategory.SOCIAL),
                app("com.edu.m", "M Edu", AppCategory.EDUCATION),
            )
        val groups = buildGroupedApps(apps)
        assertEquals(3, groups.size)
        assertEquals(AppCategory.SOCIAL, groups[0].category)
        assertEquals(AppCategory.GAMING, groups[1].category)
        assertEquals(AppCategory.EDUCATION, groups[2].category)
    }

    @Test
    fun otherAndUnknownAreLast() {
        val apps =
            listOf(
                app("com.other.x", "X Other", AppCategory.OTHER),
                app("com.social.a", "A Social", AppCategory.SOCIAL),
                app("com.unknown.y", "Y Unknown", AppCategory.UNKNOWN),
                app("com.game.z", "Z Game", AppCategory.GAMING),
            )
        val groups = buildGroupedApps(apps)
        assertEquals(4, groups.size)
        assertEquals(AppCategory.SOCIAL, groups[0].category)
        assertEquals(AppCategory.GAMING, groups[1].category)
        assertEquals(AppCategory.OTHER, groups[2].category)
        assertEquals(AppCategory.UNKNOWN, groups[3].category)
    }

    @Test
    fun appsWithinGroupAreSortedByLabelAscending() {
        val apps =
            listOf(
                app("com.g.z", "Zap", AppCategory.GAMING),
                app("com.g.a", "Alpha", AppCategory.GAMING),
                app("com.g.m", "Mango", AppCategory.GAMING),
            )
        val groups = buildGroupedApps(apps)
        assertEquals(1, groups.size)
        assertEquals(listOf("Alpha", "Mango", "Zap"), groups[0].apps.map { it.label })
    }

    @Test
    fun blankLabelFallsBackToPackageNameForSort() {
        val apps =
            listOf(
                app("com.g.zzz", "", AppCategory.GAMING),
                app("com.g.aaa", "", AppCategory.GAMING),
                app("com.g.mmm", "Mango", AppCategory.GAMING),
            )
        val groups = buildGroupedApps(apps)
        assertEquals(1, groups.size)
        // Sort keys (String natural order, case-sensitive): "Mango" < "com.g.aaa" < "com.g.zzz"
        // because 'M'(77) < 'c'(99) in ASCII.
        val keys = groups[0].apps.map { it.label.ifBlank { it.packageName } }
        assertEquals(listOf("Mango", "com.g.aaa", "com.g.zzz"), keys)
    }

    @Test
    fun emptyAppListProducesNoGroups() {
        assertTrue(buildGroupedApps(emptyList()).isEmpty())
    }

    @Test
    fun categoriesWithNoAppsProduceNoGroup() {
        val apps =
            listOf(
                app("com.social.a", "A", AppCategory.SOCIAL),
                app("com.social.b", "B", AppCategory.SOCIAL),
            )
        val groups = buildGroupedApps(apps)
        assertEquals(1, groups.size)
        assertEquals(AppCategory.SOCIAL, groups[0].category)
    }

    @Test
    fun allCategoriesPresent_groupsRespectFullEnumOrder() {
        val apps =
            AppCategory.entries.mapIndexed { i, cat ->
                app("com.pkg.$i", "App $i", cat)
            }
        val groups = buildGroupedApps(apps)
        assertEquals(AppCategory.entries.size, groups.size)
        groups.forEachIndexed { i, group ->
            assertEquals(AppCategory.entries[i], group.category)
        }
    }

    @Test
    fun viewModelGroupedAppsReflectsLoadedState() =
        runTest {
            val apps =
                listOf(
                    app("com.game.a", "Angry", AppCategory.GAMING),
                    app("com.social.z", "Zoom", AppCategory.SOCIAL),
                    app("com.social.a", "Again", AppCategory.SOCIAL),
                )
            val vm = AllowlistEditorViewModel(FakeRepo(apps))
            vm.load()

            val groups = vm.groupedApps()
            // SOCIAL(0) before GAMING(2)
            assertEquals(2, groups.size)
            assertEquals(AppCategory.SOCIAL, groups[0].category)
            assertEquals(listOf("Again", "Zoom"), groups[0].apps.map { it.label })
            assertEquals(AppCategory.GAMING, groups[1].category)
        }
}

// ---------------------------------------------------------------------------
// Part B — apply() → ApplyState mapping tests
// ---------------------------------------------------------------------------

/**
 * Tests that [AllowlistEditorViewModel.apply] maps every [SendResult] variant to the
 * correct [ApplyState]. A lambda is injected as [sendPolicy] so no real [PolicySender],
 * crypto, or network calls occur. Fail-closed: only [SendResult.Sent] → [ApplyState.Applied].
 */
class AllowlistEditorApplyTest {
    /** Create a ViewModel whose send always returns [result]. */
    private fun vmWith(result: SendResult): AllowlistEditorViewModel =
        AllowlistEditorViewModel(
            repo = FakeRepo(),
            sendPolicy = { result },
        )

    @Test
    fun apply_sent_mapsToApplied() =
        runTest {
            val bundle = minimalBundle()
            val vm = vmWith(SendResult.Sent(policySeq = 42L, bundle = bundle))
            vm.apply()
            val applyState = assertIs<ApplyState.Applied>(vm.state.value.applyState)
            assertEquals(42L, applyState.policySeq)
        }

    @Test
    fun apply_rejected_mapsToRejected() =
        runTest {
            val bundle = minimalBundle()
            val vm = vmWith(SendResult.Rejected(reason = "REGRESSION", bundle = bundle))
            vm.apply()
            val applyState = assertIs<ApplyState.Rejected>(vm.state.value.applyState)
            assertEquals("REGRESSION", applyState.reason)
        }

    @Test
    fun apply_transportFailed_mapsToTransportFailed() =
        runTest {
            val bundle = minimalBundle()
            val vm = vmWith(SendResult.TransportFailed(message = "offline", bundle = bundle))
            vm.apply()
            val applyState = assertIs<ApplyState.TransportFailed>(vm.state.value.applyState)
            assertEquals("offline", applyState.message)
        }

    @Test
    fun apply_notProvisioned_mapsToNotProvisioned() =
        runTest {
            val vm = vmWith(SendResult.NotProvisioned)
            vm.apply()
            assertIs<ApplyState.NotProvisioned>(vm.state.value.applyState)
        }

    @Test
    fun apply_notPaired_mapsToNotPaired() =
        runTest {
            val vm = vmWith(SendResult.NotPaired)
            vm.apply()
            assertIs<ApplyState.NotPaired>(vm.state.value.applyState)
        }

    @Test
    fun apply_nullSendFn_mapsToNotProvisioned() =
        runTest {
            val vm = AllowlistEditorViewModel(repo = FakeRepo(), sendPolicy = null)
            vm.apply()
            assertIs<ApplyState.NotProvisioned>(vm.state.value.applyState)
        }

    @Test
    fun apply_sendsCurrentAllowlistSortedAsProtoPolicy() =
        runTest {
            var capturedPolicy: Policy? = null
            val vm =
                AllowlistEditorViewModel(
                    repo =
                        object : AllowlistRepository {
                            override suspend fun fetchInstalledApps(): FetchAppsResult =
                                FetchAppsResult.Success(
                                    listOf(
                                        AppInfo("com.z", "Z"),
                                        AppInfo("com.a", "A"),
                                        AppInfo("com.m", "M"),
                                    ),
                                )

                            override fun saveAllowlist(allowlist: Set<String>) {}

                            override fun loadAllowlist(): Set<String> = emptySet()
                        },
                    sendPolicy = { policy ->
                        capturedPolicy = policy
                        SendResult.NotPaired // result irrelevant for this test
                    },
                )
            vm.load()
            vm.toggle("com.z")
            vm.toggle("com.a")

            vm.apply()

            val policy = requireNotNull(capturedPolicy)
            // Allowlist MUST be sorted in the proto Policy
            assertEquals(listOf("com.a", "com.z"), policy.allowlist)
        }

    @Test
    fun clearApplyState_resetsToNull() =
        runTest {
            val vm = vmWith(SendResult.NotPaired)
            vm.apply()
            assertIs<ApplyState.NotPaired>(vm.state.value.applyState)
            vm.clearApplyState()
            assertNull(vm.state.value.applyState)
        }

    // ---------------------------------------------------------------------------
    // Helper: build a minimal PolicyBundle for SendResult variants that carry one.
    // Uses PolicyBundleBuilder (same package, commonMain) with a fixed sig.
    // ---------------------------------------------------------------------------
    private fun minimalBundle() =
        PolicyBundleBuilder
            .build(
                policy = com.openwarden.proto.Policy(allowlist = listOf("com.test")),
                childDeviceId = "test-child",
                policySeq = 1L,
                nowMs = 1_000L,
                freshnessWindowMs = 86_400_000L,
                nonceHex = "0".repeat(32),
            ).copy(sig = "a".repeat(128)) // fake hex sig — not verified in these tests
}

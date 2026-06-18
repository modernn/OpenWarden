package com.openwarden.parent.dashboard

import kotlinx.datetime.Instant

/**
 * Fake / fixture implementation of [ChildStateRepository].
 *
 * Used in:
 *   1. Unit tests (inject specific scenarios via [scenario]).
 *   2. Android Compose previews while the live HTTP client (#20) is unbuilt.
 *
 * NOT for production use.  The live HTTP client will be injected via DI and
 * will replace this in the production build.
 *
 * [freshReportedAt] is the timestamp used for online fixtures.  Tests that need
 * to exercise freshness-window logic should supply a custom value.
 */
class FakeChildStateRepository(
    private val scenario: Scenario = Scenario.Online,
    /**
     * Simulated child self-report timestamp for the [Scenario.Online] fixture.
     * Defaults to a recently-fixed epoch that tests override as needed.
     * Deliberately not "now()" — the repository MUST NOT fabricate a live timestamp;
     * tests supply explicit values so freshness assertions are deterministic.
     */
    private val freshReportedAt: Instant = Instant.fromEpochSeconds(1_718_000_100L),
) : ChildStateRepository {

    sealed class Scenario {
        /** Child is online with realistic fixture data and a fresh reportedAt. */
        data object Online : Scenario()

        /** Child device is unreachable — snapshot has null reportedAt → OFFLINE_OR_UNKNOWN. */
        data object Offline : Scenario()

        /**
         * Repository throws internally — must fail closed to offline/unknown.
         * Per contract the repository should not throw, but [DashboardViewModel]'s
         * try/catch backstop catches this and maps to [DashboardUiState.Error].
         */
        data object Error : Scenario()
    }

    override suspend fun fetchSnapshot(): ChildDashboardSnapshot = when (scenario) {
        Scenario.Online -> onlineFixture(freshReportedAt)
        Scenario.Offline -> offlineSnapshot()
        Scenario.Error -> offlineSnapshot() // errors degrade to offline; see contract in interface
    }

    companion object {
        /**
         * Shared offline/unknown snapshot.
         * reportedAt = null → ViewModel derives OFFLINE_OR_UNKNOWN regardless of any flag.
         * todayUsage = Unknown, blocksData = Unknown → UI shows "unavailable", never "0m"/"none".
         */
        fun offlineSnapshot() = ChildDashboardSnapshot(
            reportedAt = null,
            todayUsage = TodayUsage.Unknown,
            blocksData = BlocksData.Unknown,
        )

        /** Realistic fixture for the Online scenario. */
        fun onlineFixture(
            reportedAt: Instant = Instant.fromEpochSeconds(1_718_000_100L),
        ) = ChildDashboardSnapshot(
            reportedAt = reportedAt,
            todayUsage = TodayUsage.Known(
                totalForegroundMs = 2_700_000L, // 45 min
                perApp = listOf(
                    AppUsageSummary(
                        packageName = "com.android.chrome",
                        label = "Chrome",
                        foregroundMs = 1_200_000L,
                    ),
                    AppUsageSummary(
                        packageName = "com.roblox.client",
                        label = "Roblox",
                        foregroundMs = 900_000L,
                    ),
                    AppUsageSummary(
                        packageName = "com.google.android.youtube",
                        label = "YouTube",
                        foregroundMs = 600_000L,
                    ),
                ),
            ),
            blocksData = BlocksData.Known(
                attempts = listOf(
                    BlockedAttempt(
                        packageName = "com.discord",
                        appLabel = "Discord",
                        category = AppCategory.SOCIAL,
                        blockedAt = Instant.fromEpochSeconds(1_718_000_000L),
                        countToday = 2,
                    ),
                    BlockedAttempt(
                        packageName = "com.google.android.youtube",
                        appLabel = "YouTube",
                        category = AppCategory.ENTERTAINMENT,
                        blockedAt = Instant.fromEpochSeconds(1_717_990_000L),
                        countToday = 1,
                    ),
                ),
            ),
        )
    }
}

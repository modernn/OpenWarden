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
 */
class FakeChildStateRepository(
    private val scenario: Scenario = Scenario.Online,
) : ChildStateRepository {

    sealed class Scenario {
        /** Child is online with realistic fixture data. */
        data object Online : Scenario()

        /** Child device is unreachable — must display as offline/unknown. */
        data object Offline : Scenario()

        /** Repository throws internally — must fail closed to offline/unknown. */
        data object Error : Scenario()
    }

    override suspend fun fetchSnapshot(): ChildDashboardSnapshot = when (scenario) {
        Scenario.Online -> onlineFixture()
        Scenario.Offline -> offlineSnapshot()
        Scenario.Error -> offlineSnapshot() // errors degrade to offline; see contract in interface
    }

    companion object {
        /** Shared offline/unknown snapshot.  All callers that need fail-closed should use this. */
        fun offlineSnapshot() = ChildDashboardSnapshot(
            onlineStatus = ChildOnlineStatus.OFFLINE_OR_UNKNOWN,
            todayUsage = TodayUsage.EMPTY,
            recentBlocks = emptyList(),
        )

        /** Realistic fixture for the Online scenario. */
        fun onlineFixture() = ChildDashboardSnapshot(
            onlineStatus = ChildOnlineStatus.ONLINE,
            todayUsage = TodayUsage(
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
            recentBlocks = listOf(
                BlockedAttempt(
                    packageName = "com.discord",
                    appLabel = "Discord",
                    category = "SOCIAL",
                    blockedAt = Instant.fromEpochSeconds(1_718_000_000L),
                    countToday = 2,
                ),
                BlockedAttempt(
                    packageName = "com.google.android.youtube",
                    appLabel = "YouTube",
                    category = "ENTERTAINMENT",
                    blockedAt = Instant.fromEpochSeconds(1_717_990_000L),
                    countToday = 1,
                ),
            ),
        )
    }
}

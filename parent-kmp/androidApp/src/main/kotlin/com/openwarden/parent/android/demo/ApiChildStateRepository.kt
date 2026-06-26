package com.openwarden.parent.android.demo

import com.openwarden.parent.dashboard.AppUsageSummary
import com.openwarden.parent.dashboard.BlocksData
import com.openwarden.parent.dashboard.ChildDashboardSnapshot
import com.openwarden.parent.dashboard.ChildStateRepository
import com.openwarden.parent.dashboard.TodayUsage
import kotlinx.datetime.Instant

/**
 * DEMO-grade real [ChildStateRepository] (#20).
 *
 * Fetches the child's `/state` + `/usage` over [ChildApiClient] (hardcoded
 * `http://10.0.2.2:7180`, no auth/TLS — the two-emulator showcase only) and assembles a
 * [ChildDashboardSnapshot] for the dashboard, replacing the fixture
 * [com.openwarden.parent.dashboard.FakeChildStateRepository].
 *
 * Honors the [ChildStateRepository] contract:
 *  - **Never throws** — any failure (network, parse) yields an offline snapshot
 *    (`reportedAt = null`, usage/blocks `Unknown`).
 *  - **Relays only the child's self-reported timestamp** for `reportedAt`; it never fabricates
 *    one from the local clock. The ONLINE/OFFLINE trust decision stays in the ViewModel, which
 *    judges `reportedAt` against its freshness window.
 *  - **Metadata only** — package + label + foreground minutes; no content ever.
 *
 * The [ChildApiClient] is injected (no default) so its OkHttp lifecycle has exactly one owner — the
 * caller that constructs it also closes it via [close]. A default-constructed client would be a leak
 * footgun for any caller that forgets to close it.
 */
internal class ApiChildStateRepository(
    private val client: ChildApiClient,
) : ChildStateRepository,
    java.io.Closeable {
    override suspend fun fetchSnapshot(): ChildDashboardSnapshot =
        runCatching {
            val state =
                (client.getState() as? ApiResult.Success)?.data
                    ?: return@runCatching offline()
            // A null usage list means the /usage fetch failed → TodayUsage.Unknown (not zero).
            val usage = (client.getUsage() as? ApiResult.Success)?.data
            mapToSnapshot(state, usage)
        }.getOrDefault(offline())

    override fun close() = client.close()

    internal companion object {
        const val MS_PER_MIN = 60_000L

        fun offline() =
            ChildDashboardSnapshot(
                reportedAt = null,
                todayUsage = TodayUsage.Unknown,
                blocksData = BlocksData.Unknown,
            )

        /**
         * Pure mapping from the child wire types to a dashboard snapshot (unit-tested).
         *
         * @param usage null when the /usage fetch failed → [TodayUsage.Unknown] (distinct from a
         *   real empty/zero reading). The /state fetch is the reachability gate, so [state] is
         *   non-null here.
         */
        fun mapToSnapshot(
            state: ChildStateResponse,
            usage: List<AppUsageEntry>?,
        ): ChildDashboardSnapshot {
            // Liveness: relay ONLY the child's self-reported epoch-ms (0/absent → null → not fresh).
            // Never substitute the local clock — that would fake an ONLINE the child didn't assert.
            val reportedAt: Instant? =
                state.reportedAt.takeIf { it > 0L }?.let { Instant.fromEpochMilliseconds(it) }

            val todayUsage: TodayUsage =
                if (usage == null) {
                    TodayUsage.Unknown
                } else {
                    TodayUsage.Known(
                        totalForegroundMs = usage.sumOf { it.foregroundMinutes * MS_PER_MIN },
                        perApp =
                            usage.map {
                                AppUsageSummary(
                                    packageName = it.packageName,
                                    label = it.label.ifBlank { it.packageName },
                                    foregroundMs = it.foregroundMinutes * MS_PER_MIN,
                                )
                            },
                    )
                }

            return ChildDashboardSnapshot(
                reportedAt = reportedAt,
                todayUsage = todayUsage,
                // No child /blocks endpoint yet (#30) — honestly unavailable, never "no blocks today".
                blocksData = BlocksData.Unknown,
            )
        }
    }
}

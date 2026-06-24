package com.openwarden.child

import android.app.usage.UsageStatsManager
import android.content.Context
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowUsageStatsManager
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Unit tests for [UsageStatsHelper] — runs on JVM via Robolectric, no emulator required.
 *
 * Coverage (ADR-042):
 * - No-grant on a **debug** build → DemoFallback (empty/null/SecurityException paths).
 * - No-grant on a **release** build → Unavailable (honest-empty, never fabricated; D2).
 * - OnDevice returned when UsageStatsManager has entries with foreground time — and it
 *   flows even with debuggable=false (real data is never suppressed by the build type).
 * - Results sorted desc, zero-minute entries excluded, top-15 cap, floor-division of ms.
 * - DemoFallback entries all carry the "[DEMO]" label prefix (can't read as real data).
 * - METADATA-ONLY shape guard: [AppUsageEntry] exposes exactly packageName/label/
 *   foregroundMinutes — the stalkerware boundary (D1). Adding a content-ish field fails this.
 *
 * [DEVICE RESIDUAL]: the real UsageStatsManager path is verified here under
 * ShadowUsageStatsManager, not on a physical device (ADR-042 Consequences). The
 * on-device appops-grant confirmation is a recorded E2E pre-prod gate.
 *
 * Note on ShadowUsageStatsManager time ranges: Robolectric 4.13 filters queryUsageStats by
 * the firstTimeStamp/lastTimeStamp against the query window; we set them to "now" so they
 * fall inside the helper's 24-hour window.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class UsageStatsHelperTest {

    private val context: Context get() = RuntimeEnvironment.getApplication()

    private fun buildStats(pkg: String, foregroundMs: Long): android.app.usage.UsageStats {
        val now = System.currentTimeMillis()
        return ShadowUsageStatsManager.UsageStatsBuilder.newBuilder()
            .setPackageName(pkg)
            .setTotalTimeInForeground(foregroundMs)
            .setFirstTimeStamp(now - foregroundMs)
            .setLastTimeStamp(now)
            .build()
    }

    // -------------------------------------------------------------------------
    // No-grant — empty shadow simulates a missing PACKAGE_USAGE_STATS appops grant
    // -------------------------------------------------------------------------

    @Test
    fun `no grant on debug build returns DemoFallback`() {
        // Robolectric ShadowUsageStatsManager starts empty → simulates denied permission.
        val result = UsageStatsHelper.query(context, debuggable = true)
        assertIs<UsageStatsHelper.UsageResult.DemoFallback>(
            result, "Empty UsageStatsManager on a debug build must produce DemoFallback, got $result",
        )
    }

    @Test
    fun `no grant on release build returns Unavailable (honest-empty, never fabricated)`() {
        val result = UsageStatsHelper.query(context, debuggable = false)
        assertIs<UsageStatsHelper.UsageResult.Unavailable>(
            result, "Empty UsageStatsManager on a release build must produce Unavailable, got $result",
        )
    }

    @Test
    fun `DemoFallback entries are non-empty`() {
        val result = UsageStatsHelper.query(context, debuggable = true)
            as UsageStatsHelper.UsageResult.DemoFallback
        assertTrue(result.entries.isNotEmpty(), "Demo fallback must contain at least one entry")
    }

    @Test
    fun `DemoFallback entries all carry DEMO label prefix`() {
        val result = UsageStatsHelper.query(context, debuggable = true)
            as UsageStatsHelper.UsageResult.DemoFallback
        val bad = result.entries.filter { it.label?.startsWith("[DEMO]") != true }
        assertTrue(
            bad.isEmpty(),
            "Every demo-fallback label must start with [DEMO]; offenders: ${bad.map { it.label }}",
        )
    }

    @Test
    fun `DemoFallback entries all have foregroundMinutes greater than zero`() {
        val result = UsageStatsHelper.query(context, debuggable = true)
            as UsageStatsHelper.UsageResult.DemoFallback
        assertTrue(
            result.entries.all { it.foregroundMinutes > 0 },
            "Demo entries must have foregroundMinutes > 0",
        )
    }

    @Test
    fun `DemoFallback entries are sorted descending by foregroundMinutes`() {
        val result = UsageStatsHelper.query(context, debuggable = true)
            as UsageStatsHelper.UsageResult.DemoFallback
        val minutes = result.entries.map { it.foregroundMinutes }
        assertEquals(minutes.sortedDescending(), minutes, "Demo fallback must be sorted desc")
    }

    // -------------------------------------------------------------------------
    // On-device path via ShadowUsageStatsManager
    // -------------------------------------------------------------------------

    @Test
    fun `query returns OnDevice when UsageStatsManager has stats with foreground time`() {
        val usm = context.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
        Shadows.shadowOf(usm).addUsageStats(
            UsageStatsManager.INTERVAL_DAILY, buildStats("com.example.testapp", 10 * 60_000L),
        )
        // debuggable=false: real data must flow even on a release build, never suppressed.
        val result = UsageStatsHelper.query(context, debuggable = false)
        assertIs<UsageStatsHelper.UsageResult.OnDevice>(
            result, "Non-empty UsageStatsManager must produce OnDevice, got $result",
        )
    }

    @Test
    fun `OnDevice entries exclude packages with zero foreground time`() {
        val usm = context.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
        val shadow = Shadows.shadowOf(usm)
        shadow.addUsageStats(UsageStatsManager.INTERVAL_DAILY, buildStats("com.example.active", 5 * 60_000L))
        shadow.addUsageStats(UsageStatsManager.INTERVAL_DAILY, buildStats("com.example.idle", 0L))

        val result = UsageStatsHelper.query(context, debuggable = true)
            as UsageStatsHelper.UsageResult.OnDevice
        val packages = result.entries.map { it.packageName }
        assertFalse("com.example.idle" in packages, "Entries with 0 foreground time must be excluded")
        assertTrue("com.example.active" in packages, "Entries with non-zero foreground time must be included")
    }

    @Test
    fun `OnDevice entries are sorted descending by foregroundMinutes`() {
        val usm = context.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
        val shadow = Shadows.shadowOf(usm)
        listOf("com.a" to 3L, "com.b" to 10L, "com.c" to 1L).forEach { (pkg, mins) ->
            shadow.addUsageStats(UsageStatsManager.INTERVAL_DAILY, buildStats(pkg, mins * 60_000L))
        }

        val result = UsageStatsHelper.query(context, debuggable = true)
            as UsageStatsHelper.UsageResult.OnDevice
        val minutes = result.entries.map { it.foregroundMinutes }
        assertEquals(minutes.sortedDescending(), minutes, "OnDevice results must be sorted desc")
    }

    @Test
    fun `OnDevice entries capped at top 15`() {
        val usm = context.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
        val shadow = Shadows.shadowOf(usm)
        for (i in 1..20) {
            shadow.addUsageStats(UsageStatsManager.INTERVAL_DAILY, buildStats("com.pkg.$i", i * 60_000L))
        }

        val result = UsageStatsHelper.query(context, debuggable = true)
            as UsageStatsHelper.UsageResult.OnDevice
        assertTrue(result.entries.size <= 15, "OnDevice results must be capped at 15; got ${result.entries.size}")
    }

    @Test
    fun `OnDevice foregroundMinutes is floor division of ms by 60000`() {
        val usm = context.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
        // 90 000 ms = 1.5 minutes → floor → 1 minute
        Shadows.shadowOf(usm).addUsageStats(
            UsageStatsManager.INTERVAL_DAILY, buildStats("com.example.timed", 90_000L),
        )

        val result = UsageStatsHelper.query(context, debuggable = true)
            as UsageStatsHelper.UsageResult.OnDevice
        val entry = result.entries.first { it.packageName == "com.example.timed" }
        assertEquals(1L, entry.foregroundMinutes, "90 000 ms must floor to foregroundMinutes=1")
    }

    @Test
    fun `OnDevice aggregates multiple intervals for the same package`() {
        val usm = context.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
        val shadow = Shadows.shadowOf(usm)
        // Two daily intervals for the same package → 4 + 6 = 10 minutes aggregated.
        shadow.addUsageStats(UsageStatsManager.INTERVAL_DAILY, buildStats("com.example.split", 4 * 60_000L))
        shadow.addUsageStats(UsageStatsManager.INTERVAL_DAILY, buildStats("com.example.split", 6 * 60_000L))

        val result = UsageStatsHelper.query(context, debuggable = true)
            as UsageStatsHelper.UsageResult.OnDevice
        val matches = result.entries.filter { it.packageName == "com.example.split" }
        assertEquals(1, matches.size, "A package must appear once, aggregated")
        assertEquals(10L, matches.first().foregroundMinutes, "4m + 6m must aggregate to 10m")
    }

    // -------------------------------------------------------------------------
    // Stalkerware boundary (ADR-042 D1) — metadata only, never content
    // -------------------------------------------------------------------------

    @Test
    fun `AppUsageEntry exposes only metadata fields (no content surface)`() {
        // The data class is the boundary: it may carry ONLY package name, label, and
        // foreground minutes. Any new property is a potential content leak and must be
        // reviewed against the non-negotiable. This guard fails loudly if one is added.
        val props = UsageStatsHelper.AppUsageEntry::class.members
            .filterIsInstance<kotlin.reflect.KProperty<*>>()
            .map { it.name }
            .toSet()
        assertEquals(
            setOf("packageName", "label", "foregroundMinutes"),
            props,
            "AppUsageEntry must expose only metadata fields; a new field risks the content boundary",
        )
    }
}

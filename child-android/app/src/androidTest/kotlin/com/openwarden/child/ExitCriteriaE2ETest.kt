package com.openwarden.child

import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.SystemClock
import android.os.UserManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Exit-criteria E2E ("Oliver's phone works", docs/ROADMAP.md → docs/E2E_EXIT_CRITERIA.md).
 *
 * Issue #30. Asserts the exit criteria that are verifiable over ADB on a provisioned device:
 *  1. The app is Device Owner (the outcome of "provision from factory").
 *  3. Block/unblock enforcement latency is under the 5-second budget (the device-side leg).
 *
 * Criterion 2 (the Day-One restriction baseline is intact) is **deliberately not asserted here.**
 * The baseline includes `DISALLOW_DEBUGGING_FEATURES`, which disables ADB the instant the watchdog
 * enforces it — so an ADB-driven instrumentation test can only ever run while the baseline is
 * *absent*, never observe it *present*. (Verified live: the device drops to `offline` the moment
 * `PolicyService` applies Day-One after boot.) Criterion 2 is therefore checked manually on-device —
 * the Kid-Transparency screen or a one-shot pre-enforcement readback; see docs/E2E_EXIT_CRITERIA.md.
 * Criteria 1 and 3 here are ADB-safe: they read DO state and toggle reversible, non-baseline DPM
 * state, none of which touches debugging.
 *
 * Run manually (device provisioned as Device Owner, watchdog not yet enforcing):
 *   adb shell am instrument -w -e class com.openwarden.child.ExitCriteriaE2ETest \
 *     com.openwarden.child.debug.test/androidx.test.runner.AndroidJUnitRunner
 *
 * Criterion 3 [assumeTrue]-skips when the app is NOT Device Owner so the class fails LOUDLY in
 * exactly one place — [exitCriterion1_deviceOwnerProvisioned] — never a vacuous green.
 */
@RunWith(AndroidJUnit4::class)
class ExitCriteriaE2ETest {
    private lateinit var context: Context
    private lateinit var dpm: DevicePolicyManager
    private lateinit var admin: ComponentName

    @Before
    fun setUp() {
        context = InstrumentationRegistry.getInstrumentation().targetContext
        dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
        admin = AdminReceiver.componentName(context)
    }

    private fun isDeviceOwner(): Boolean = dpm.isDeviceOwnerApp(context.packageName)

    /**
     * Criterion 1 — the device is provisioned: this app is Device Owner and its admin is active.
     * This is the one HARD assertion; everything else assumes it, so a non-provisioned device can
     * never produce a vacuously-green suite.
     */
    @Test
    fun exitCriterion1_deviceOwnerProvisioned() {
        assertTrue(
            "Exit criterion: app must be Device Owner. Provision first: " +
                "adb shell dpm set-device-owner ${context.packageName}/com.openwarden.child.AdminReceiver",
            isDeviceOwner(),
        )
        assertTrue("AdminReceiver must be an active admin", dpm.isAdminActive(admin))
    }

    /**
     * Criterion 3, device-side write leg — a Device-Owner enforcement write reflects in the
     * authoritative readback well under the 5-second budget. Uses [UserManager.DISALLOW_AIRPLANE_MODE]
     * (reversible, harmless, NOT in our baseline) as a victim-free proxy for the same
     * write → enforce → readback path block/unblock travels. Always runs on a provisioned device, so
     * the latency criterion is never silently skipped. Reverted in `finally`.
     */
    @Test
    fun exitCriterion3a_enforcementWriteReadbackLatencyUnder5s() {
        assumeTrue("requires Device Owner (provision first)", isDeviceOwner())
        val probe = UserManager.DISALLOW_AIRPLANE_MODE
        // Heal any probe left set by a hard-killed prior run and guarantee a real off→on transition
        // to time. The probe is NOT in our baseline, so clearing it is safe and the watchdog never
        // re-adds it. Deterministic on a provisioned device: this latency check never silently skips.
        runCatching { dpm.clearUserRestriction(admin, probe) }
        try {
            val blockMs =
                measureUntil(BUDGET_MS) {
                    dpm.addUserRestriction(admin, probe)
                    dpm.getUserRestrictions(admin).getBoolean(probe, false)
                }
            assertTrue(
                "enforcement write→readback latency ${blockMs}ms exceeds ${BUDGET_MS}ms budget",
                blockMs < BUDGET_MS,
            )
            val clearMs =
                measureUntil(BUDGET_MS) {
                    dpm.clearUserRestriction(admin, probe)
                    !dpm.getUserRestrictions(admin).getBoolean(probe, false)
                }
            assertTrue(
                "enforcement clear→readback latency ${clearMs}ms exceeds ${BUDGET_MS}ms budget",
                clearMs < BUDGET_MS,
            )
        } finally {
            runCatching { dpm.clearUserRestriction(admin, probe) }
        }
    }

    /**
     * Criterion 3, real app-suspend leg — exercises the actual `setPackagesSuspended` path the
     * allowlist uses, on a dynamically-chosen benign user app, and asserts both block and unblock
     * land under the 5-second budget. [assumeTrue]-skips when the device has no safe victim app
     * (e.g. a bare emulator with only system apps); a real provisioned phone has user apps, and the
     * runbook documents installing a throwaway app to make this deterministic on the emulator.
     * Always reverts (un-suspend + un-hide) in `finally`.
     */
    @Test
    fun exitCriterion3b_appSuspendLatencyUnder5s() {
        assumeTrue("requires Device Owner (provision first)", isDeviceOwner())
        val pkg = pickBenignTarget()
        assumeTrue(
            "no benign user app to exercise real block/unblock on this device — install a throwaway " +
                "user app or run on a real device with apps installed",
            pkg != null,
        )
        requireNotNull(pkg)
        try {
            val blockMs =
                measureUntil(BUDGET_MS) {
                    dpm.setPackagesSuspended(admin, arrayOf(pkg), true)
                    isLaunchBlocked(pkg)
                }
            assertTrue("block latency ${blockMs}ms for $pkg exceeds ${BUDGET_MS}ms budget", blockMs < BUDGET_MS)
            val unblockMs =
                measureUntil(BUDGET_MS) {
                    dpm.setPackagesSuspended(admin, arrayOf(pkg), false)
                    !isLaunchBlocked(pkg)
                }
            assertTrue("unblock latency ${unblockMs}ms for $pkg exceeds ${BUDGET_MS}ms budget", unblockMs < BUDGET_MS)
        } finally {
            runCatching { dpm.setPackagesSuspended(admin, arrayOf(pkg), false) }
            runCatching { dpm.setApplicationHidden(admin, pkg, false) }
        }
    }

    /** A package is launch-blocked for this admin when it is suspended OR hidden (mirrors PolicyEnforcer). */
    private fun isLaunchBlocked(pkg: String): Boolean {
        val suspended = runCatching { dpm.isPackageSuspended(admin, pkg) }.getOrDefault(false)
        val hidden = runCatching { dpm.isApplicationHidden(admin, pkg) }.getOrDefault(false)
        return suspended || hidden
    }

    /**
     * Poll [op] until it returns true or [budgetMs] elapses; return elapsed ms. If it never flips,
     * returns [budgetMs] — which fails the `< budgetMs` assertion, so a never-enforcing device is a
     * loud failure, not a pass.
     */
    private fun measureUntil(
        budgetMs: Long,
        op: () -> Boolean,
    ): Long {
        val start = SystemClock.elapsedRealtime()
        while (true) {
            if (op()) return SystemClock.elapsedRealtime() - start
            if (SystemClock.elapsedRealtime() - start >= budgetMs) return budgetMs
            SystemClock.sleep(POLL_MS)
        }
    }

    /**
     * First launchable, non-system, non-self, non-launcher, non-critical installed package — a safe
     * victim to briefly suspend/un-suspend. null when the device has no such user app.
     */
    private fun pickBenignTarget(): String? {
        val pm = context.packageManager
        val self = context.packageName
        val launcher =
            pm
                .resolveActivity(
                    Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME),
                    PackageManager.MATCH_DEFAULT_ONLY,
                )?.activityInfo
                ?.packageName
        val launchable =
            pm
                .queryIntentActivities(
                    Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER),
                    0,
                ).mapNotNull { it.activityInfo?.packageName }
                .toSet()
        return launchable.firstOrNull { p ->
            p != self && p != launcher && p !in CRITICAL_EXEMPT && !isSystem(pm, p)
        }
    }

    private fun isSystem(
        pm: PackageManager,
        pkg: String,
    ): Boolean {
        // Treat an unresolvable package as system (conservative: never pick it as a victim).
        val flags = runCatching { pm.getApplicationInfo(pkg, 0).flags }.getOrDefault(ApplicationInfo.FLAG_SYSTEM)
        return (flags and ApplicationInfo.FLAG_SYSTEM) != 0
    }

    companion object {
        /** "sub-5-sec latency" exit criterion. */
        private const val BUDGET_MS = 5_000L
        private const val POLL_MS = 25L

        /** Never suspend these even if they were somehow launchable — would destabilise the device. */
        private val CRITICAL_EXEMPT =
            setOf(
                "com.android.systemui",
                "com.android.settings",
                "com.google.android.gms",
                "com.google.android.gsf",
                "com.android.vending",
            )
    }
}

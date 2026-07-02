package com.openwarden.child

import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Build
import android.os.SystemClock
import android.os.UserManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Exit-criteria E2E ("Oliver's phone works", docs/ROADMAP.md → docs/E2E_EXIT_CRITERIA.md).
 *
 * Issue #30 (criteria 1, 3) + issue #131 (criterion 2, via the ADR-045 seam). Asserts the exit
 * criteria on a provisioned device:
 *  1. The app is Device Owner (the outcome of "provision from factory").
 *  2. The enforced Day-One restriction baseline is intact — everything **except** the one
 *     restriction that severs ADB ([UserManager.DISALLOW_DEBUGGING_FEATURES]), which the ADR-045
 *     `restrictionFilter` seam omits so the rest can be applied and read back while ADB stays alive.
 *     That one restriction stays out-of-band (inherent; the ADB-offline-after-boot signal
 *     substantiates it — see docs/E2E_EXIT_CRITERIA.md).
 *  3. Block/unblock enforcement latency is under the 5-second budget (the device-side leg).
 *
 * Before ADR-045, criterion 2 could not be ADB-automated at all: `DISALLOW_DEBUGGING_FEATURES` drops
 * the device to ADB `offline` the instant the watchdog enforces it. The seam relieves that for the
 * whole baseline *except* that single restriction.
 *
 * Run manually (device provisioned as Device Owner, watchdog NOT yet enforcing — so the baseline is
 * absent at start and the tests can apply / observe / revert it):
 *   adb shell am instrument -w -e class com.openwarden.child.ExitCriteriaE2ETest \
 *     com.openwarden.child.debug.test/androidx.test.runner.AndroidJUnitRunner
 *
 * Every test [assumeTrue]-skips when the app is NOT Device Owner, so the class fails LOUDLY in
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
     * Criterion 2 — the enforced Day-One baseline is intact on a real provisioned device
     * (docs/E2E_EXIT_CRITERIA.md). Uses the ADR-045 [PolicyEnforcer] `restrictionFilter` seam to omit
     * the ONE restriction that kills ADB ([UserManager.DISALLOW_DEBUGGING_FEATURES]) so the rest of
     * the baseline can be applied and read back over a live ADB link. The omitted restriction's
     * presence in the *release* set is pinned separately by the host `PolicyEnforcerTest` regression
     * and stays out-of-band here — inherent: you cannot both keep ADB alive and observe the
     * restriction that severs it.
     *
     * Assurance boundary (stated to avoid over-claiming):
     *  - The added value over the host test is that this runs against the **real DevicePolicyManager**
     *    (the host test injects a fake readback), so it proves the filtered baseline actually STICKS
     *    on the platform — not merely that the enforcer's logic is internally consistent.
     *  - The readback oracle is [DevicePolicyManager.getUserRestrictions] for this admin — the
     *    DO-authoritative view, the same fail-closed-correct authority the enforcer verifies against.
     *    [UserManager.hasUserRestriction] is deliberately NOT used: its effective view can report a
     *    restriction set by another source and so fail-OPEN (see `defaultRestrictionReader`).
     *  - To reduce the "enforcer is both actor and oracle" coupling the runbook warns about, the
     *    expected set is recomputed independently from the pure [PolicyEnforcer.requiredRestrictionsForSdk]
     *    (not read off the enforcer's own field), and the readback assertion is the test's own, not
     *    [PolicyEnforcer.missingRestrictions]. This does not fully break the circle — no more-independent
     *    *and* fail-closed-correct on-device oracle exists.
     *
     * [assumeTrue]-skips when not Device Owner (criterion 1 is the loud failure). Runs in the
     * provisioned-but-watchdog-halted window (like the latency tests); reverts every restriction it
     * set in `finally`. Factory-Reset-Protection is a separate call and is untouched.
     */
    @Test
    fun exitCriterion2_enforcedBaselineIntactMinusAdbKiller() {
        assumeTrue("requires Device Owner (provision first)", isDeviceOwner())
        val adbKiller = UserManager.DISALLOW_DEBUGGING_FEATURES

        // Independent expectation: the full baseline for THIS OS level minus only the ADB-killer,
        // computed straight from the pure function so it does not trust the enforcer's own list.
        val expected = PolicyEnforcer.requiredRestrictionsForSdk(Build.VERSION.SDK_INT) - adbKiller
        assertTrue("baseline must be non-trivial", expected.size >= 2)
        assertTrue("expected set must exclude the ADB-killer", adbKiller !in expected)

        val enforcer = PolicyEnforcer(context, restrictionFilter = { it != adbKiller })
        // List-level guard: the seam must have produced exactly the independently-computed set.
        assertEquals(
            "seam-filtered requiredRestrictions must equal the independent expectation",
            expected.toSet(),
            enforcer.requiredRestrictions.toSet(),
        )

        try {
            // Real apply + real self-verify against the live DevicePolicyManager. Throws (and locks)
            // if any filtered restriction does not stick — a loud failure, never a silent pass. ADB
            // survives because the ADB-killer was filtered out of the applied set.
            enforcer.applyDayOneRestrictions()

            // Test-owned DO-authoritative readback (NOT enforcer.missingRestrictions()).
            val set = dpm.getUserRestrictions(admin)
            val notSet = expected.filterNot { set.getBoolean(it, false) }
            assertTrue("enforced baseline missing from DO-authoritative readback: $notSet", notSet.isEmpty())
            // The ADB-killer must NOT have been applied by this filtered run — that is what kept ADB
            // alive to reach this assertion. Its release-set membership is a separate host test.
            assertTrue(
                "filtered run must not have applied the ADB-killer $adbKiller",
                !set.getBoolean(adbKiller, false),
            )
        } finally {
            // Revert to the pre-test state so a watchdog-halted device is left unrestricted. A live
            // watchdog re-asserts the FULL baseline (and severs ADB) on its next tick — the documented
            // enforcement behaviour, hence the halted-watchdog run precondition.
            expected.forEach { runCatching { dpm.clearUserRestriction(admin, it) } }
        }
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

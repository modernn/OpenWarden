package com.openwarden.child

import android.app.admin.DevicePolicyManager
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * E2E instrumented test proving the enforcement half of parent approve / unapprove:
 *
 *  - When an app is NOT on the allowlist the child suspends it (deny-by-default).
 *  - When an app IS on the allowlist the child un-suspends it (approve).
 *
 * Entry point under test: [PolicyEnforcer.applyAllowlist]. The test calls the real enforcer
 * against the real [DevicePolicyManager] on a provisioned Device Owner, so it exercises the
 * full DPM write → readback path rather than any injected seam.
 *
 * ## ADB / DISALLOW_DEBUGGING_FEATURES constraint (same as ExitCriteriaE2ETest)
 *
 * These tests require ADB to stay alive. They must run **before** the policy watchdog enforces
 * the Day-One baseline, because `DISALLOW_DEBUGGING_FEATURES` severs ADB the instant it is
 * applied. The driver script (`scripts/e2e-allowlist.sh`) force-stops the watchdog — exactly
 * as `scripts/e2e-exit-criteria.sh` does — before instrumentation starts.
 *
 * ## Victim-app selection
 *
 * The test calls the same [pickBenignTarget] logic as [ExitCriteriaE2ETest.exitCriterion3b_appSuspendLatencyUnder5s]:
 * first launchable, non-system, non-self, non-launcher, non-critical installed package.
 * [assumeTrue]-skips when no such package exists (bare emulator). The runbook in
 * `docs/E2E_EXIT_CRITERIA.md` says to install a throwaway app on the emulator to make this
 * deterministic; the same throwaway satisfies both criteria.
 *
 * ## Idempotence / cleanup
 *
 * [tearDown] un-suspends and un-hides the target so the suite is repeatable with no leftover state.
 * If the test crashes mid-run the device is left in a fully restricted state (the fail-closed
 * direction), never in a silently-relaxed one.
 *
 * ## Run instructions
 *
 *   1. Boot emulator (watchdog NOT enforcing — do NOT trigger full Day-One enforcement first):
 *        adb shell am force-stop com.openwarden.child.debug
 *   2. Build + install:
 *        cd child-android && ./gradlew :app:assembleDebug :app:assembleDebugAndroidTest \
 *          :app:installDebug :app:installDebugAndroidTest
 *   3. Run:
 *        adb shell am instrument -w \
 *          -e class com.openwarden.child.AllowlistEnforcementE2ETest \
 *          com.openwarden.child.debug.test/androidx.test.runner.AndroidJUnitRunner
 *
 * Or use the driver:
 *
 *   scripts/e2e-allowlist.sh
 */
@RunWith(AndroidJUnit4::class)
class AllowlistEnforcementE2ETest {
    private lateinit var context: Context
    private lateinit var dpm: DevicePolicyManager
    private lateinit var admin: android.content.ComponentName

    /** The package chosen as the suspend/un-suspend victim for the current run. May be null on
     * a bare emulator; tests [assumeTrue]-skip in that case. */
    private var target: String? = null

    @Before
    fun setUp() {
        context = InstrumentationRegistry.getInstrumentation().targetContext
        dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
        admin = AdminReceiver.componentName(context)
        target = pickBenignTarget()
    }

    /**
     * Restore the victim to un-suspended / un-hidden state after every test so the suite is
     * repeatable and the device is not left with a suspended app after the test passes or fails.
     * runCatching on each call: a missing `target` or an already-clean state must not mask a
     * real test failure with a teardown exception.
     */
    @After
    fun tearDown() {
        val pkg = target ?: return
        runCatching { dpm.setPackagesSuspended(admin, arrayOf(pkg), false) }
        runCatching { dpm.setApplicationHidden(admin, pkg, false) }
    }

    // ---- precondition -----------------------------------------------------------------------

    /**
     * Hard gate: the app must be Device Owner. Every other test [assumeTrue]-gates on this so
     * a non-provisioned run fails exactly here rather than producing vacuous greens.
     *
     * Mirrors [ExitCriteriaE2ETest.exitCriterion1_deviceOwnerProvisioned].
     */
    @Test
    fun precondition_deviceOwnerProvisioned() {
        assertTrue(
            "AllowlistEnforcementE2ETest requires Device Owner. Provision first: " +
                "adb shell dpm set-device-owner " +
                "${context.packageName}/com.openwarden.child.AdminReceiver",
            dpm.isDeviceOwnerApp(context.packageName),
        )
        assertTrue("AdminReceiver must be an active admin", dpm.isAdminActive(admin))
    }

    // ---- approve → block (deny) -------------------------------------------------------------

    /**
     * Enforce an allowlist that does NOT include [target]. Asserts the target is launch-blocked
     * (suspended or hidden) within the [BUDGET_MS] latency budget after [PolicyEnforcer.applyAllowlist]
     * returns.
     *
     * This proves the deny-by-default half of ADR-022: an app not on the parent's allowlist is
     * suspended by the child enforcer.
     */
    @Test
    fun enforce_appNotOnAllowlist_isBlocked() {
        assumeTrue("requires Device Owner (provision first)", dpm.isDeviceOwnerApp(context.packageName))
        val pkg = target
        assumeTrue(
            "no benign user app available on this device — install a throwaway app or run on a real device",
            pkg != null,
        )
        requireNotNull(pkg)

        // Build an allowlist that explicitly omits the target — all other non-exempt apps are also
        // absent, but the target is the one we assert on. Use an empty set so the enforcer has no
        // allow-relaxation work to do; we only care about the deny path.
        val allowlistWithoutTarget: Set<String> = emptySet()

        // Construct the enforcer with production DPM seams (no injection). applyAllowlist is the
        // real entry point used by the policy watchdog and the /policy endpoint.
        val enforcer = PolicyEnforcer(context)
        enforcer.applyAllowlist(allowlistWithoutTarget)

        // applyAllowlist already verified the deny set is contained before returning (it would have
        // thrown AllowlistEnforcementException and locked the device if not). Poll the authoritative
        // readback to confirm within the latency budget as an independent assertion — mirrors the
        // approach in exitCriterion3b.
        val blockedMs =
            measureUntil(BUDGET_MS) { isLaunchBlocked(pkg) }
        assertTrue(
            "deny path: $pkg should be launch-blocked within ${BUDGET_MS}ms " +
                "after applyAllowlist(empty), took ${blockedMs}ms",
            blockedMs < BUDGET_MS,
        )
    }

    // ---- unapprove → allow ------------------------------------------------------------------

    /**
     * First block [target] (allowlist without it), then re-apply with it on the allowlist. Asserts
     * the target is NOT launch-blocked within [BUDGET_MS] after the second [PolicyEnforcer.applyAllowlist]
     * call.
     *
     * This proves the approve half of ADR-022: adding an app to the allowlist un-suspends it on the
     * child device.
     */
    @Test
    fun enforce_appAddedToAllowlist_isUnblocked() {
        assumeTrue("requires Device Owner (provision first)", dpm.isDeviceOwnerApp(context.packageName))
        val pkg = target
        assumeTrue(
            "no benign user app available on this device — install a throwaway app or run on a real device",
            pkg != null,
        )
        requireNotNull(pkg)

        val enforcer = PolicyEnforcer(context)

        // Phase 1: block the target by excluding it from the allowlist.
        enforcer.applyAllowlist(emptySet())
        val blockedMs = measureUntil(BUDGET_MS) { isLaunchBlocked(pkg) }
        assertTrue(
            "setup (block) failed: $pkg was not launch-blocked within ${BUDGET_MS}ms",
            blockedMs < BUDGET_MS,
        )

        // Phase 2: add the target to the allowlist; verify it becomes launchable again.
        enforcer.applyAllowlist(setOf(pkg))
        val unblockedMs = measureUntil(BUDGET_MS) { !isLaunchBlocked(pkg) }
        assertFalse(
            "approve path: $pkg should be launch-UNblocked within ${BUDGET_MS}ms " +
                "after applyAllowlist(setOf(pkg)), took ${unblockedMs}ms",
            // The assertion mirrors ExitCriteriaE2ETest: if !isLaunchBlocked never flips within
            // the budget, measureUntil returns BUDGET_MS, so unblockedMs >= BUDGET_MS fails the
            // `< BUDGET_MS` check we encode here as assertFalse(isLaunchBlocked).
            isLaunchBlocked(pkg),
        )
    }

    // ---- idempotence / re-apply -------------------------------------------------------------

    /**
     * Calling [PolicyEnforcer.applyAllowlist] twice with the same allowlist (target absent)
     * must not throw and must leave the target suspended. Proves the watchdog can re-assert
     * on every tick without double-applying causing a gap.
     */
    @Test
    fun enforce_idempotentReapply_staysBlocked() {
        assumeTrue("requires Device Owner (provision first)", dpm.isDeviceOwnerApp(context.packageName))
        val pkg = target
        assumeTrue(
            "no benign user app available on this device — install a throwaway app or run on a real device",
            pkg != null,
        )
        requireNotNull(pkg)

        val enforcer = PolicyEnforcer(context)

        // First apply.
        enforcer.applyAllowlist(emptySet())
        val firstBlockMs = measureUntil(BUDGET_MS) { isLaunchBlocked(pkg) }
        assertTrue(
            "first apply: $pkg should be blocked within ${BUDGET_MS}ms",
            firstBlockMs < BUDGET_MS,
        )

        // Second apply (idempotent re-assert — simulates watchdog tick).
        enforcer.applyAllowlist(emptySet())
        // Should still be blocked immediately; poll with the same budget.
        val secondBlockMs = measureUntil(BUDGET_MS) { isLaunchBlocked(pkg) }
        assertTrue(
            "idempotent re-apply: $pkg must remain blocked within ${BUDGET_MS}ms",
            secondBlockMs < BUDGET_MS,
        )
    }

    // ---- helpers ----------------------------------------------------------------------------

    /**
     * A package is launch-blocked for this admin when it is suspended OR hidden. Mirrors
     * [ExitCriteriaE2ETest.isLaunchBlocked] and the production [PolicyEnforcer] readback seam
     * ([PolicyEnforcer.defaultLaunchBlockedReader]).
     */
    private fun isLaunchBlocked(pkg: String): Boolean {
        val suspended = runCatching { dpm.isPackageSuspended(admin, pkg) }.getOrDefault(false)
        val hidden = runCatching { dpm.isApplicationHidden(admin, pkg) }.getOrDefault(false)
        return suspended || hidden
    }

    /**
     * Poll [op] until it returns true or [budgetMs] elapses. Returns elapsed ms. If [op] never
     * returns true, returns [budgetMs] — which fails any `< budgetMs` assertion, so a
     * never-enforcing device is a loud failure, not a pass.
     *
     * Mirrors [ExitCriteriaE2ETest.measureUntil].
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
     * First launchable, non-system, non-self, non-launcher, non-critical installed package — a
     * safe victim to briefly suspend / un-suspend. Returns null when the device has no such user
     * app (bare emulator without a throwaway app installed).
     *
     * Mirrors [ExitCriteriaE2ETest.pickBenignTarget] exactly so both suites agree on victim
     * selection and the same throwaway app satisfies both.
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
        val flags = runCatching { pm.getApplicationInfo(pkg, 0).flags }.getOrDefault(ApplicationInfo.FLAG_SYSTEM)
        return (flags and ApplicationInfo.FLAG_SYSTEM) != 0
    }

    companion object {
        /** DPM enforcement must land within 5 seconds (mirrors the exit-criteria budget). */
        private const val BUDGET_MS = 5_000L
        private const val POLL_MS = 25L

        /** Never suspend these — mirrored from [ExitCriteriaE2ETest.CRITICAL_EXEMPT]. */
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

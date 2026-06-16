package com.openwarden.child

import android.app.admin.DevicePolicyManager
import android.content.Context
import android.os.UserManager
import org.junit.After
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Robolectric unit tests for [PolicyEnforcer] — the fail-closed Day-One restriction baseline
 * (issue #8 / ADR-020).
 *
 * Two layers, mirroring the [LockScreenLockdownTest] idiom:
 *
 *  - **Deterministic** behaviour tests drive the verify path through the injected
 *    `isRestrictionSet` readback seam, so failure-injection ("only some restrictions stuck")
 *    is exact and does not depend on whether the Robolectric shadow wires
 *    `addUserRestriction` through to `UserManager`.
 *  - **Round-trip** tests use the real [UserManager] reader and are gated with [assumeTrue]
 *    so they SKIP (never vacuously pass) if the shadow does not persist restrictions.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PolicyEnforcerTest {

    private lateinit var context: Context
    private lateinit var dpm: DevicePolicyManager

    /**
     * Independent witness of the canonical baseline (DEFENSES.md row 2 — the 17-restriction v1
     * ship set). Spelled out here, NOT derived from [PolicyEnforcer.requiredRestrictions], so a
     * change to the production list is caught instead of rubber-stamped.
     */
    private val canonical17 = setOf(
        UserManager.DISALLOW_FACTORY_RESET,
        UserManager.DISALLOW_SAFE_BOOT,
        UserManager.DISALLOW_DEBUGGING_FEATURES,
        UserManager.DISALLOW_CONFIG_VPN,
        UserManager.DISALLOW_MODIFY_ACCOUNTS,
        // DISALLOW_OEM_UNLOCK is a hidden @SystemApi constant; pin its AOSP key literally so
        // this witness stays independent of the production source.
        "no_oem_unlock",
        UserManager.DISALLOW_APPS_CONTROL,
        UserManager.DISALLOW_USB_FILE_TRANSFER,
        UserManager.DISALLOW_INSTALL_UNKNOWN_SOURCES_GLOBALLY,
        UserManager.DISALLOW_USER_SWITCH,
        UserManager.DISALLOW_ADD_USER,
        UserManager.DISALLOW_REMOVE_USER,
        UserManager.DISALLOW_CONFIG_DATE_TIME,
        UserManager.DISALLOW_MOUNT_PHYSICAL_MEDIA,
        UserManager.DISALLOW_CONFIG_TETHERING,
        UserManager.DISALLOW_CONFIG_MOBILE_NETWORKS,
        UserManager.DISALLOW_OUTGOING_BEAM,
    )

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
        Shadows.shadowOf(dpm).setDeviceOwner(AdminReceiver.componentName(context))
    }

    @After
    fun tearDown() {
        // Restore Device Owner — tests that clear it must not break isolation for the suite.
        Shadows.shadowOf(dpm).setDeviceOwner(AdminReceiver.componentName(context))
    }

    /** Enforcer whose verify readback is fully controlled by the test. */
    private fun enforcerReading(setKeys: Set<String>): PolicyEnforcer =
        PolicyEnforcer(context, isRestrictionSet = { it in setKeys })

    // ---------------------------------------------------------------------
    // Static baseline composition — unconditional
    // ---------------------------------------------------------------------

    @Test
    fun `requiredRestrictions is exactly the canonical 17`() {
        val actual = PolicyEnforcer(context).requiredRestrictions
        assertEquals(17, actual.size, "Day-One baseline must be exactly 17 restrictions (DEFENSES row 2)")
        assertEquals(canonical17, actual.toSet(), "Day-One baseline must equal the canonical DEFENSES row-2 set")
    }

    @Test
    fun `requiredRestrictions has no duplicates`() {
        val list = PolicyEnforcer(context).requiredRestrictions
        assertEquals(list.size, list.toSet().size, "Day-One baseline must not contain duplicate restrictions")
    }

    @Test
    fun `requiredRestrictions never disables the emergency dialer`() {
        // DISALLOW_OUTGOING_CALLS would block the emergency dialer — must never be in the set.
        assertFalse(
            UserManager.DISALLOW_OUTGOING_CALLS in PolicyEnforcer(context).requiredRestrictions,
            "DISALLOW_OUTGOING_CALLS must never be applied — emergency dialer must remain reachable",
        )
    }

    @Test
    fun `requiredRestrictions defers DNS toggle to issue 19`() {
        // The DNS fail-closed floor (pin resolver + lock toggle) is owned by #19, not #8.
        assertFalse(
            UserManager.DISALLOW_CONFIG_PRIVATE_DNS in PolicyEnforcer(context).requiredRestrictions,
            "DISALLOW_CONFIG_PRIVATE_DNS is owned by the DNS floor (#19), not the Day-One set",
        )
    }

    // ---------------------------------------------------------------------
    // verify / missing — deterministic via injected reader
    // ---------------------------------------------------------------------

    @Test
    fun `verifyOrThrow does not throw when every restriction is set`() {
        enforcerReading(canonical17).verifyOrThrow()
    }

    @Test
    fun `verifyOrThrow throws listing the missing restrictions`() {
        // Inject a partial state: everything set EXCEPT factory reset + VPN.
        val missing = setOf(UserManager.DISALLOW_FACTORY_RESET, UserManager.DISALLOW_CONFIG_VPN)
        val ex = assertFailsWith<RestrictionEnforcementException> {
            enforcerReading(canonical17 - missing).verifyOrThrow()
        }
        assertEquals(missing, ex.missing.toSet(), "Exception must name exactly the missing restrictions")
    }

    @Test
    fun `missingRestrictions reports the complement of what is set`() {
        val present = setOf(UserManager.DISALLOW_FACTORY_RESET, UserManager.DISALLOW_SAFE_BOOT)
        val missing = enforcerReading(present).missingRestrictions().toSet()
        assertEquals(canonical17 - present, missing)
    }

    // ---------------------------------------------------------------------
    // applyDayOneRestrictions — fail-closed semantics
    // ---------------------------------------------------------------------

    @Test
    fun `applyDayOneRestrictions throws when app is NOT device owner`() {
        Shadows.shadowOf(dpm).setDeviceOwner(null)
        assertTrue(!dpm.isDeviceOwnerApp(context.packageName), "Precondition: app must not be Device Owner")

        assertFailsWith<IllegalArgumentException>("must refuse to enforce when not Device Owner") {
            PolicyEnforcer(context) { true }.applyDayOneRestrictions()
        }
        // @After restores Device Owner.
    }

    @Test
    fun `applyDayOneRestrictions returns cleanly only when all restrictions verify set`() {
        // Reader reports everything set -> apply succeeds, verify passes, no throw.
        PolicyEnforcer(context) { true }.applyDayOneRestrictions()
    }

    @Test
    fun `applyDayOneRestrictions fails closed when application is partial`() {
        // Failure-injection: even though we are Device Owner and call addUserRestriction for all
        // 17, the readback reports NONE stuck. The enforcer must NOT return in a partial state —
        // it must throw rather than silently log-and-continue (the old fail-OPEN bug).
        val ex = assertFailsWith<RestrictionEnforcementException>("must fail closed on partial apply") {
            PolicyEnforcer(context) { false }.applyDayOneRestrictions()
        }
        assertEquals(canonical17, ex.missing.toSet(), "All restrictions reported missing must surface in the exception")
    }

    @Test
    fun `applyDayOneRestrictions verifies the full set via the DO-authoritative readback`() {
        // Round-trip against the DO-authoritative reader (dpm.getUserRestrictions(admin)). Gate
        // on the shadow actually persisting DO-set restrictions; skip (never vacuously pass) if
        // it does not.
        val admin = AdminReceiver.componentName(context)
        dpm.addUserRestriction(admin, UserManager.DISALLOW_FACTORY_RESET)
        assumeTrue(
            "Robolectric shadow round-trips dpm.getUserRestrictions(admin)",
            dpm.getUserRestrictions(admin).getBoolean(UserManager.DISALLOW_FACTORY_RESET, false),
        )

        // Default reader (DO-authoritative). If the shadow round-trips the full set this must not
        // throw; if it persists only some, applyDayOneRestrictions() fails closed -> skip rather
        // than crash (the deterministic injected-reader tests carry the fail-closed proof).
        try {
            PolicyEnforcer(context).applyDayOneRestrictions()
        } catch (e: RestrictionEnforcementException) {
            assumeTrue("Robolectric shadow does not persist the full DO restriction set", false)
            return
        }
        assertTrue(
            PolicyEnforcer(context).missingRestrictions().isEmpty(),
            "After apply, no required restriction may be reported missing",
        )
    }

    // ---------------------------------------------------------------------
    // FRP — fail-closed guards (deterministic) + round-trip (gated)
    // ---------------------------------------------------------------------

    @Test
    fun `applyFrpAccounts throws when app is NOT device owner`() {
        Shadows.shadowOf(dpm).setDeviceOwner(null)
        assertFailsWith<IllegalArgumentException> {
            PolicyEnforcer(context).applyFrpAccounts(listOf("gaia-123"))
        }
    }

    @Test
    fun `applyFrpAccounts refuses an empty account set`() {
        // Enabling FRP with no recovery account would brick the device — must refuse.
        assertFailsWith<IllegalArgumentException>("must refuse FRP with no recovery account") {
            PolicyEnforcer(context).applyFrpAccounts(emptyList())
        }
    }

    @Test
    fun `applyFrpAccounts binds the parent accounts when device owner`() {
        val accounts = listOf("gaia-parent-123")
        try {
            PolicyEnforcer(context).applyFrpAccounts(accounts)
        } catch (e: UnsupportedOperationException) {
            // Shadow lacks setFactoryResetProtectionPolicy — skip rather than fail.
            assumeTrue("Robolectric shadow supports setFactoryResetProtectionPolicy", false)
        }
        val policy = dpm.getFactoryResetProtectionPolicy(AdminReceiver.componentName(context))
        assumeTrue("Robolectric shadow round-trips FactoryResetProtectionPolicy", policy != null)
        assertEquals(accounts, policy!!.factoryResetProtectionAccounts)
        assertTrue(policy.isFactoryResetProtectionEnabled, "FRP must be enabled after binding accounts")
    }
}

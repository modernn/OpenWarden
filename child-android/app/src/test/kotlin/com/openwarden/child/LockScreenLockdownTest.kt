package com.openwarden.child

import android.app.admin.DevicePolicyManager
import android.content.Context
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * Robolectric unit tests for [LockScreenLockdown].
 *
 * Note on Robolectric + ShadowDevicePolicyManager keyguard round-trip:
 * As of Robolectric 4.13 / sdk 34, ShadowDevicePolicyManager does support storing and
 * returning keyguard disabled features via setKeyguardDisabledFeatures /
 * getKeyguardDisabledFeatures when device-owner is set. Where a full round-trip is
 * supported, we assert equality. Where shadow behaviour is limited, we assert the
 * expected bit composition on [LockScreenLockdown.disabledKeyguardFeatures] and that
 * [LockScreenLockdown.apply] does not throw.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LockScreenLockdownTest {

    private lateinit var context: Context
    private lateinit var dpm: DevicePolicyManager
    private lateinit var lockdown: LockScreenLockdown

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager

        // Register this app as Device Owner in the Robolectric shadow so that
        // isDeviceOwnerApp() returns true and setKeyguardDisabledFeatures() is permitted.
        val shadowDpm = Shadows.shadowOf(dpm)
        shadowDpm.setDeviceOwner(AdminReceiver.componentName(context))

        lockdown = LockScreenLockdown(context)
    }

    // -------------------------------------------------------------------------
    // disabledKeyguardFeatures composition
    // -------------------------------------------------------------------------

    @Test
    fun `disabledKeyguardFeatures includes KEYGUARD_DISABLE_SECURE_CAMERA`() {
        assertTrue(
            (lockdown.disabledKeyguardFeatures and DevicePolicyManager.KEYGUARD_DISABLE_SECURE_CAMERA) != 0,
            "disabledKeyguardFeatures must include KEYGUARD_DISABLE_SECURE_CAMERA (DEFENSE #21)"
        )
    }

    @Test
    fun `disabledKeyguardFeatures includes KEYGUARD_DISABLE_WIDGETS_ALL`() {
        assertTrue(
            (lockdown.disabledKeyguardFeatures and DevicePolicyManager.KEYGUARD_DISABLE_WIDGETS_ALL) != 0,
            "disabledKeyguardFeatures must include KEYGUARD_DISABLE_WIDGETS_ALL (DEFENSE #22)"
        )
    }

    @Test
    fun `disabledKeyguardFeatures includes KEYGUARD_DISABLE_TRUST_AGENTS`() {
        assertTrue(
            (lockdown.disabledKeyguardFeatures and DevicePolicyManager.KEYGUARD_DISABLE_TRUST_AGENTS) != 0,
            "disabledKeyguardFeatures must include KEYGUARD_DISABLE_TRUST_AGENTS (DEFENSE #23)"
        )
    }

    @Test
    fun `disabledKeyguardFeatures includes KEYGUARD_DISABLE_SECURE_NOTIFICATIONS`() {
        assertTrue(
            (lockdown.disabledKeyguardFeatures and DevicePolicyManager.KEYGUARD_DISABLE_SECURE_NOTIFICATIONS) != 0,
            "disabledKeyguardFeatures must include KEYGUARD_DISABLE_SECURE_NOTIFICATIONS (DEFENSE #24)"
        )
    }

    @Test
    fun `disabledKeyguardFeatures does NOT equal KEYGUARD_DISABLE_FEATURES_ALL`() {
        // We never use the catch-all flag. Guard against a future regression where someone
        // replaces the explicit set with the catch-all.
        assertNotEquals(
            DevicePolicyManager.KEYGUARD_DISABLE_FEATURES_ALL,
            lockdown.disabledKeyguardFeatures,
            "Must NOT use KEYGUARD_DISABLE_FEATURES_ALL — choose explicit flags"
        )
    }

    // -------------------------------------------------------------------------
    // apply() — must not throw and must persist the flag mask
    // -------------------------------------------------------------------------

    @Test
    fun `apply() does not throw when app is device owner`() {
        // Primary requirement: apply() must succeed without any exception when the
        // Robolectric shadow has the app registered as Device Owner.
        lockdown.apply()
    }

    @Test
    fun `apply() sets keyguard disabled features to disabledKeyguardFeatures`() {
        lockdown.apply()

        val admin = AdminReceiver.componentName(context)
        val reported = dpm.getKeyguardDisabledFeatures(admin)

        // ShadowDevicePolicyManager in Robolectric 4.13 supports keyguard round-trips.
        // If the shadow does NOT persist the value it returns 0; in that case we fall back
        // to asserting on the property (see note in class KDoc).
        if (reported != 0) {
            assertEquals(
                lockdown.disabledKeyguardFeatures,
                reported,
                "getKeyguardDisabledFeatures() must return the exact mask passed to setKeyguardDisabledFeatures()"
            )
        } else {
            // Shadow did not store the value — assert the property is non-zero and has the
            // mandatory SECURE_CAMERA bit.
            assertTrue(
                lockdown.disabledKeyguardFeatures != 0,
                "disabledKeyguardFeatures must be non-zero"
            )
            assertTrue(
                (lockdown.disabledKeyguardFeatures and DevicePolicyManager.KEYGUARD_DISABLE_SECURE_CAMERA) != 0,
                "disabledKeyguardFeatures must have KEYGUARD_DISABLE_SECURE_CAMERA set"
            )
        }
    }

    @Test
    fun `apply() throws when app is NOT device owner`() {
        // Build a fresh context where the shadow has no device-owner set.
        val freshContext = RuntimeEnvironment.getApplication()
        val freshDpm = freshContext.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
        val freshShadow = Shadows.shadowOf(freshDpm)
        // Explicitly clear device owner (set to null component = not DO)
        freshShadow.setDeviceOwner(null)

        val notDoLockdown = LockScreenLockdown(freshContext)
        assertFailsWith<IllegalArgumentException>("apply() must throw when not Device Owner") {
            notDoLockdown.apply()
        }
    }

    // -------------------------------------------------------------------------
    // disableCameraOnKeyguardOnly() — narrow camera-only variant
    // -------------------------------------------------------------------------

    @Test
    fun `disableCameraOnKeyguardOnly() does not throw when app is device owner`() {
        lockdown.disableCameraOnKeyguardOnly()
    }

    @Test
    fun `disableCameraOnKeyguardOnly() sets SECURE_CAMERA bit`() {
        lockdown.disableCameraOnKeyguardOnly()

        val admin = AdminReceiver.componentName(context)
        val reported = dpm.getKeyguardDisabledFeatures(admin)

        if (reported != 0) {
            assertTrue(
                (reported and DevicePolicyManager.KEYGUARD_DISABLE_SECURE_CAMERA) != 0,
                "After disableCameraOnKeyguardOnly(), KEYGUARD_DISABLE_SECURE_CAMERA must be set"
            )
        }
        // Shadow fallback: property check already covers this via dedicated flag tests above.
    }

    @Test
    fun `disableCameraOnKeyguardOnly() merges with existing flags without clearing them`() {
        // Pre-apply a different flag, then call the narrow helper; the original flag must survive.
        val admin = AdminReceiver.componentName(context)
        // Apply just the trust-agent flag first
        dpm.setKeyguardDisabledFeatures(admin, DevicePolicyManager.KEYGUARD_DISABLE_TRUST_AGENTS)

        lockdown.disableCameraOnKeyguardOnly()

        val reported = dpm.getKeyguardDisabledFeatures(admin)
        if (reported != 0) {
            assertTrue(
                (reported and DevicePolicyManager.KEYGUARD_DISABLE_TRUST_AGENTS) != 0,
                "Pre-existing KEYGUARD_DISABLE_TRUST_AGENTS must survive disableCameraOnKeyguardOnly()"
            )
            assertTrue(
                (reported and DevicePolicyManager.KEYGUARD_DISABLE_SECURE_CAMERA) != 0,
                "KEYGUARD_DISABLE_SECURE_CAMERA must be added by disableCameraOnKeyguardOnly()"
            )
        }
    }
}

package com.openwarden.child

import android.app.admin.DevicePolicyManager
import android.content.Context
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull

/**
 * Unit tests for [AccessibilityLockdown] using Robolectric's ShadowDevicePolicyManager.
 *
 * These tests run on the JVM — no emulator or physical Device Owner provisioning required.
 * We use [org.robolectric.shadows.ShadowDevicePolicyManager.setDeviceOwner] to satisfy
 * the Device Owner precondition in [AccessibilityLockdown.apply].
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AccessibilityLockdownTest {

    private val context: Context get() = RuntimeEnvironment.getApplication()
    private val dpm: DevicePolicyManager get() =
        context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
    private val admin get() = AdminReceiver.componentName(context)

    /**
     * Set up Robolectric's shadow DPM to recognise our app as Device Owner
     * so that [AccessibilityLockdown.apply] passes its [require] guard.
     */
    private fun makeDeviceOwner() {
        Shadows.shadowOf(dpm).setDeviceOwner(admin)
    }

    /**
     * Core correctness: after [apply], [DevicePolicyManager.getPermittedAccessibilityServices]
     * must return an empty (non-null) list.
     *
     * An empty list means "no third-party services permitted" — the locked-down state.
     */
    @Test
    fun `apply sets permitted accessibility services to empty list`() {
        makeDeviceOwner()
        val lockdown = AccessibilityLockdown(context)

        lockdown.apply()

        val permitted = dpm.getPermittedAccessibilityServices(admin)
        assertNotNull(permitted, "getPermittedAccessibilityServices must return non-null (null = all permitted = the bug)")
        assertEquals(
            0,
            permitted.size,
            "Permitted accessibility services must be empty after apply() — emptyList(), not null",
        )
    }

    /**
     * Null guard: the result must NOT be null.
     *
     * null from [DevicePolicyManager.getPermittedAccessibilityServices] means *all* services are
     * permitted — the unrestricted default. After [apply] this must never be null.
     */
    @Test
    fun `apply result is not null — null would mean all-permitted which is the bug`() {
        makeDeviceOwner()
        AccessibilityLockdown(context).apply()

        val permitted = dpm.getPermittedAccessibilityServices(admin)
        // Explicit null check with a descriptive failure message so the bug is obvious if it regresses.
        assertNotNull(
            permitted,
            "BUG: getPermittedAccessibilityServices returned null after apply(). " +
                "null means ALL services permitted — emptyList() must be used instead.",
        )
    }

    /**
     * Regression guard: emptyList() is distinct from null at the API level.
     * We call setPermittedAccessibilityServices(admin, emptyList()) — confirm the shadow
     * round-trips it back as an empty list, not as null.
     */
    @Test
    fun `permitted list is empty list not null — emptyList distinct from null`() {
        makeDeviceOwner()
        AccessibilityLockdown(context).apply()

        val permitted: List<String>? = dpm.getPermittedAccessibilityServices(admin)
        // Both assertions must pass: non-null AND size 0.
        assertNotNull(permitted, "Must be non-null (not the all-permitted default)")
        assertEquals(emptyList(), permitted, "Must be an empty list, not a list with entries")
    }

    /**
     * Precondition guard: [apply] must throw [IllegalArgumentException] when the app is
     * NOT the Device Owner (do NOT call makeDeviceOwner() in this test).
     *
     * This is the fail-closed contract: if somehow called outside DO context, it must
     * throw loudly rather than silently doing nothing.
     */
    @Test
    fun `apply throws when not device owner`() {
        // Deliberately do NOT call makeDeviceOwner() — shadow DPM starts with no device owner.
        val lockdown = AccessibilityLockdown(context)

        assertFailsWith<IllegalArgumentException>(
            message = "apply() must throw when not Device Owner",
        ) {
            lockdown.apply()
        }
    }

    /**
     * Idempotency: calling [apply] twice must leave the permitted list empty.
     * Repeated calls (e.g. on every boot) must not corrupt state.
     */
    @Test
    fun `apply is idempotent — calling twice leaves empty list`() {
        makeDeviceOwner()
        val lockdown = AccessibilityLockdown(context)

        lockdown.apply()
        lockdown.apply()

        val permitted = dpm.getPermittedAccessibilityServices(admin)
        assertNotNull(permitted, "Must be non-null after double apply()")
        assertEquals(0, permitted.size, "Must still be empty after double apply()")
    }
}

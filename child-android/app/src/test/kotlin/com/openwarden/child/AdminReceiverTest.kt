package com.openwarden.child

import android.app.admin.DeviceAdminReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AdminReceiverTest {
    /**
     * Verify that AdminReceiver is correctly declared as a DeviceAdminReceiver.
     * This test runs on the JVM and does not require a Device Owner provisioned emulator.
     */
    @Test
    fun testAdminReceiverIsDeviceAdminReceiver() {
        val receiver = AdminReceiver()
        assertTrue(
            receiver is DeviceAdminReceiver,
            "AdminReceiver must extend DeviceAdminReceiver",
        )
    }

    /**
     * Verify that AdminReceiver.componentName() returns the correct ComponentName.
     * The ComponentName must resolve to the package name and the receiver's fully qualified class name.
     */
    @Test
    fun testComponentNameResolution() {
        val context = RuntimeEnvironment.getApplication()
        val componentName = AdminReceiver.componentName(context)

        assertNotNull(componentName, "componentName() must not return null")
        assertEquals(
            context.packageName,
            componentName.packageName,
            "ComponentName package must be the application's package",
        )
        assertEquals(
            "com.openwarden.child.AdminReceiver",
            componentName.className,
            "ComponentName class must be AdminReceiver's fully qualified name",
        )
    }

    /**
     * Verify ComponentName equality using the factory method.
     * This ensures the receiver can be correctly identified in DevicePolicyManager APIs.
     */
    @Test
    fun testComponentNameEquals() {
        val context = RuntimeEnvironment.getApplication()
        val componentName = AdminReceiver.componentName(context)
        val expectedName = ComponentName(context, AdminReceiver::class.java)

        // Both should point to the same receiver, though package names differ due to debug suffix
        assertEquals(
            "com.openwarden.child.AdminReceiver",
            componentName.className,
            "className must match expected",
        )
        assertEquals(
            expectedName.className,
            componentName.className,
            "className from both methods must match",
        )
    }

    /**
     * Admin-enable must bring enforcement up (issue #75 / ADR-021 D2 trigger 1). `onEnabled` fires
     * when the device-admin becomes active; it must start the foreground [PolicyService] so the
     * watchdog begins re-asserting. Deterministic on the JVM — asserts the service start intent,
     * not a real Device Owner apply.
     */
    @Test
    fun `onEnabled starts the enforcement service`() {
        val app = RuntimeEnvironment.getApplication()

        AdminReceiver().onEnabled(app, Intent())

        val started = shadowOf(app).nextStartedService
        assertNotNull(started, "admin-enable must start PolicyService so enforcement comes up")
        assertEquals(
            PolicyService::class.java.name,
            started.component?.className,
            "the service started on admin-enable must be PolicyService",
        )
    }

    /**
     * Fail-closed-but-alive at provisioning (issue #75 / ADR-020/021). `onProfileProvisioningComplete`
     * applies the Day-One baseline in a try/catch and starts the FGS in a `finally`. Here there is
     * no Device Owner, so `applyDayOneRestrictions()` throws `require(isDeviceOwnerApp)` — the
     * service MUST still start so the watchdog is alive to retry. A provisioning apply failure must
     * never leave enforcement dead (that would be failing *open*).
     */
    @Test
    fun `onProfileProvisioningComplete starts the service even when day-one apply throws`() {
        val app = RuntimeEnvironment.getApplication()

        // No Device Owner set → applyDayOneRestrictions() throws; the finally block must still run.
        AdminReceiver().onProfileProvisioningComplete(app, Intent())

        val started = shadowOf(app).nextStartedService
        assertNotNull(
            started,
            "provisioning-complete must start PolicyService even when the day-one apply fails",
        )
        assertEquals(
            PolicyService::class.java.name,
            started.component?.className,
            "the service started at provisioning must be PolicyService",
        )
    }
}

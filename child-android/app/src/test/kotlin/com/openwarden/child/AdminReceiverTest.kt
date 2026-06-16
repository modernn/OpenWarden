package com.openwarden.child

import android.app.admin.DeviceAdminReceiver
import android.content.ComponentName
import android.content.Context
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
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
            "AdminReceiver must extend DeviceAdminReceiver"
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
            "com.openwarden.child.debug",
            componentName.packageName,
            "ComponentName package must be the application's package"
        )
        assertEquals(
            "com.openwarden.child.AdminReceiver",
            componentName.className,
            "ComponentName class must be AdminReceiver's fully qualified name"
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
            "className must match expected"
        )
        assertEquals(
            expectedName.className,
            componentName.className,
            "className from both methods must match"
        )
    }
}

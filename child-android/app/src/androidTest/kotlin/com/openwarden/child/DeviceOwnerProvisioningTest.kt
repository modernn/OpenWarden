package com.openwarden.child

import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Instrumented Device Owner provisioning test.
 *
 * This test REQUIRES the app to be provisioned as Device Owner on the emulator.
 * It is NOT run automatically in CI (requires manual provisioning via `adb shell dpm set-device-owner`).
 *
 * Run instructions:
 * 1. Boot a fresh AVD: `emulator -avd openwarden-pixel7-api35 -no-snapshot`
 * 2. Wait for boot to complete.
 * 3. Build and install the debug app: `./gradlew installDebugAndroidTest`
 * 4. Provision as Device Owner: `adb shell dpm set-device-owner com.openwarden.child.debug/com.openwarden.child.AdminReceiver`
 * 5. Run this test: `./gradlew connectedAndroidTest -DtestCases=com.openwarden.child.DeviceOwnerProvisioningTest`
 *
 * Expected outcome:
 * - isDeviceOwnerApp() returns true
 * - isAdminActive(AdminReceiver.componentName()) returns true
 * - No crashes or exceptions
 */
@RunWith(AndroidJUnit4::class)
class DeviceOwnerProvisioningTest {
    private lateinit var context: Context
    private lateinit var dpm: DevicePolicyManager
    private lateinit var adminComponentName: ComponentName

    @Before
    fun setUp() {
        context = InstrumentationRegistry.getInstrumentation().targetContext
        dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
        adminComponentName = AdminReceiver.componentName(context)
    }

    /**
     * Core acceptance test: verify the app is provisioned as Device Owner.
     * This is the primary criterion for issue #7.
     */
    @Test
    fun testIsDeviceOwnerApp() {
        assertTrue(
            dpm.isDeviceOwnerApp(context.packageName),
            "App must be provisioned as Device Owner (run: adb shell dpm set-device-owner)"
        )
    }

    /**
     * Verify AdminReceiver is the active admin.
     * Device Owner implies that AdminReceiver is an active admin.
     */
    @Test
    fun testAdminReceiverIsActiveAdmin() {
        assertTrue(
            dpm.isAdminActive(adminComponentName),
            "AdminReceiver must be an active admin"
        )
    }

    /**
     * Verify the ComponentName resolves correctly.
     * Ensures the receiver is correctly addressable by the DevicePolicyManager.
     */
    @Test
    fun testComponentNameMatches() {
        assertNotNull(adminComponentName, "componentName() must not return null")
        assertEquals(
            "com.openwarden.child.AdminReceiver",
            adminComponentName.className,
            "ComponentName className must point to AdminReceiver"
        )
    }

    /**
     * Verify that AdminReceiver is declared in the manifest.
     * This is a sanity check that the receiver is properly exported.
     */
    @Test
    fun testAdminReceiverManifestDeclaration() {
        val componentInfo = context.packageManager.getReceiverInfo(
            adminComponentName,
            0
        )
        assertNotNull(componentInfo, "AdminReceiver must be declared in AndroidManifest.xml")
        assertEquals(
            "com.openwarden.child.AdminReceiver",
            componentInfo.name,
            "Declared receiver name must match our AdminReceiver"
        )
    }
}

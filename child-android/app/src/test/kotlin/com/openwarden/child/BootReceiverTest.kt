package com.openwarden.child

import android.content.Intent
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/**
 * Boot-path wiring for the enforcement lifecycle (issue #75 / ADR-021 D2 trigger 1).
 *
 * The red-team finding behind #75 was "PolicyService not observed running after a reboot". That
 * turned out to be a test-methodology false negative — an `adb install -r` leaves the app in
 * Android's "stopped" state, which suppresses `BOOT_COMPLETED` until the first manual launch, and
 * the finding was taken on a non-Device-Owner device where the enforcement APIs are no-ops
 * regardless. But the production wiring itself — [BootReceiver] starting the foreground
 * [PolicyService] on the boot broadcast — was never regression-tested. This pins that link
 * deterministically on the JVM.
 *
 * Scope note: this proves the *service is (re)started on boot*, which is what "enforcement comes
 * back up after a reboot" reduces to at the wiring level. The device-level restriction readback
 * (the baseline is actually re-applied) rides ADR-020's `lockNow()`-on-verify-gap and the
 * `connectedAndroidTest` suite — it cannot be a JVM/CI assertion because a Device Owner enforcing
 * `DISALLOW_DEBUGGING_FEATURES` severs adb (#30 criterion 2 / #124).
 *
 * Both declared boot actions must bring enforcement back up: `BOOT_COMPLETED` (normal boot) and
 * `LOCKED_BOOT_COMPLETED` (direct-boot, before the user unlocks). [BootReceiver] is intentionally
 * unconditional — any delivered boot broadcast restarts the FGS.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class BootReceiverTest {
    private fun assertStartsPolicyService(action: String) {
        // Each test method runs in a fresh Robolectric application, so the started-service queue
        // starts empty — the only service captured is the one this broadcast starts.
        val app = RuntimeEnvironment.getApplication()

        BootReceiver().onReceive(app, Intent(action))

        val started = shadowOf(app).nextStartedService
        assertNotNull(
            started,
            "boot broadcast $action must start a service — enforcement must come back up after reboot",
        )
        assertEquals(
            PolicyService::class.java.name,
            started.component?.className,
            "the service started on boot must be PolicyService",
        )
    }

    @Test
    fun `BOOT_COMPLETED starts the enforcement service`() {
        assertStartsPolicyService(Intent.ACTION_BOOT_COMPLETED)
    }

    @Test
    fun `LOCKED_BOOT_COMPLETED starts the enforcement service (direct-boot)`() {
        assertStartsPolicyService(Intent.ACTION_LOCKED_BOOT_COMPLETED)
    }
}

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
 * [BootReceiver] is declared for two boot actions and is intentionally unconditional — any
 * delivered boot broadcast restarts the FGS. This test pins that *receiver wiring* for both
 * declared actions.
 *
 * Caveat — do NOT read the `LOCKED_BOOT_COMPLETED` case as a working direct-boot guarantee. That
 * action is only actually delivered in the locked / pre-unlock (direct-boot) window to components
 * marked `android:directBootAware="true"`, and **no component here is direct-boot-aware today**.
 * So on real hardware the app does not run in that window; Robolectric does not model direct-boot
 * gating, so this test proves only that the receiver *would* start PolicyService when the broadcast
 * is delivered — NOT that enforcement comes up before first unlock. Whether the control plane
 * should be `directBootAware` is a separate, unratified security-posture decision (follow-up).
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
    fun `LOCKED_BOOT_COMPLETED is wired to start the enforcement service`() {
        // Wiring only: effective locked-window delivery needs android:directBootAware (not set
        // today) — see the class kdoc. This asserts the receiver would start PolicyService when the
        // broadcast is delivered, NOT that enforcement runs before first unlock.
        assertStartsPolicyService(Intent.ACTION_LOCKED_BOOT_COMPLETED)
    }
}

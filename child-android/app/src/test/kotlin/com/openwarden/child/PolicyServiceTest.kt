package com.openwarden.child

import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Robolectric smoke test for [PolicyService] lifecycle wiring (issue #11 / ADR-021).
 *
 * Exercises `onCreate` (start foreground, build the watchdog, register the default-network
 * callback, schedule the periodic tick) and `onDestroy` (remove the tick, unregister the
 * callback, stop the API server). It deliberately does NOT call `onStartCommand`, which would
 * start the real Ktor server and bind a socket — the watchdog re-assert logic itself is covered
 * deterministically by [PolicyWatchdogTest]. Reaching the end without an exception proves the
 * lateinit ordering, callback registration, and teardown are wired correctly (no leaked callback,
 * no NPE).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PolicyServiceTest {

    @Test
    fun `onCreate then onDestroy wires and tears down without throwing`() {
        val controller = Robolectric.buildService(PolicyService::class.java)
        controller.create()
        controller.destroy()
    }
}

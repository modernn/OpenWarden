package com.openwarden.child

import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Unit tests for [ProfileGuard] — the profile-escape detection backstop (ADR-022 / issue #12).
 *
 * The profile count is injected as a seam so the trip logic is proven deterministically without a
 * live device. Runs under Robolectric only so `android.util.Log` resolves in the default callback.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ProfileGuardTest {

    @Test
    fun `check is quiet at the baseline single profile and does not lock`() {
        var flagged: Int? = null
        var contained = 0
        val guard = ProfileGuard(profileCount = { 1 }, onExtraProfile = { flagged = it }, contain = { contained++ })

        assertFalse(guard.check(), "A single (primary) profile is the expected, locked-down state")
        assertEquals(null, flagged, "No extra-profile callback may fire at the baseline")
        assertEquals(0, contained, "The device must NOT be locked at the baseline")
    }

    @Test
    fun `check flags AND locks when an extra profile appears`() {
        var flagged: Int? = null
        var contained = 0
        val guard = ProfileGuard(profileCount = { 2 }, onExtraProfile = { flagged = it }, contain = { contained++ })

        assertTrue(guard.check(), "A second profile (Private Space / managed profile) must be flagged")
        assertEquals(2, flagged, "The callback must receive the observed profile count")
        assertEquals(1, contained, "An extra profile is a full allowlist bypass — must trigger lockNow() containment")
    }

    @Test
    fun `check records before it contains`() {
        // The warning must be logged before the lock so the detection is recorded even if the
        // device locks (and a future event-log write happens) on the same tick.
        val order = mutableListOf<String>()
        val guard = ProfileGuard(
            profileCount = { 3 },
            onExtraProfile = { order += "flag" },
            contain = { order += "contain" },
        )

        assertTrue(guard.check())
        assertEquals(listOf("flag", "contain"), order, "must record the detection, then contain")
    }

    @Test
    fun `baseline is a single primary profile`() {
        assertEquals(1, ProfileGuard.BASELINE_PROFILES, "A locked-down child device has exactly one profile")
    }

    @Test
    fun `forContext wires the real UserManager without throwing`() {
        // Construction must not require a privileged caller — it only reads the profile count lazily.
        val guard = ProfileGuard.forContext(RuntimeEnvironment.getApplication())
        assertNotNull(guard)
    }
}

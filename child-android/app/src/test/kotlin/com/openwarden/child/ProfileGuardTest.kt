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
    fun `check is quiet at the baseline single profile`() {
        var flagged: Int? = null
        val guard = ProfileGuard(profileCount = { 1 }, onExtraProfile = { flagged = it })

        assertFalse(guard.check(), "A single (primary) profile is the expected, locked-down state")
        assertEquals(null, flagged, "No extra-profile callback may fire at the baseline")
    }

    @Test
    fun `check flags when an extra profile appears`() {
        var flagged: Int? = null
        val guard = ProfileGuard(profileCount = { 2 }, onExtraProfile = { flagged = it })

        assertTrue(guard.check(), "A second profile (Private Space / managed profile) must be flagged")
        assertEquals(2, flagged, "The callback must receive the observed profile count")
    }

    @Test
    fun `check flags any count above baseline`() {
        var calls = 0
        val guard = ProfileGuard(profileCount = { 3 }, onExtraProfile = { calls++ })

        assertTrue(guard.check())
        assertEquals(1, calls, "An extra profile must be surfaced exactly once per check")
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

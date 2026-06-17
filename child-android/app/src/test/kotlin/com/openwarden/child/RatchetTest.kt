package com.openwarden.child

import org.junit.Test
import kotlin.test.assertEquals

/**
 * Pure no-contact ratchet math (ADR-024). No Android, no I/O — the clock-driven tier + the
 * fail-closed silence calculation are deterministic (the issue's "clock-driven ratchet test").
 */
class RatchetTest {

    private val stale = Ratchet.RATCHET_STALE_MS
    private val strict = Ratchet.RATCHET_STRICT_MS

    private fun markers(wall: Long?, elapsed: Long?, hw: Long? = wall) =
        Ratchet.Markers(lastContactWallMs = wall, lastContactElapsedMs = elapsed, wallHighWaterMs = hw)

    @Test
    fun `tier thresholds are inclusive at the boundary`() {
        assertEquals(Ratchet.Tier.FRESH, Ratchet.tierFor(0))
        assertEquals(Ratchet.Tier.FRESH, Ratchet.tierFor(stale - 1))
        assertEquals(Ratchet.Tier.STALE, Ratchet.tierFor(stale))
        assertEquals(Ratchet.Tier.STALE, Ratchet.tierFor(strict - 1))
        assertEquals(Ratchet.Tier.STRICT, Ratchet.tierFor(strict))
        assertEquals(Ratchet.Tier.STRICT, Ratchet.tierFor(Ratchet.STRICT_SILENCE))
    }

    @Test
    fun `in-session silence uses the monotonic elapsed delta`() {
        // Same boot session (now elapsed >= contact elapsed); silence = elapsed delta.
        val m = markers(wall = 1_000_000L, elapsed = 1_000L, hw = 1_000_000L)
        assertEquals(Ratchet.Tier.FRESH, Ratchet.tierFor(Ratchet.silenceMs(m, true, 1_000_000L + 1_000L, 1_000L + 1_000L)))
        assertEquals(Ratchet.Tier.STALE, Ratchet.tierFor(Ratchet.silenceMs(m, true, 1_000_000L + stale, 1_000L + stale)))
        assertEquals(Ratchet.Tier.STRICT, Ratchet.tierFor(Ratchet.silenceMs(m, true, 1_000_000L + strict, 1_000L + strict)))
    }

    @Test
    fun `provisioned child with no contact marker is STRICT (anomaly)`() {
        assertEquals(Ratchet.STRICT_SILENCE, Ratchet.silenceMs(markers(null, null, null), provisioned = true, 5L, 5L))
    }

    @Test
    fun `unprovisioned child with no marker is FRESH (pre-genesis, nothing to ratchet)`() {
        assertEquals(Ratchet.Tier.FRESH, Ratchet.tierFor(Ratchet.silenceMs(markers(null, null, null), provisioned = false, 5L, 5L)))
    }

    @Test
    fun `wall clock below high-water in-session is a backward roll - STRICT`() {
        // elapsed advanced (same session) but wall pushed below the high-water = tamper.
        val m = markers(wall = 2_000_000L, elapsed = 1_000L, hw = 5_000_000L)
        assertEquals(Ratchet.STRICT_SILENCE, Ratchet.silenceMs(m, provisioned = true, nowWallMs = 4_000_000L, nowElapsedMs = 2_000L))
    }

    @Test
    fun `reboot (elapsed regressed) falls back to wall delta when not rolled back`() {
        // now elapsed < contact elapsed => reboot; wall advanced by `strict` and >= high-water.
        val m = markers(wall = 1_000_000L, elapsed = 9_000_000L, hw = 1_000_000L)
        assertEquals(Ratchet.Tier.STRICT, Ratchet.tierFor(Ratchet.silenceMs(m, provisioned = true, nowWallMs = 1_000_000L + strict, nowElapsedMs = 500L)))
    }

    @Test
    fun `reboot with wall below high-water is STRICT (rollback across reboot)`() {
        val m = markers(wall = 3_000_000L, elapsed = 9_000_000L, hw = 8_000_000L)
        assertEquals(Ratchet.STRICT_SILENCE, Ratchet.silenceMs(m, provisioned = true, nowWallMs = 5_000_000L, nowElapsedMs = 500L))
    }

    @Test
    fun `reboot with a small honest wall delta stays FRESH`() {
        val m = markers(wall = 1_000_000L, elapsed = 9_000_000L, hw = 1_000_000L)
        assertEquals(Ratchet.Tier.FRESH, Ratchet.tierFor(Ratchet.silenceMs(m, provisioned = true, nowWallMs = 1_000_000L + 1_000L, nowElapsedMs = 200L)))
    }
}

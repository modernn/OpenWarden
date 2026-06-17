package com.openwarden.child

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * [ContactClock] tier evaluation + reset behavior (ADR-024), with an in-memory [ContactStore]
 * and an injected [ContactClock.Clock] — the issue's clock-driven + sync-resume-reset tests.
 */
class ContactClockTest {

    private class FakeStore(
        var provisioned: Boolean = true,
        var childId: String = "child-abcd",
        var contactWall: Long? = null,
        var contactElapsed: Long? = null,
        var wallHw: Long? = null,
        var hbFloor: Long? = null,
    ) : ContactStore {
        override fun childDeviceId() = childId
        override fun isProvisioned() = provisioned
        override fun lastContactWallMs() = contactWall
        override fun lastContactElapsedMs() = contactElapsed
        override fun wallHighWaterMs() = wallHw
        override fun recordContact(wallMs: Long, elapsedMs: Long) {
            contactWall = wallMs; contactElapsed = elapsedMs; wallHw = maxOf(wallHw ?: wallMs, wallMs)
        }
        override fun advanceWallHighWater(wallMs: Long) {
            if (wallHw == null || wallMs > wallHw!!) wallHw = wallMs
        }
        override fun heartbeatFloor() = hbFloor
        override fun admitHeartbeatContact(issuedAt: Long, wallMs: Long, elapsedMs: Long) {
            hbFloor = issuedAt; contactWall = wallMs; contactElapsed = elapsedMs; wallHw = maxOf(wallHw ?: wallMs, wallMs)
        }
    }

    private class FakeClock(var wall: Long, var elapsed: Long) : ContactClock.Clock {
        override fun wallMs() = wall
        override fun elapsedMs() = elapsed
    }

    @Test
    fun `fresh immediately after contact`() {
        val store = FakeStore()
        val clock = FakeClock(1_000_000L, 1_000L)
        val cc = ContactClock(store, clock)
        cc.recordContact()
        assertEquals(Ratchet.Tier.FRESH, cc.currentTier())
    }

    @Test
    fun `tightens to STALE then STRICT as monotonic time advances`() {
        val store = FakeStore()
        val clock = FakeClock(1_000_000L, 1_000L)
        val cc = ContactClock(store, clock)
        cc.recordContact()

        clock.wall = 1_000_000L + Ratchet.RATCHET_STALE_MS
        clock.elapsed = 1_000L + Ratchet.RATCHET_STALE_MS
        assertEquals(Ratchet.Tier.STALE, cc.currentTier())

        clock.wall = 1_000_000L + Ratchet.RATCHET_STRICT_MS
        clock.elapsed = 1_000L + Ratchet.RATCHET_STRICT_MS
        assertEquals(Ratchet.Tier.STRICT, cc.currentTier())
    }

    @Test
    fun `provisioned child with no contact is STRICT`() {
        val cc = ContactClock(FakeStore(provisioned = true), FakeClock(5L, 5L))
        assertEquals(Ratchet.Tier.STRICT, cc.currentTier())
    }

    @Test
    fun `backward wall clock in-session trips STRICT`() {
        val store = FakeStore()
        val clock = FakeClock(10_000_000L, 1_000L)
        val cc = ContactClock(store, clock)
        cc.recordContact() // records wall=10M, high-water=10M
        clock.wall = 9_000_000L // rolled back below the high-water
        clock.elapsed = 2_000L  // still same session
        assertEquals(Ratchet.Tier.STRICT, cc.currentTier())
    }

    @Test
    fun `currentTier advances the wall high-water`() {
        val store = FakeStore()
        val clock = FakeClock(1_000_000L, 1_000L)
        val cc = ContactClock(store, clock)
        cc.recordContact()
        clock.wall = 2_000_000L
        cc.currentTier()
        assertEquals(2_000_000L, store.wallHighWaterMs())
    }

    @Test
    fun `valid heartbeat resets the ratchet and advances the replay floor`() {
        val kp = HeartbeatTestSigner.newKeypair()
        val store = FakeStore(provisioned = true, childId = "child-z", contactWall = 1L, contactElapsed = 1L, wallHw = 1L, hbFloor = 500L)
        val clock = FakeClock(9_000_000L, 9_000L)
        val cc = ContactClock(store, clock)
        val signed = HeartbeatTestSigner.sign(SignedHeartbeat(v = 1, child_device_id = "child-z", issued_at = 1_000L, sig = ""), kp)

        assertTrue(cc.admitHeartbeat(signed, kp.pubRaw))
        assertEquals(1_000L, store.heartbeatFloor())
        assertEquals(9_000_000L, store.lastContactWallMs())
        assertEquals(Ratchet.Tier.FRESH, cc.currentTier())
    }

    @Test
    fun `replayed heartbeat is rejected and leaves all state untouched`() {
        val kp = HeartbeatTestSigner.newKeypair()
        val store = FakeStore(provisioned = true, childId = "child-z", hbFloor = 2_000L)
        val cc = ContactClock(store, FakeClock(9_000_000L, 9_000L))
        val signed = HeartbeatTestSigner.sign(SignedHeartbeat(v = 1, child_device_id = "child-z", issued_at = 1_500L, sig = ""), kp)

        assertFalse(cc.admitHeartbeat(signed, kp.pubRaw))
        assertEquals(2_000L, store.heartbeatFloor()) // unchanged
        assertEquals(null, store.lastContactWallMs()) // no contact recorded
    }
}

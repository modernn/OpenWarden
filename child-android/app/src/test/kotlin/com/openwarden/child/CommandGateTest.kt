package com.openwarden.child

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * [CommandGate] composes [CommandAdmission] with a durable [CommandStore] (ADR-030). These tests
 * use an in-memory fake to assert the atomic floor-advance + lock-flag transition, and that any
 * rejection leaves all state untouched (fail-closed). Mirrors the heartbeat admit-contact contract.
 */
class CommandGateTest {

    private val myId = "child-abcd"
    private val now = 10_000_000L

    /** In-memory [CommandStore]; [failAdmit] simulates a fail-closed durable-write failure. */
    private class FakeCommandStore(
        private val id: String,
        var floor: Long? = null,
        var locked: Boolean = false,
        var admitCalls: Int = 0,
        val failAdmit: Boolean = false,
    ) : CommandStore {
        override fun childDeviceId() = id
        override fun commandFloor() = floor
        override fun isLocked() = locked
        override fun admitCommand(issuedAt: Long, locked: Boolean) {
            if (failAdmit) throw IllegalStateException("command admit commit() failed (fail-closed)")
            // Atomic in the real store; here the fake sets both together.
            this.floor = issuedAt
            this.locked = locked
            this.admitCalls++
        }
    }

    private fun gate(store: CommandStore) = CommandGate(store, clock = { now })

    private fun lock(issuedAt: Long = now) =
        SignedCommand(v = 1, type = SignedCommand.TYPE_LOCK, child_device_id = myId, issued_at = issuedAt)

    private fun unlock(issuedAt: Long = now) =
        SignedCommand(v = 1, type = SignedCommand.TYPE_UNLOCK, child_device_id = myId, issued_at = issuedAt)

    @Test
    fun `admitted lock advances the floor and sets is_locked`() {
        val kp = CommandTestSigner.newKeypair()
        val store = FakeCommandStore(myId)
        val outcome = gate(store).admit(CommandTestSigner.sign(lock(), kp), SignedCommand.TYPE_LOCK, kp.pubRaw)
        assertTrue(outcome is CommandAdmission.Outcome.Accept)
        assertEquals(now, store.floor)
        assertTrue(store.isLocked())
    }

    @Test
    fun `admitted unlock advances the floor and clears is_locked`() {
        val kp = CommandTestSigner.newKeypair()
        val store = FakeCommandStore(myId, floor = now - 1, locked = true)
        val outcome = gate(store).admit(CommandTestSigner.sign(unlock(), kp), SignedCommand.TYPE_UNLOCK, kp.pubRaw)
        assertTrue(outcome is CommandAdmission.Outcome.Accept)
        assertEquals(now, store.floor)
        assertFalse(store.isLocked())
    }

    @Test
    fun `lock then unlock toggles the durable flag with a monotonic floor`() {
        val kp = CommandTestSigner.newKeypair()
        val store = FakeCommandStore(myId)
        val g = CommandGate(store, clock = { now }) // both within the freshness window of `now`
        gate(store).admit(CommandTestSigner.sign(lock(issuedAt = now - 1), kp), SignedCommand.TYPE_LOCK, kp.pubRaw)
        assertTrue(store.isLocked())
        g.admit(CommandTestSigner.sign(unlock(issuedAt = now), kp), SignedCommand.TYPE_UNLOCK, kp.pubRaw)
        assertFalse(store.isLocked())
        assertEquals(now, store.floor)
    }

    @Test
    fun `a rejected command leaves floor and lock flag untouched`() {
        val signer = CommandTestSigner.newKeypair()
        val attacker = CommandTestSigner.newKeypair()
        val store = FakeCommandStore(myId, floor = null, locked = false)
        // Valid signature by the WRONG key → SIG_FAIL → reject, no admit.
        val outcome = gate(store).admit(CommandTestSigner.sign(lock(), signer), SignedCommand.TYPE_LOCK, attacker.pubRaw)
        assertTrue(outcome is CommandAdmission.Outcome.Reject)
        assertNull(store.floor)
        assertFalse(store.isLocked())
        assertEquals(0, store.admitCalls)
    }

    @Test
    fun `a replayed command never reaches the store`() {
        val kp = CommandTestSigner.newKeypair()
        val store = FakeCommandStore(myId, floor = now, locked = true)
        // unlock stamped at or below the floor → replay reject; lock state must stay true.
        val outcome = gate(store).admit(CommandTestSigner.sign(unlock(issuedAt = now), kp), SignedCommand.TYPE_UNLOCK, kp.pubRaw)
        assertTrue(outcome is CommandAdmission.Outcome.Reject)
        assertTrue(store.isLocked())
        assertEquals(0, store.admitCalls)
    }

    @Test
    fun `a fail-closed store write propagates (caller must reject)`() {
        val kp = CommandTestSigner.newKeypair()
        val store = FakeCommandStore(myId, failAdmit = true)
        // The command admits, but the durable write fails: the gate must NOT swallow it — the caller
        // surfaces a fail-closed rejection rather than acking a command that never persisted.
        assertFailsWith<IllegalStateException> {
            gate(store).admit(CommandTestSigner.sign(lock(), kp), SignedCommand.TYPE_LOCK, kp.pubRaw)
        }
    }
}

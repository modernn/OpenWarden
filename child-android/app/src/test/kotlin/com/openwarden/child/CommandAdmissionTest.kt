package com.openwarden.child

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Pure command admission decision (ADR-030). Verifies the fail-closed order
 * (version → JC1 → type → audience → signature → monotonic floor → freshness window) with real
 * Ed25519 sigs, mirroring [HeartbeatAdmissionTest].
 */
class CommandAdmissionTest {
    private val myId = "child-abcd"
    private val now = 10_000_000L // arbitrary fixed "now" well inside the JCS-safe range

    private fun cmd(
        type: String = SignedCommand.TYPE_LOCK,
        issuedAt: Long = now,
        childId: String = myId,
        v: Int = 1,
    ) = SignedCommand(v = v, type = type, child_device_id = childId, issued_at = issuedAt, sig = "")

    private fun accepts(o: CommandAdmission.Outcome) = o is CommandAdmission.Outcome.Accept

    private fun decide(
        signed: SignedCommand,
        kp: CommandTestSigner.Keypair,
        expectedType: String = SignedCommand.TYPE_LOCK,
        pinned: ByteArray? = kp.pubRaw,
        floor: Long? = null,
        nowMs: Long = now,
    ) = CommandAdmission.decide(signed, expectedType, myId, pinned, floor, nowMs)

    @Test
    fun `valid fresh lock accepts and reports its type`() {
        val kp = CommandTestSigner.newKeypair()
        val signed = CommandTestSigner.sign(cmd(type = SignedCommand.TYPE_LOCK), kp)
        val outcome = decide(signed, kp, expectedType = SignedCommand.TYPE_LOCK)
        assertTrue(accepts(outcome))
        assertEquals(SignedCommand.TYPE_LOCK, (outcome as CommandAdmission.Outcome.Accept).type)
    }

    @Test
    fun `valid fresh unlock accepts`() {
        val kp = CommandTestSigner.newKeypair()
        val signed = CommandTestSigner.sign(cmd(type = SignedCommand.TYPE_UNLOCK), kp)
        assertTrue(accepts(decide(signed, kp, expectedType = SignedCommand.TYPE_UNLOCK)))
    }

    @Test
    fun `valid command with no prior floor accepts`() {
        val kp = CommandTestSigner.newKeypair()
        val signed = CommandTestSigner.sign(cmd(), kp)
        assertTrue(accepts(decide(signed, kp, floor = null)))
    }

    @Test
    fun `unsupported version rejects`() {
        val kp = CommandTestSigner.newKeypair()
        val signed = CommandTestSigner.sign(cmd(v = 2), kp)
        assertFalse(accepts(decide(signed, kp)))
    }

    @Test
    fun `non-JCS-safe issued_at rejects before signature`() {
        // No need to sign: the JC1 bound is checked before the signature step.
        val bad = cmd(issuedAt = Canonical.MAX_JCS_SAFE_INTEGER + 1).copy(sig = "00")
        assertFalse(accepts(decide(bad, CommandTestSigner.newKeypair(), nowMs = Canonical.MAX_JCS_SAFE_INTEGER)))
    }

    @Test
    fun `lock command posted to the unlock endpoint rejects (type binding)`() {
        val kp = CommandTestSigner.newKeypair()
        val signed = CommandTestSigner.sign(cmd(type = SignedCommand.TYPE_LOCK), kp)
        assertFalse(accepts(decide(signed, kp, expectedType = SignedCommand.TYPE_UNLOCK)))
    }

    @Test
    fun `unknown command verb rejects`() {
        val kp = CommandTestSigner.newKeypair()
        val signed = CommandTestSigner.sign(cmd(type = "wipe"), kp)
        // Even if the endpoint somehow expected "wipe", it is not in VALID_TYPES; here the lock
        // endpoint expects "lock", so the signed "wipe" mismatches and rejects.
        assertFalse(accepts(decide(signed, kp, expectedType = SignedCommand.TYPE_LOCK)))
    }

    @Test
    fun `audience mismatch rejects`() {
        val kp = CommandTestSigner.newKeypair()
        val signed = CommandTestSigner.sign(cmd(childId = "child-other"), kp)
        assertFalse(accepts(decide(signed, kp)))
    }

    @Test
    fun `empty audience id rejects`() {
        val kp = CommandTestSigner.newKeypair()
        val signed = CommandTestSigner.sign(cmd(childId = ""), kp)
        assertFalse(accepts(decide(signed, kp)))
    }

    @Test
    fun `no pinned parent key rejects`() {
        val kp = CommandTestSigner.newKeypair()
        val signed = CommandTestSigner.sign(cmd(), kp)
        assertFalse(accepts(decide(signed, kp, pinned = null)))
    }

    @Test
    fun `signature by a different key fails closed`() {
        val signer = CommandTestSigner.newKeypair()
        val attacker = CommandTestSigner.newKeypair()
        val signed = CommandTestSigner.sign(cmd(), signer)
        assertFalse(accepts(decide(signed, signer, pinned = attacker.pubRaw)))
    }

    @Test
    fun `tampered type after signing fails closed`() {
        val kp = CommandTestSigner.newKeypair()
        // Sign an unlock, then flip the wire bytes to lock — the signature no longer covers them.
        val signed =
            CommandTestSigner
                .sign(cmd(type = SignedCommand.TYPE_UNLOCK), kp)
                .copy(type = SignedCommand.TYPE_LOCK)
        assertFalse(accepts(decide(signed, kp, expectedType = SignedCommand.TYPE_LOCK)))
    }

    @Test
    fun `tampered issued_at after signing fails closed`() {
        val kp = CommandTestSigner.newKeypair()
        val signed = CommandTestSigner.sign(cmd(issuedAt = now), kp).copy(issued_at = now + 1)
        assertFalse(accepts(decide(signed, kp)))
    }

    @Test
    fun `a fully-default command is rejected (fail-closed defaults)`() {
        // Empty type + empty audience id + empty sig + issued_at 0 — never admits.
        assertFalse(
            accepts(
                CommandAdmission.decide(
                    SignedCommand(v = 1),
                    SignedCommand.TYPE_LOCK,
                    myId,
                    CommandTestSigner.newKeypair().pubRaw,
                    commandFloor = null,
                    nowMs = now,
                ),
            ),
        )
    }

    @Test
    fun `replayed command at or below the floor rejects`() {
        val kp = CommandTestSigner.newKeypair()
        val signed = CommandTestSigner.sign(cmd(issuedAt = now), kp)
        assertFalse(accepts(decide(signed, kp, floor = now))) // equal to floor
        assertFalse(accepts(decide(signed, kp, floor = now + 1))) // below floor
    }

    @Test
    fun `a lock advances the shared floor so an earlier unlock dies`() {
        // Floor semantics check at the decision layer: an unlock stamped before the floor is replay.
        val kp = CommandTestSigner.newKeypair()
        val unlock = CommandTestSigner.sign(cmd(type = SignedCommand.TYPE_UNLOCK, issuedAt = now - 1), kp)
        assertFalse(accepts(decide(unlock, kp, expectedType = SignedCommand.TYPE_UNLOCK, floor = now)))
        val laterUnlock = CommandTestSigner.sign(cmd(type = SignedCommand.TYPE_UNLOCK, issuedAt = now + 1), kp)
        assertTrue(accepts(decide(laterUnlock, kp, expectedType = SignedCommand.TYPE_UNLOCK, floor = now)))
    }

    @Test
    fun `stale command beyond the freshness window rejects`() {
        val kp = CommandTestSigner.newKeypair()
        val signed = CommandTestSigner.sign(cmd(issuedAt = now), kp)
        // now advanced past the window: a captured command older than FRESHNESS_MS is dead.
        assertFalse(accepts(decide(signed, kp, nowMs = now + CommandAdmission.FRESHNESS_MS + 1)))
        // exactly at the edge still admits.
        assertTrue(accepts(decide(signed, kp, nowMs = now + CommandAdmission.FRESHNESS_MS)))
    }

    @Test
    fun `an out-of-range nowMs rejects fail-closed (no overflow in the freshness window)`() {
        val kp = CommandTestSigner.newKeypair()
        val signed = CommandTestSigner.sign(cmd(issuedAt = now), kp)
        // A negative or absurd clock must reject, never wrap the subtraction back into the window.
        assertFalse(accepts(decide(signed, kp, nowMs = -1L)))
        assertFalse(accepts(decide(signed, kp, nowMs = Canonical.MAX_JCS_SAFE_INTEGER + 1)))
    }

    @Test
    fun `future command beyond the freshness window rejects`() {
        val kp = CommandTestSigner.newKeypair()
        val signed = CommandTestSigner.sign(cmd(issuedAt = now), kp)
        // child clock lags far behind the signed time: reject (fail-closed), parent retries.
        assertFalse(accepts(decide(signed, kp, nowMs = now - CommandAdmission.FRESHNESS_MS - 1)))
        assertTrue(accepts(decide(signed, kp, nowMs = now - CommandAdmission.FRESHNESS_MS)))
    }
}

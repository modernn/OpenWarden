package com.openwarden.child

import org.junit.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Pure heartbeat admission decision (ADR-024 D4). Verifies the fail-closed order
 * (version → JC1 → audience → signature → monotonic replay floor) with real Ed25519 sigs.
 */
class HeartbeatAdmissionTest {
    private val myId = "child-abcd"

    private fun hb(
        issuedAt: Long = 1_000L,
        childId: String = myId,
        v: Int = 1,
    ) = SignedHeartbeat(v = v, child_device_id = childId, issued_at = issuedAt, sig = "")

    private fun accepts(o: HeartbeatAdmission.Outcome) = o is HeartbeatAdmission.Outcome.Accept

    @Test
    fun `valid fresh heartbeat accepts`() {
        val kp = HeartbeatTestSigner.newKeypair()
        val signed = HeartbeatTestSigner.sign(hb(issuedAt = 2_000L), kp)
        assertTrue(accepts(HeartbeatAdmission.decide(signed, myId, kp.pubRaw, heartbeatFloor = 1_000L)))
    }

    @Test
    fun `valid heartbeat with no prior floor accepts`() {
        val kp = HeartbeatTestSigner.newKeypair()
        val signed = HeartbeatTestSigner.sign(hb(issuedAt = 1L), kp)
        assertTrue(accepts(HeartbeatAdmission.decide(signed, myId, kp.pubRaw, heartbeatFloor = null)))
    }

    @Test
    fun `unsupported version rejects`() {
        val kp = HeartbeatTestSigner.newKeypair()
        val signed = HeartbeatTestSigner.sign(hb(v = 2), kp)
        assertFalse(accepts(HeartbeatAdmission.decide(signed, myId, kp.pubRaw, heartbeatFloor = null)))
    }

    @Test
    fun `non-JCS-safe issued_at rejects before signature`() {
        // No need to sign: JC1 bound is checked before the signature step.
        val bad = hb(issuedAt = Canonical.MAX_JCS_SAFE_INTEGER + 1).copy(sig = "00")
        assertFalse(accepts(HeartbeatAdmission.decide(bad, myId, HeartbeatTestSigner.newKeypair().pubRaw, heartbeatFloor = null)))
    }

    @Test
    fun `audience mismatch rejects`() {
        val kp = HeartbeatTestSigner.newKeypair()
        val signed = HeartbeatTestSigner.sign(hb(childId = "child-other"), kp)
        assertFalse(accepts(HeartbeatAdmission.decide(signed, myId, kp.pubRaw, heartbeatFloor = null)))
    }

    @Test
    fun `empty audience id rejects`() {
        val kp = HeartbeatTestSigner.newKeypair()
        val signed = HeartbeatTestSigner.sign(hb(childId = ""), kp)
        assertFalse(accepts(HeartbeatAdmission.decide(signed, myId, kp.pubRaw, heartbeatFloor = null)))
    }

    @Test
    fun `no pinned parent key rejects`() {
        val kp = HeartbeatTestSigner.newKeypair()
        val signed = HeartbeatTestSigner.sign(hb(), kp)
        assertFalse(accepts(HeartbeatAdmission.decide(signed, myId, pinnedParentPubkey = null, heartbeatFloor = null)))
    }

    @Test
    fun `signature by a different key fails closed`() {
        val signer = HeartbeatTestSigner.newKeypair()
        val attacker = HeartbeatTestSigner.newKeypair()
        val signed = HeartbeatTestSigner.sign(hb(), signer)
        assertFalse(accepts(HeartbeatAdmission.decide(signed, myId, attacker.pubRaw, heartbeatFloor = null)))
    }

    @Test
    fun `tampered issued_at after signing fails closed`() {
        val kp = HeartbeatTestSigner.newKeypair()
        val signed = HeartbeatTestSigner.sign(hb(issuedAt = 2_000L), kp).copy(issued_at = 3_000L)
        assertFalse(accepts(HeartbeatAdmission.decide(signed, myId, kp.pubRaw, heartbeatFloor = null)))
    }

    @Test
    fun `a fully-default heartbeat is rejected (fail-closed defaults)`() {
        // Empty audience id + empty sig + issued_at 0 — the all-defaults object must never admit.
        val outcome =
            HeartbeatAdmission.decide(
                SignedHeartbeat(v = 1),
                myId,
                HeartbeatTestSigner.newKeypair().pubRaw,
                heartbeatFloor = null,
            )
        assertFalse(accepts(outcome))
    }

    @Test
    fun `replayed heartbeat at or below the floor rejects`() {
        val kp = HeartbeatTestSigner.newKeypair()
        val signed = HeartbeatTestSigner.sign(hb(issuedAt = 1_000L), kp)
        // equal to floor
        assertFalse(accepts(HeartbeatAdmission.decide(signed, myId, kp.pubRaw, heartbeatFloor = 1_000L)))
        // below floor
        assertFalse(accepts(HeartbeatAdmission.decide(signed, myId, kp.pubRaw, heartbeatFloor = 1_500L)))
    }
}

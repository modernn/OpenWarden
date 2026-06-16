package com.openwarden.proto

import com.openwarden.proto.ReplayFloor.Decision
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Vectors for the pure replay-floor decision function (ADR-017, issue #5).
 * All JVM-testable, no libsodium. Each test maps to an ADR-017 clause.
 */
class ReplayFloorTest {
    // ---- accept path (monotonic advance) ------------------------------------

    @Test
    fun acceptsStrictlyHigherSeq() {
        assertEquals(Decision.Accept(11L), ReplayFloor.admit(currentFloor = 10L, incomingPolicySeq = 11L))
    }

    @Test
    fun acceptedNewFloorEqualsIncomingSeq() {
        val d = ReplayFloor.admit(currentFloor = 41L, incomingPolicySeq = 42L)
        assertEquals(Decision.Accept(42L), d)
    }

    @Test
    fun acceptsMaxAllowedJumpButRejectsOneBeyond() {
        // floor + MAX_SEQ_JUMP is the last admissible value; +1 is floor-poison.
        val edge = 10L + ReplayFloor.MAX_SEQ_JUMP
        assertEquals(Decision.Accept(edge), ReplayFloor.admit(10L, edge))
        assertTrue(ReplayFloor.admit(10L, edge + 1) is Decision.RejectStrict)
    }

    // ---- ADR-017 vector: rollback-snapshot (K1) -----------------------------

    @Test
    fun rollbackSnapshotRejected() {
        // floor=10, incoming=5 => RejectStrict (a restored older bundle).
        assertTrue(ReplayFloor.admit(currentFloor = 10L, incomingPolicySeq = 5L) is Decision.RejectStrict)
    }

    @Test
    fun equalSeqRejectedAsReplay() {
        // policy_seq == floor is not strictly greater => REGRESSION (replay).
        assertTrue(ReplayFloor.admit(currentFloor = 10L, incomingPolicySeq = 10L) is Decision.RejectStrict)
    }

    @Test
    fun restoringOlderFloorDoesNotReAdmitOldBundle() {
        // Even if an attacker rolls the *floor* back to an older value, the
        // decision is purely (floor, seq): seq 5 stays rejected against floor 10,
        // and the function never silently re-admits the old permissive bundle.
        // The defense lives in the floor read being max(at_rest, chain) upstream;
        // here we assert the decision itself never re-admits a stale seq.
        val floorAfterAdvance = 10L
        // seq 5 was already superseded; it must stay rejected.
        assertTrue(ReplayFloor.admit(floorAfterAdvance, 5L) is Decision.RejectStrict)
        // and a fresh higher seq still advances normally.
        assertEquals(Decision.Accept(11L), ReplayFloor.admit(floorAfterAdvance, 11L))
    }

    // ---- ADR-017 vector: rotate-then-replay (K2) ----------------------------

    @Test
    fun decisionIsIndependentOfKeyIdentity() {
        // The function has NO pubkey parameter, so a key rotation cannot reset or
        // lower the floor. Structurally: the same (floor, seq) yields the same
        // decision regardless of which parent key would have signed the bundle.
        // Model a rotation as: floor=50 established under "old key"; the RotateKey
        // bundle (and any subsequent "new key" bundle) is admitted on the SAME
        // device-global floor, so old-key seqs <= floor are dead.
        val floorBeforeRotation = 50L
        // RotateKey bundle at a higher seq advances the shared floor.
        assertEquals(Decision.Accept(51L), ReplayFloor.admit(floorBeforeRotation, 51L))
        // After rotation the floor is still 51 (device-global). An old-key bundle
        // (e.g. seq 40 the old key once signed) cannot replay under the new key.
        assertTrue(ReplayFloor.admit(51L, 40L) is Decision.RejectStrict)
        // The floor never reads as zero for a "new" key — there is no per-key
        // counter to reset. Same monotonic rule holds across the rotation.
        assertTrue(ReplayFloor.admit(51L, 51L) is Decision.RejectStrict)
    }

    // ---- ADR-017 vector: genesis-absent (K2 fail-closed) --------------------

    @Test
    fun genesisAbsentFloorIsRejectedNeverSilentlyFresh() {
        // admit(null, <permissive seq>) => RejectStrict. An absent/unreadable
        // floor MUST NOT be treated as fresh/accept-anything (ADR-017 K2).
        assertTrue(ReplayFloor.admit(currentFloor = null, incomingPolicySeq = 1L) is Decision.RejectStrict)
        assertTrue(ReplayFloor.admit(currentFloor = null, incomingPolicySeq = 999_999L) is Decision.RejectStrict)
        // Even seq 0 (reserved) on a null floor is rejected, not accepted.
        assertTrue(ReplayFloor.admit(currentFloor = null, incomingPolicySeq = 0L) is Decision.RejectStrict)
    }

    // ---- JC1: JCS integer bound ---------------------------------------------

    @Test
    fun rejectsSeqAtJcsOverflow() {
        // incoming = 2^53 (one past MAX_JCS_SAFE_INTEGER) => reject, checked
        // BEFORE the monotonic comparison so a high seq can never poison the floor.
        val overflow = Canonical.MAX_JCS_SAFE_INTEGER + 1
        assertTrue(ReplayFloor.admit(currentFloor = 10L, incomingPolicySeq = overflow) is Decision.RejectStrict)
    }

    @Test
    fun jcsBoundIsCheckedBeforeNullFloor() {
        // An overflow seq is rejected even with a null floor (JC1 ordering),
        // and the reason names the JCS range, not the genesis path.
        val overflow = Canonical.MAX_JCS_SAFE_INTEGER + 1
        val d = ReplayFloor.admit(currentFloor = null, incomingPolicySeq = overflow)
        assertTrue(d is Decision.RejectStrict)
        assertTrue((d as Decision.RejectStrict).reason.contains("JCS-safe"))
    }

    @Test
    fun rejectsNegativeSeq() {
        assertTrue(ReplayFloor.admit(currentFloor = 10L, incomingPolicySeq = -1L) is Decision.RejectStrict)
    }

    @Test
    fun acceptsSeqExactlyAtJcsBoundWhenWithinJump() {
        // The JCS boundary value itself round-trips and is admissible if it is a
        // legal monotonic step (floor just below the bound, within MAX_SEQ_JUMP).
        val bound = Canonical.MAX_JCS_SAFE_INTEGER
        assertEquals(Decision.Accept(bound), ReplayFloor.admit(bound - 1, bound))
    }

    // ---- floor-poison DoS bound (MAX_SEQ_JUMP) ------------------------------

    @Test
    fun rejectsAbsurdForwardJump() {
        // A single bundle near 2^53 against a low floor would otherwise freeze
        // updates forever; MAX_SEQ_JUMP rejects it as floor-poison.
        val d = ReplayFloor.admit(currentFloor = 10L, incomingPolicySeq = Canonical.MAX_JCS_SAFE_INTEGER)
        assertTrue(d is Decision.RejectStrict)
        assertTrue((d as Decision.RejectStrict).reason.contains("MAX_SEQ_JUMP"))
    }
}

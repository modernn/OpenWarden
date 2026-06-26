package com.openwarden.child

import com.openwarden.child.ReplayFloor.Decision
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Vectors for the pure replay-floor decision function (ADR-017, issue #5).
 * Ported byte-rule-identical from the proto module's ReplayFloorTest. Pure JVM,
 * no Robolectric, no libsodium. Each test maps to an ADR-017 clause.
 */
class ReplayFloorTest {
    // ---- accept path (monotonic advance) ------------------------------------

    @Test
    fun acceptsStrictlyHigherSeq() {
        assertEquals(Decision.Accept(11L), ReplayFloor.admit(currentFloor = 10L, incomingPolicySeq = 11L))
    }

    @Test
    fun acceptedNewFloorEqualsIncomingSeq() {
        assertEquals(Decision.Accept(42L), ReplayFloor.admit(currentFloor = 41L, incomingPolicySeq = 42L))
    }

    @Test
    fun acceptsMaxAllowedJumpButRejectsOneBeyond() {
        val edge = 10L + ReplayFloor.MAX_SEQ_JUMP
        assertEquals(Decision.Accept(edge), ReplayFloor.admit(10L, edge))
        assertTrue(ReplayFloor.admit(10L, edge + 1) is Decision.RejectStrict)
    }

    // ---- K1 rollback/replay -------------------------------------------------

    @Test
    fun rollbackSnapshotRejected() {
        assertTrue(ReplayFloor.admit(currentFloor = 10L, incomingPolicySeq = 5L) is Decision.RejectStrict)
    }

    @Test
    fun equalSeqRejectedAsReplay() {
        assertTrue(ReplayFloor.admit(currentFloor = 10L, incomingPolicySeq = 10L) is Decision.RejectStrict)
    }

    // ---- K2 rotation independence -------------------------------------------

    @Test
    fun decisionIsIndependentOfKeyIdentity() {
        // No pubkey parameter, so rotation cannot reset/lower the floor.
        assertEquals(Decision.Accept(51L), ReplayFloor.admit(50L, 51L))
        assertTrue(ReplayFloor.admit(51L, 40L) is Decision.RejectStrict)
        assertTrue(ReplayFloor.admit(51L, 51L) is Decision.RejectStrict)
    }

    // ---- K2 fail-closed null floor (pure-function level) --------------------

    @Test
    fun nullFloorIsRejectedNeverSilentlyFresh() {
        assertTrue(ReplayFloor.admit(currentFloor = null, incomingPolicySeq = 1L) is Decision.RejectStrict)
        assertTrue(ReplayFloor.admit(currentFloor = null, incomingPolicySeq = 999_999L) is Decision.RejectStrict)
        assertTrue(ReplayFloor.admit(currentFloor = null, incomingPolicySeq = 0L) is Decision.RejectStrict)
    }

    // ---- JC1 JCS integer bound ----------------------------------------------

    @Test
    fun rejectsSeqAtJcsOverflow() {
        val overflow = ReplayFloor.MAX_JCS_SAFE_INTEGER + 1
        assertTrue(ReplayFloor.admit(currentFloor = 10L, incomingPolicySeq = overflow) is Decision.RejectStrict)
    }

    @Test
    fun jcsBoundIsCheckedBeforeNullFloor() {
        val overflow = ReplayFloor.MAX_JCS_SAFE_INTEGER + 1
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
        val bound = ReplayFloor.MAX_JCS_SAFE_INTEGER
        assertEquals(Decision.Accept(bound), ReplayFloor.admit(bound - 1, bound))
    }

    // ---- floor-poison DoS bound (MAX_SEQ_JUMP) ------------------------------

    @Test
    fun rejectsAbsurdForwardJump() {
        val d = ReplayFloor.admit(currentFloor = 10L, incomingPolicySeq = ReplayFloor.MAX_JCS_SAFE_INTEGER)
        assertTrue(d is Decision.RejectStrict)
        assertTrue((d as Decision.RejectStrict).reason.contains("MAX_SEQ_JUMP"))
    }
}

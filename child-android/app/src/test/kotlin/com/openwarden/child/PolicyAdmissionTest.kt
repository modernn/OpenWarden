package com.openwarden.child

import net.i2p.crypto.eddsa.EdDSAEngine
import net.i2p.crypto.eddsa.EdDSAPrivateKey
import net.i2p.crypto.eddsa.spec.EdDSANamedCurveTable
import net.i2p.crypto.eddsa.spec.EdDSAPrivateKeySpec
import net.i2p.crypto.eddsa.spec.EdDSAPublicKeySpec
import org.junit.Test
import java.security.MessageDigest
import java.security.SecureRandom
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * ADR-017 child admission tests (issue #5/#10). Pure JVM — no Robolectric, no
 * Android: [PolicyAdmission] depends on the [PolicyAdmission.FloorState] interface
 * (faked here in memory) and Ed25519 is the i2p lib, which runs on the JVM.
 *
 * Required vectors (per the work order):
 *  - genesis-TOFU-first-accept       (never-provisioned + seq>=1 => accept + seed)
 *  - genesis-anomaly-strict          (provisioned + null/lower floor => strict)
 *  - monotonic-reject                (seq<=floor => strict)
 *  - rollback-snapshot-strict        (restored older floor never re-admits stale seq)
 *  - audience-mismatch-reject        (child_device_id != my id => MALFORMED, before sig)
 *  - seq-overflow-reject             (2^53 => MALFORMED)
 *  - seq-jump-reject                 (>floor+1024 => MALFORMED)
 *  - crash-idempotency               (re-apply same seq after crash => no regression)
 */
class PolicyAdmissionTest {

    // ---- Ed25519 test signer (produces sigs the child verifies) -------------

    private val curve = EdDSANamedCurveTable.getByName("Ed25519")

    private class Keypair(val privateKey: EdDSAPrivateKey, val publicKeyRaw: ByteArray)

    private fun newKeypair(): Keypair {
        val seed = ByteArray(32).also { SecureRandom().nextBytes(it) }
        val privSpec = EdDSAPrivateKeySpec(seed, curve)
        val priv = EdDSAPrivateKey(privSpec)
        val pubSpec = EdDSAPublicKeySpec(privSpec.a, curve)
        return Keypair(priv, pubSpec.a.toByteArray())
    }

    /** Sign the JCS canonical body (bundle minus sig) and return the bundle with a real sig. */
    private fun sign(bundle: SignedBundle, kp: Keypair): SignedBundle {
        val body = BundleVerifier.canonicalBody(bundle.copy(sig = ""))
        val engine = EdDSAEngine(MessageDigest.getInstance("SHA-512"))
        engine.initSign(kp.privateKey)
        engine.update(body)
        val sig = engine.sign()
        return bundle.copy(sig = sig.joinToString("") { "%02x".format(it) })
    }

    private fun bundle(
        policySeq: Long,
        childId: String,
        v: Int = 1,
        allowlist: List<String> = listOf("com.example.app"),
    ) = SignedBundle(
        v = v,
        child_device_id = childId,
        policy_seq = policySeq,
        issued_at = "2026-01-01T00:00:00Z",
        expires_at = "2099-12-31T23:59:59Z",
        nonce = "9f1b3c4d5e6f70819a2b3c4d5e6f7081",
        policy = PolicyDoc(allowlist = allowlist),
        sig = "",
    )

    // ---- in-memory FloorState fake ------------------------------------------

    private class FakeFloorState(
        private val myId: String = "child-aaaa",
        var provisioned: Boolean = false,
        var atRest: Long? = null,
        var chain: Long? = null,
    ) : PolicyAdmission.FloorState {
        val acks = mutableListOf<Long>()
        override fun childDeviceId() = myId
        override fun isProvisioned() = provisioned
        override fun markProvisioned() { provisioned = true }
        override fun atRestFloor() = atRest
        override fun chainFloor() = chain
        override fun effectiveFloor(): Long? = when {
            atRest == null && chain == null -> null
            atRest == null -> chain
            chain == null -> atRest
            else -> maxOf(atRest!!, chain!!)
        }
        override fun advanceFloor(policySeq: Long) {
            val cur = atRest
            if (cur != null && policySeq <= cur) return // never lower; idempotent
            atRest = policySeq
        }
    }

    /** Records the two-phase commit calls in order. */
    private class RecordingApplier(val failApply: Boolean = false) : PolicyAdmission.Applier {
        val calls = mutableListOf<String>()
        override fun stage(bundle: SignedBundle) { calls += "stage:${bundle.policy_seq}" }
        override fun applyAndFsync(bundle: SignedBundle) {
            calls += "apply:${bundle.policy_seq}"
            if (failApply) throw RuntimeException("simulated DPM failure")
        }
        override fun ack(policySeq: Long) { calls += "ack:$policySeq" }
    }

    // =========================================================================
    // genesis-TOFU-first-accept
    // =========================================================================

    @Test
    fun genesisTofuFirstAcceptSeedsFloor() {
        val kp = newKeypair()
        val state = FakeFloorState(provisioned = false, atRest = null) // never provisioned
        val applier = RecordingApplier()
        var pinned: ByteArray? = null

        val b = sign(bundle(policySeq = 1L, childId = state.childDeviceId()), kp)
        val result = PolicyAdmission.admit(
            bundle = b,
            store = state,
            applier = applier,
            pinParentKey = { pinned = it },
            pinnedParentPubkey = null,      // no key pinned yet — genesis candidate
            genesisPubkey = kp.publicKeyRaw, // caller verified sig against this; pin it
        )

        assertTrue(result is PolicyAdmission.Result.Applied, "genesis seq>=1 must be Applied")
        assertEquals(1L, (result as PolicyAdmission.Result.Applied).policySeq)
        assertTrue(result.genesis, "must be flagged genesis")
        assertEquals(1L, state.atRestFloor(), "floor must be seeded to the genesis seq")
        assertTrue(state.isProvisioned(), "provisioning marker must be written")
        assertTrue(pinned!!.contentEquals(kp.publicKeyRaw), "parent key must be pinned at genesis")
        // Ordering: pin/mark happen, then stage -> apply -> ack; floor advanced between apply and ack.
        assertEquals(listOf("stage:1", "apply:1", "ack:1"), applier.calls)
    }

    @Test
    fun genesisSeqZeroRejectedNeverLivePolicy() {
        val kp = newKeypair()
        val state = FakeFloorState(provisioned = false, atRest = null)
        val b = sign(bundle(policySeq = 0L, childId = state.childDeviceId()), kp)
        val result = PolicyAdmission.admit(
            bundle = b, store = state, applier = RecordingApplier(),
            pinParentKey = {}, pinnedParentPubkey = null, genesisPubkey = kp.publicKeyRaw,
        )
        assertTrue(result is PolicyAdmission.Result.Rejected, "policy_seq=0 is reserved, never genesis-live")
        assertFalse(state.isProvisioned(), "rejected genesis must not provision")
    }

    // =========================================================================
    // genesis-anomaly-strict (provisioned but floor missing/lower => strict)
    // =========================================================================

    @Test
    fun provisionedMissingFloorIsAnomalyStrict() {
        val kp = newKeypair()
        // Provisioned (marker set, key pinned) but floor is gone.
        val state = FakeFloorState(provisioned = true, atRest = null)
        val b = sign(bundle(policySeq = 5L, childId = state.childDeviceId()), kp)
        val result = PolicyAdmission.admit(
            bundle = b, store = state, applier = RecordingApplier(),
            pinParentKey = {}, pinnedParentPubkey = kp.publicKeyRaw,
        )
        assertTrue(result is PolicyAdmission.Result.Rejected, "provisioned + missing floor => strict")
        assertFalse((result as PolicyAdmission.Result.Rejected).malformed, "anomaly is strict, not malformed")
        assertTrue(result.reason.contains("anomaly"))
    }

    @Test
    fun provisionedButNoPinnedKeyIsAnomalyStrict() {
        val kp = newKeypair()
        val state = FakeFloorState(provisioned = true, atRest = 10L)
        val b = sign(bundle(policySeq = 11L, childId = state.childDeviceId()), kp)
        val result = PolicyAdmission.admit(
            bundle = b, store = state, applier = RecordingApplier(),
            pinParentKey = {}, pinnedParentPubkey = null, // provisioned but key gone
        )
        assertTrue(result is PolicyAdmission.Result.Rejected)
        assertFalse((result as PolicyAdmission.Result.Rejected).malformed)
    }

    // =========================================================================
    // monotonic-reject (seq <= floor => strict REGRESSION)
    // =========================================================================

    @Test
    fun monotonicRejectEqualSeq() {
        val kp = newKeypair()
        val state = FakeFloorState(provisioned = true, atRest = 10L)
        val b = sign(bundle(policySeq = 10L, childId = state.childDeviceId()), kp)
        val result = PolicyAdmission.admit(
            bundle = b, store = state, applier = RecordingApplier(),
            pinParentKey = {}, pinnedParentPubkey = kp.publicKeyRaw,
        )
        assertTrue(result is PolicyAdmission.Result.Rejected, "seq == floor must reject")
        assertFalse((result as PolicyAdmission.Result.Rejected).malformed, "REGRESSION is strict")
        assertEquals(10L, state.atRestFloor(), "floor must not change on reject")
    }

    @Test
    fun monotonicRejectLowerSeq() {
        val kp = newKeypair()
        val state = FakeFloorState(provisioned = true, atRest = 10L)
        val b = sign(bundle(policySeq = 9L, childId = state.childDeviceId()), kp)
        val result = PolicyAdmission.admit(
            bundle = b, store = state, applier = RecordingApplier(),
            pinParentKey = {}, pinnedParentPubkey = kp.publicKeyRaw,
        )
        assertTrue(result is PolicyAdmission.Result.Rejected)
        assertFalse((result as PolicyAdmission.Result.Rejected).malformed)
    }

    // =========================================================================
    // rollback-snapshot-strict
    // =========================================================================

    @Test
    fun rollbackSnapshotStrict() {
        val kp = newKeypair()
        // Floor was 100; an attacker restores an OLD snapshot dropping at-rest to 50.
        // A validly-signed OLD bundle at seq 60 (<= the real high-water but > restored
        // floor) must still be evaluated against whatever floor is read. Here we model
        // the chain witness retaining 100 so effectiveFloor = max(50,100)=100 => reject.
        val state = FakeFloorState(provisioned = true, atRest = 50L, chain = 100L)
        val b = sign(bundle(policySeq = 60L, childId = state.childDeviceId()), kp)
        val result = PolicyAdmission.admit(
            bundle = b, store = state, applier = RecordingApplier(),
            pinParentKey = {}, pinnedParentPubkey = kp.publicKeyRaw,
        )
        // effectiveFloor=100; seq 60 <= 100 => REGRESSION strict. AND the at-rest<chain
        // anomaly path also fails closed; either way it is strict, never applied.
        assertTrue(result is PolicyAdmission.Result.Rejected, "restored older snapshot must not re-admit")
        assertFalse((result as PolicyAdmission.Result.Rejected).malformed)
    }

    @Test
    fun atRestBelowChainIsAnomalyStrictEvenForHighSeq() {
        val kp = newKeypair()
        // at-rest rolled back below chain witness; even a fresh high seq must fail closed
        // (ADR-017 part 1/3: at-rest < chain is a rollback anomaly).
        val state = FakeFloorState(provisioned = true, atRest = 50L, chain = 100L)
        val b = sign(bundle(policySeq = 101L, childId = state.childDeviceId()), kp)
        val result = PolicyAdmission.admit(
            bundle = b, store = state, applier = RecordingApplier(),
            pinParentKey = {}, pinnedParentPubkey = kp.publicKeyRaw,
        )
        assertTrue(result is PolicyAdmission.Result.Rejected)
        assertFalse((result as PolicyAdmission.Result.Rejected).malformed)
        assertTrue(result.reason.contains("anomaly") || result.reason.contains("rollback"))
    }

    // =========================================================================
    // audience-mismatch-reject (before signature)
    // =========================================================================

    @Test
    fun audienceMismatchRejectedMalformed() {
        val kp = newKeypair()
        val state = FakeFloorState(myId = "child-aaaa", provisioned = true, atRest = 10L)
        // Bundle addressed to a DIFFERENT child, validly signed.
        val b = sign(bundle(policySeq = 11L, childId = "child-bbbb"), kp)
        val result = PolicyAdmission.admit(
            bundle = b, store = state, applier = RecordingApplier(),
            pinParentKey = {}, pinnedParentPubkey = kp.publicKeyRaw,
        )
        assertTrue(result is PolicyAdmission.Result.Rejected)
        assertTrue((result as PolicyAdmission.Result.Rejected).malformed, "audience mismatch is MALFORMED")
        assertTrue(result.reason.contains("audience"))
    }

    @Test
    fun audienceCheckedBeforeSignature() {
        // A bundle with a WRONG audience AND a garbage signature must reject MALFORMED
        // (audience), proving the audience gate runs before signature verification.
        val state = FakeFloorState(myId = "child-aaaa", provisioned = true, atRest = 10L)
        val kp = newKeypair()
        val b = bundle(policySeq = 11L, childId = "child-bbbb").copy(sig = "deadbeef")
        val result = PolicyAdmission.admit(
            bundle = b, store = state, applier = RecordingApplier(),
            pinParentKey = {}, pinnedParentPubkey = kp.publicKeyRaw,
        )
        assertTrue(result is PolicyAdmission.Result.Rejected)
        assertTrue((result as PolicyAdmission.Result.Rejected).malformed)
        assertTrue(result.reason.contains("audience"), "must fail audience before sig")
    }

    // =========================================================================
    // seq-overflow-reject (> 2^53-1 => MALFORMED, before signature)
    // =========================================================================

    @Test
    fun seqOverflowRejectedMalformed() {
        val kp = newKeypair()
        val state = FakeFloorState(provisioned = true, atRest = 10L)
        val overflow = Canonical.MAX_JCS_SAFE_INTEGER + 1
        val b = sign(bundle(policySeq = overflow, childId = state.childDeviceId()), kp)
        val result = PolicyAdmission.admit(
            bundle = b, store = state, applier = RecordingApplier(),
            pinParentKey = {}, pinnedParentPubkey = kp.publicKeyRaw,
        )
        assertTrue(result is PolicyAdmission.Result.Rejected)
        assertTrue((result as PolicyAdmission.Result.Rejected).malformed, "2^53 seq is MALFORMED (JC1)")
        assertTrue(result.reason.contains("JCS-safe"))
    }

    // =========================================================================
    // seq-jump-reject (> floor + 1024 => MALFORMED)
    // =========================================================================

    @Test
    fun seqJumpRejectedMalformed() {
        val kp = newKeypair()
        val state = FakeFloorState(provisioned = true, atRest = 10L)
        val tooFar = 10L + ReplayFloor.MAX_SEQ_JUMP + 1
        val b = sign(bundle(policySeq = tooFar, childId = state.childDeviceId()), kp)
        val result = PolicyAdmission.admit(
            bundle = b, store = state, applier = RecordingApplier(),
            pinParentKey = {}, pinnedParentPubkey = kp.publicKeyRaw,
        )
        assertTrue(result is PolicyAdmission.Result.Rejected)
        assertTrue((result as PolicyAdmission.Result.Rejected).malformed, "floor-poison jump is MALFORMED")
        assertTrue(result.reason.contains("MAX_SEQ_JUMP"))
        assertEquals(10L, state.atRestFloor(), "rejected jump must not poison the floor")
    }

    @Test
    fun seqExactlyAtMaxJumpAccepted() {
        val kp = newKeypair()
        val state = FakeFloorState(provisioned = true, atRest = 10L)
        val edge = 10L + ReplayFloor.MAX_SEQ_JUMP
        val b = sign(bundle(policySeq = edge, childId = state.childDeviceId()), kp)
        val result = PolicyAdmission.admit(
            bundle = b, store = state, applier = RecordingApplier(),
            pinParentKey = {}, pinnedParentPubkey = kp.publicKeyRaw,
        )
        assertTrue(result is PolicyAdmission.Result.Applied, "floor+MAX_SEQ_JUMP is the last admissible step")
        assertEquals(edge, state.atRestFloor())
    }

    // =========================================================================
    // signature failure => strict (fail-closed), and a good sig advances
    // =========================================================================

    @Test
    fun badSignatureRejectedStrict() {
        val kp = newKeypair()
        val state = FakeFloorState(provisioned = true, atRest = 10L)
        // Valid audience + seq, but signed by the WRONG key.
        val wrong = newKeypair()
        val b = sign(bundle(policySeq = 11L, childId = state.childDeviceId()), wrong)
        val result = PolicyAdmission.admit(
            bundle = b, store = state, applier = RecordingApplier(),
            pinParentKey = {}, pinnedParentPubkey = kp.publicKeyRaw,
        )
        assertTrue(result is PolicyAdmission.Result.Rejected)
        assertFalse((result as PolicyAdmission.Result.Rejected).malformed, "SIG_FAIL is strict fail-closed")
        assertEquals(10L, state.atRestFloor(), "bad sig must not advance the floor")
    }

    @Test
    fun validInOrderBundleAppliesAndAdvancesFloor() {
        val kp = newKeypair()
        val state = FakeFloorState(provisioned = true, atRest = 10L)
        val applier = RecordingApplier()
        val b = sign(bundle(policySeq = 11L, childId = state.childDeviceId()), kp)
        val result = PolicyAdmission.admit(
            bundle = b, store = state, applier = applier,
            pinParentKey = {}, pinnedParentPubkey = kp.publicKeyRaw,
        )
        assertTrue(result is PolicyAdmission.Result.Applied)
        assertEquals(11L, state.atRestFloor())
        // Floor must advance only AFTER apply (between apply and ack in call order).
        assertEquals(listOf("stage:11", "apply:11", "ack:11"), applier.calls)
    }

    // =========================================================================
    // crash-idempotency (re-apply same seq after crash => no regression)
    // =========================================================================

    @Test
    fun crashBeforeFloorAdvanceLeavesOldFloorAndReapplyIsIdempotent() {
        val kp = newKeypair()
        // Simulate a crash during apply: applier throws AFTER stage, BEFORE floor advance.
        val state = FakeFloorState(provisioned = true, atRest = 10L)
        val crashing = RecordingApplier(failApply = true)
        val b = sign(bundle(policySeq = 11L, childId = state.childDeviceId()), kp)

        val crashed = PolicyAdmission.admit(
            bundle = b, store = state, applier = crashing,
            pinParentKey = {}, pinnedParentPubkey = kp.publicKeyRaw,
        )
        assertTrue(crashed is PolicyAdmission.Result.Rejected, "apply failure => fail-closed reject")
        assertEquals(10L, state.atRestFloor(), "floor must NOT advance when apply fails (crash safety)")

        // Restart: the SAME bundle re-applies cleanly because the floor is still 10
        // (seq 11 > 10), so it is NOT a permanent REGRESSION.
        val healthy = RecordingApplier()
        val retried = PolicyAdmission.admit(
            bundle = b, store = state, applier = healthy,
            pinParentKey = {}, pinnedParentPubkey = kp.publicKeyRaw,
        )
        assertTrue(retried is PolicyAdmission.Result.Applied, "re-apply after crash must succeed (idempotent)")
        assertEquals(11L, state.atRestFloor())
    }

    @Test
    fun reapplyingSameSeqAfterSuccessfulApplyDoesNotRegress() {
        val kp = newKeypair()
        val state = FakeFloorState(provisioned = true, atRest = 10L)
        val b = sign(bundle(policySeq = 11L, childId = state.childDeviceId()), kp)

        val first = PolicyAdmission.admit(
            bundle = b, store = state, applier = RecordingApplier(),
            pinParentKey = {}, pinnedParentPubkey = kp.publicKeyRaw,
        )
        assertTrue(first is PolicyAdmission.Result.Applied)
        assertEquals(11L, state.atRestFloor())

        // Replaying the identical bundle now hits seq == floor => REGRESSION reject,
        // which is the correct idempotent outcome: the live policy is already this one,
        // the floor does not change, and we never DOWN-grade.
        val replay = PolicyAdmission.admit(
            bundle = b, store = state, applier = RecordingApplier(),
            pinParentKey = {}, pinnedParentPubkey = kp.publicKeyRaw,
        )
        assertTrue(replay is PolicyAdmission.Result.Rejected)
        assertEquals(11L, state.atRestFloor(), "floor stays put — no regression, no double-advance")
    }

    // =========================================================================
    // version + malformed structural checks
    // =========================================================================

    @Test
    fun wrongVersionRejectedMalformed() {
        val kp = newKeypair()
        val state = FakeFloorState(provisioned = true, atRest = 10L)
        val b = sign(bundle(policySeq = 11L, childId = state.childDeviceId(), v = 2), kp)
        val result = PolicyAdmission.admit(
            bundle = b, store = state, applier = RecordingApplier(),
            pinParentKey = {}, pinnedParentPubkey = kp.publicKeyRaw,
        )
        assertTrue(result is PolicyAdmission.Result.Rejected)
        assertTrue((result as PolicyAdmission.Result.Rejected).malformed)
    }
}

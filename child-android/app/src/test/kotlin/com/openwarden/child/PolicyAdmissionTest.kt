package com.openwarden.child

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
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

    private class Keypair(
        val privateKey: EdDSAPrivateKey,
        val publicKeyRaw: ByteArray,
    )

    private fun newKeypair(): Keypair {
        val seed = ByteArray(32).also { SecureRandom().nextBytes(it) }
        val privSpec = EdDSAPrivateKeySpec(seed, curve)
        val priv = EdDSAPrivateKey(privSpec)
        val pubSpec = EdDSAPublicKeySpec(privSpec.a, curve)
        return Keypair(priv, pubSpec.a.toByteArray())
    }

    /** Sign the JCS canonical body (bundle minus sig) and return the bundle with a real sig. */
    private fun sign(
        bundle: SignedBundle,
        kp: Keypair,
    ): SignedBundle {
        val body = BundleVerifier.canonicalBody(bundle.copy(sig = ""))
        return bundle.copy(sig = signBytesHex(body, kp))
    }

    /** Raw Ed25519 over arbitrary [body] bytes -> lowercase hex (for hand-built wire documents). */
    private fun signBytesHex(
        body: ByteArray,
        kp: Keypair,
    ): String {
        val engine = EdDSAEngine(MessageDigest.getInstance("SHA-512"))
        engine.initSign(kp.privateKey)
        engine.update(body)
        return engine.sign().joinToString("") { "%02x".format(it) }
    }

    // ---- ADR-040: the RECEIVED wire document is the verifier authority ------
    // In production the child verifies over the JSON object it RECEIVED, never a re-serialization of
    // its own typed model. Tests build that document from the typed (signed) bundle the same way the
    // wire would carry it, then drive admit() over the document — so a regression that silently
    // reverts to typed re-canonicalization is caught here.

    /** The wire JSON object a typed bundle serializes to (what the parent would transmit). */
    private fun docOf(bundle: SignedBundle): JsonObject = BundleVerifier.toWireDocument(bundle)

    /** Admit a typed signed bundle by feeding [PolicyAdmission.admit] its wire document (ADR-040). */
    private fun admit(
        bundle: SignedBundle,
        store: PolicyAdmission.FloorState,
        applier: PolicyAdmission.Applier,
        pinParentKey: (ByteArray) -> Unit,
        pinnedParentPubkey: ByteArray?,
        genesisPubkey: ByteArray? = null,
    ): PolicyAdmission.Result = PolicyAdmission.admit(docOf(bundle), store, applier, pinParentKey, pinnedParentPubkey, genesisPubkey)

    private fun bundle(
        policySeq: Long,
        childId: String,
        v: Int = 1,
        allowlist: List<String> = listOf("com.example.app"),
    ) = SignedBundle(
        v = v,
        child_device_id = childId,
        policy_seq = policySeq,
        // PROTOCOL.md §2: integer ms timestamps (u53-bounded), not ISO-8601 strings.
        issued_at = 1_767_225_600_000L, // 2026-01-01T00:00:00Z
        not_before = 1_767_225_600_000L,
        not_after = 4_102_444_799_000L, // 2099-12-31T23:59:59Z
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
        // R6: simulate a failed durable floor write (commit() == false => advanceFloor throws).
        var failAdvance: Boolean = false,
    ) : PolicyAdmission.FloorState {
        val acks = mutableListOf<Long>()

        override fun childDeviceId() = myId

        override fun isProvisioned() = provisioned

        override fun markProvisioned() {
            provisioned = true
        }

        override fun atRestFloor() = atRest

        override fun chainFloor() = chain

        override fun effectiveFloor(): Long? =
            when {
                atRest == null && chain == null -> null
                atRest == null -> chain
                chain == null -> atRest
                else -> maxOf(atRest!!, chain!!)
            }

        override fun advanceFloor(policySeq: Long) {
            if (failAdvance) throw IllegalStateException("simulated floor commit() failure (fail-closed)")
            val cur = atRest
            if (cur != null && policySeq <= cur) return // never lower; idempotent
            atRest = policySeq
        }

        // Rollback witness — instance-scoped here so each test is isolated. failNote simulates a
        // failed durable witness commit (R11): noteApplied throws fail-closed.
        var highWater: Long? = null
        var failNote: Boolean = false

        override fun appliedHighWater(): Long? = highWater

        override fun noteApplied(policySeq: Long) {
            if (failNote) throw IllegalStateException("simulated staged-witness commit failure (fail-closed)")
            val cur = highWater
            if (cur == null || policySeq > cur) highWater = policySeq
        }
    }

    /** Records the two-phase commit calls in order. */
    private class RecordingApplier(
        var failApply: Boolean = false,
    ) : PolicyAdmission.Applier {
        val calls = mutableListOf<String>()

        override fun stage(bundle: SignedBundle) {
            calls += "stage:${bundle.policy_seq}"
        }

        override fun applyAndFsync(bundle: SignedBundle) {
            calls += "apply:${bundle.policy_seq}"
            if (failApply) throw RuntimeException("simulated DPM failure")
        }

        override fun ack(policySeq: Long) {
            calls += "ack:$policySeq"
        }
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
        val result =
            admit(
                bundle = b,
                store = state,
                applier = applier,
                pinParentKey = { pinned = it },
                pinnedParentPubkey = null, // no key pinned yet — genesis candidate
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

    // =========================================================================
    // concurrent-admission ordering (R5) — serialize the whole transaction
    // =========================================================================

    @Test
    fun concurrentAdmitsNeverApplyAStaleLowerSeqAfterAHigherSeq() {
        // R5: two /policy admissions racing on different threads must be serialized so a lower-seq
        // bundle can NEVER apply after a higher-seq one advanced the floor (which would re-open a
        // freshly-denied app). ADMIT_LOCK + the in-lock floor read/advance make the floor check
        // atomic, so the second admit re-reads the advanced floor and rejects the stale bundle.
        val kp = newKeypair()
        val state = FakeFloorState(provisioned = true, atRest = 9L) // floor starts at 9
        val applier = RecordingApplier()
        val b10 = sign(bundle(policySeq = 10L, childId = state.childDeviceId()), kp)
        val b11 = sign(bundle(policySeq = 11L, childId = state.childDeviceId()), kp)

        val barrier = java.util.concurrent.CountDownLatch(1)
        val threads =
            listOf(b11, b10).map { b ->
                Thread {
                    barrier.await()
                    admit(b, state, applier, pinParentKey = {}, pinnedParentPubkey = kp.publicKeyRaw)
                }
            }
        threads.forEach { it.start() }
        barrier.countDown() // release both as near-simultaneously as the scheduler allows
        threads.forEach { it.join() }

        // Whatever the interleaving, the floor ends at the highest seq...
        assertEquals(11L, state.atRestFloor(), "floor must end at the highest applied seq")
        // ...and the apply order is non-decreasing: seq 10 is NEVER applied after seq 11.
        val appliedSeqs = applier.calls.filter { it.startsWith("apply:") }.map { it.substringAfter(":").toLong() }
        assertEquals(appliedSeqs.sorted(), appliedSeqs, "applies must be monotonic — no stale lower seq after a higher one")
        assertTrue(11L in appliedSeqs, "the higher seq must have applied")
    }

    @Test
    fun durableFloorWriteFailureFailsClosedWithNoAck() {
        // R6: if the durable floor write fails (advanceFloor throws on commit()==false), admit() must
        // NOT ack or report Applied — it must fail closed (Rejected). Otherwise a success is reported
        // while the persisted floor stays old, letting a stale lower-seq bundle re-admit after restart
        // and undo a newer deny, defeating the R5 lock.
        val kp = newKeypair()
        val state = FakeFloorState(provisioned = true, atRest = 9L, failAdvance = true)
        val applier = RecordingApplier()
        val b = sign(bundle(policySeq = 10L, childId = state.childDeviceId()), kp)

        val result = admit(b, state, applier, pinParentKey = {}, pinnedParentPubkey = kp.publicKeyRaw)

        assertTrue(result is PolicyAdmission.Result.Rejected, "a failed durable floor write must be Rejected, not Applied")
        assertFalse(applier.calls.any { it.startsWith("ack:") }, "no ack may be emitted when the floor write fails")
        assertEquals(9L, state.atRestFloor(), "the floor must remain at its old value (not advanced)")
    }

    @Test
    fun appliedHighWaterBlocksRollbackAfterAFailedFloorWrite() {
        // R7: seq 11 applies but advanceFloor fails (R6 => Rejected, no ack) — yet the apply landed
        // and the durable floor stayed at 9. A same-process replay of an older VALID seq 10 (signed,
        // 9 < 10 < 11) must NOT be admitted, or it would roll the policy back to v10. The in-memory
        // applied high-water (=11) raises the effective floor so seq 10 is rejected.
        val kp = newKeypair()
        val state = FakeFloorState(provisioned = true, atRest = 9L, failAdvance = true)
        val applier = RecordingApplier()

        val b11 = sign(bundle(policySeq = 11L, childId = state.childDeviceId()), kp)
        val r11 = admit(b11, state, applier, pinParentKey = {}, pinnedParentPubkey = kp.publicKeyRaw)
        assertTrue(r11 is PolicyAdmission.Result.Rejected, "the failed floor write is Rejected (R6)")
        assertTrue(applier.calls.contains("apply:11"), "but seq 11 was actually applied (the dangerous partial state)")
        assertEquals(11L, state.appliedHighWater(), "the applied high-water must record seq 11")

        // The floor store recovers; an attacker replays the older valid seq 10.
        state.failAdvance = false
        val b10 = sign(bundle(policySeq = 10L, childId = state.childDeviceId()), kp)
        val r10 = admit(b10, state, applier, pinParentKey = {}, pinnedParentPubkey = kp.publicKeyRaw)

        assertTrue(r10 is PolicyAdmission.Result.Rejected, "seq 10 must be rejected — the high-water (11) blocks the rollback")
        assertFalse(applier.calls.contains("apply:10"), "the older bundle must never be applied")
    }

    @Test
    fun retryingTheSameSeqRepairsTheStaleDurableFloorAfterTransientFailure() {
        // R8: seq 11 applies but advanceFloor fails transiently (R6 => Rejected; high-water = 11,
        // durable floor still 9). A retry of the SAME seq 11 must be admitted so it can re-advance
        // the durable floor — the high-water blocks only STRICTLY-LOWER seqs (the rollback), not the
        // equal one. Otherwise the durable floor stays stale until a higher seq arrives, widening
        // the cross-restart rollback window unnecessarily.
        val kp = newKeypair()
        val state = FakeFloorState(provisioned = true, atRest = 9L, failAdvance = true)
        val applier = RecordingApplier()
        val b11 = sign(bundle(policySeq = 11L, childId = state.childDeviceId()), kp)

        val first = admit(b11, state, applier, pinParentKey = {}, pinnedParentPubkey = kp.publicKeyRaw)
        assertTrue(first is PolicyAdmission.Result.Rejected, "the transient floor-write failure is Rejected (R6)")
        assertEquals(9L, state.atRestFloor(), "durable floor is still stale after the failed write")
        assertEquals(11L, state.appliedHighWater(), "high-water recorded the applied seq 11")

        // The store recovers; the parent retries the SAME bundle.
        state.failAdvance = false
        val retry = admit(b11, state, applier, pinParentKey = {}, pinnedParentPubkey = kp.publicKeyRaw)

        assertTrue(retry is PolicyAdmission.Result.Applied, "retrying the same seq must repair the floor, not be rejected")
        assertEquals(11L, state.atRestFloor(), "the durable floor is now advanced (repaired)")
    }

    @Test
    fun stagedButFailedApplyStillBlocksALowerSeqRollback() {
        // R9: stage() persists the candidate as the active bundle, and applyAndFsync() can mutate
        // live/durable state before throwing (e.g. allowlist verify -> lockNow + throw). The rollback
        // witness must be recorded at STAGE — else a throwing apply leaves the staged newer policy
        // with no witness, and a lower valid seq could overwrite (roll back) it.
        val kp = newKeypair()
        val state = FakeFloorState(provisioned = true, atRest = 9L)
        val applier = RecordingApplier(failApply = true) // applyAndFsync throws after stage
        val b11 = sign(bundle(policySeq = 11L, childId = state.childDeviceId()), kp)

        val first = admit(b11, state, applier, pinParentKey = {}, pinnedParentPubkey = kp.publicKeyRaw)
        assertTrue(first is PolicyAdmission.Result.Rejected, "a throwing apply is Rejected (fail-closed)")
        assertTrue(applier.calls.contains("stage:11"), "but the bundle was staged — now the active policy")
        assertEquals(11L, state.appliedHighWater(), "the witness must be recorded at stage, even though apply threw")

        // A lower valid seq must not roll back the staged newer policy.
        val b10 = sign(bundle(policySeq = 10L, childId = state.childDeviceId()), kp)
        val r10 = admit(b10, state, applier, pinParentKey = {}, pinnedParentPubkey = kp.publicKeyRaw)
        assertTrue(r10 is PolicyAdmission.Result.Rejected, "seq 10 must be rejected — staged seq 11 blocks the rollback")
        assertFalse(applier.calls.contains("stage:10"), "the older bundle must never even be staged")

        // The transient apply failure clears; retrying the same seq repairs (applies + advances floor).
        applier.failApply = false
        val retry = admit(b11, state, applier, pinParentKey = {}, pinnedParentPubkey = kp.publicKeyRaw)
        assertTrue(retry is PolicyAdmission.Result.Applied, "retrying the same seq must repair once apply succeeds")
        assertEquals(11L, state.atRestFloor(), "the durable floor advanced after the successful retry")
    }

    @Test
    fun durableStageWitnessSurvivesRestartAndStillBlocksRollback() {
        // R10: the stage witness must be DURABLE. seq 11 stages then applyAndFsync throws (Rejected,
        // durable floor stays 9). After a process RESTART the in-memory witness would be gone, so a
        // replayed valid seq 10 could roll back the staged newer policy. Production persists the
        // witness (ReplayFloorStore KEY_STAGED); here we carry it to a fresh store to model restart.
        val kp = newKeypair()
        val state1 = FakeFloorState(provisioned = true, atRest = 9L)
        val applier1 = RecordingApplier(failApply = true)
        val b11 = sign(bundle(policySeq = 11L, childId = state1.childDeviceId()), kp)
        assertTrue(admit(b11, state1, applier1, pinParentKey = {}, pinnedParentPubkey = kp.publicKeyRaw) is PolicyAdmission.Result.Rejected)
        assertEquals(11L, state1.appliedHighWater(), "stage witness recorded")

        // Simulate restart: a fresh store with only the DURABLE state (floor 9, stage witness 11).
        val state2 = FakeFloorState(provisioned = true, atRest = 9L)
        state2.highWater = state1.appliedHighWater() // models the persisted KEY_STAGED surviving reboot
        val applier2 = RecordingApplier()

        val b10 = sign(bundle(policySeq = 10L, childId = state2.childDeviceId()), kp)
        val r10 = admit(b10, state2, applier2, pinParentKey = {}, pinnedParentPubkey = kp.publicKeyRaw)
        assertTrue(r10 is PolicyAdmission.Result.Rejected, "after restart the durable witness still blocks seq 10")
        assertFalse(applier2.calls.contains("stage:10"), "the older bundle must not be staged after restart")

        // seq 11 still repairs the durable floor after restart.
        val r11 = admit(b11, state2, applier2, pinParentKey = {}, pinnedParentPubkey = kp.publicKeyRaw)
        assertTrue(r11 is PolicyAdmission.Result.Applied, "seq 11 repairs the durable floor after restart")
        assertEquals(11L, state2.atRestFloor())
    }

    @Test
    fun witnessWriteFailureRejectsBeforeMakingTheBundleActive() {
        // R11: the durable witness is written BEFORE stage() makes the bundle active. If the witness
        // can't be persisted, admit fails closed WITHOUT staging — so there is no active newer bundle
        // for a later lower seq to roll back to (the staged-but-failed window never opens).
        val kp = newKeypair()
        val state = FakeFloorState(provisioned = true, atRest = 9L).apply { failNote = true }
        val applier = RecordingApplier()
        val b11 = sign(bundle(policySeq = 11L, childId = state.childDeviceId()), kp)

        val result = admit(b11, state, applier, pinParentKey = {}, pinnedParentPubkey = kp.publicKeyRaw)

        assertTrue(result is PolicyAdmission.Result.Rejected, "a failed durable witness write must be Rejected")
        assertFalse(applier.calls.contains("stage:11"), "the bundle must NOT be staged when the witness can't be persisted")
        assertEquals(9L, state.atRestFloor(), "floor unchanged; nothing became active")
    }

    @Test
    fun genesisWitnessFailureLeavesACleanIdempotentlyRetryableState() {
        // R12: on genesis the durable witness is written BEFORE pin/mark. If it fails, nothing is
        // pinned/marked/staged, so the device is NOT stranded provisioned-but-floorless — the same
        // signed genesis bundle repairs cleanly on retry.
        val kp = newKeypair()
        val state = FakeFloorState(provisioned = false, atRest = null).apply { failNote = true }
        val applier = RecordingApplier()
        var pinned: ByteArray? = null
        val b1 = sign(bundle(policySeq = 1L, childId = state.childDeviceId()), kp)

        val first = admit(b1, state, applier, pinParentKey = { pinned = it }, pinnedParentPubkey = null, genesisPubkey = kp.publicKeyRaw)
        assertTrue(first is PolicyAdmission.Result.Rejected, "genesis witness-write failure is Rejected")
        assertFalse(state.isProvisioned(), "must NOT mark provisioned when the witness failed")
        assertEquals(null, pinned, "must NOT pin the key when the witness failed")
        assertFalse(applier.calls.contains("stage:1"), "must not stage")

        // Storage recovers; retry the SAME signed genesis bundle — must repair as a clean genesis.
        state.failNote = false
        val retry = admit(b1, state, applier, pinParentKey = { pinned = it }, pinnedParentPubkey = null, genesisPubkey = kp.publicKeyRaw)
        assertTrue(retry is PolicyAdmission.Result.Applied, "retry must repair as a clean genesis, not strand as an anomaly")
        assertTrue((retry as PolicyAdmission.Result.Applied).genesis)
        assertEquals(1L, state.atRestFloor())
        assertTrue(state.isProvisioned())
        assertTrue(pinned!!.contentEquals(kp.publicKeyRaw), "the parent key is pinned on the repaired genesis")
    }

    @Test
    fun genesisSeqZeroRejectedNeverLivePolicy() {
        val kp = newKeypair()
        val state = FakeFloorState(provisioned = false, atRest = null)
        val b = sign(bundle(policySeq = 0L, childId = state.childDeviceId()), kp)
        val result =
            admit(
                bundle = b,
                store = state,
                applier = RecordingApplier(),
                pinParentKey = {},
                pinnedParentPubkey = null,
                genesisPubkey = kp.publicKeyRaw,
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
        val result =
            admit(
                bundle = b,
                store = state,
                applier = RecordingApplier(),
                pinParentKey = {},
                pinnedParentPubkey = kp.publicKeyRaw,
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
        val result =
            admit(
                bundle = b,
                store = state,
                applier = RecordingApplier(),
                pinParentKey = {},
                pinnedParentPubkey = null, // provisioned but key gone
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
        val result =
            admit(
                bundle = b,
                store = state,
                applier = RecordingApplier(),
                pinParentKey = {},
                pinnedParentPubkey = kp.publicKeyRaw,
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
        val result =
            admit(
                bundle = b,
                store = state,
                applier = RecordingApplier(),
                pinParentKey = {},
                pinnedParentPubkey = kp.publicKeyRaw,
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
        val result =
            admit(
                bundle = b,
                store = state,
                applier = RecordingApplier(),
                pinParentKey = {},
                pinnedParentPubkey = kp.publicKeyRaw,
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
        val result =
            admit(
                bundle = b,
                store = state,
                applier = RecordingApplier(),
                pinParentKey = {},
                pinnedParentPubkey = kp.publicKeyRaw,
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
        val result =
            admit(
                bundle = b,
                store = state,
                applier = RecordingApplier(),
                pinParentKey = {},
                pinnedParentPubkey = kp.publicKeyRaw,
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
        val result =
            admit(
                bundle = b,
                store = state,
                applier = RecordingApplier(),
                pinParentKey = {},
                pinnedParentPubkey = kp.publicKeyRaw,
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
        val result =
            admit(
                bundle = b,
                store = state,
                applier = RecordingApplier(),
                pinParentKey = {},
                pinnedParentPubkey = kp.publicKeyRaw,
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
        val result =
            admit(
                bundle = b,
                store = state,
                applier = RecordingApplier(),
                pinParentKey = {},
                pinnedParentPubkey = kp.publicKeyRaw,
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
        val result =
            admit(
                bundle = b,
                store = state,
                applier = RecordingApplier(),
                pinParentKey = {},
                pinnedParentPubkey = kp.publicKeyRaw,
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
        val result =
            admit(
                bundle = b,
                store = state,
                applier = RecordingApplier(),
                pinParentKey = {},
                pinnedParentPubkey = kp.publicKeyRaw,
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
        val result =
            admit(
                bundle = b,
                store = state,
                applier = applier,
                pinParentKey = {},
                pinnedParentPubkey = kp.publicKeyRaw,
            )
        assertTrue(result is PolicyAdmission.Result.Applied)
        assertEquals(11L, state.atRestFloor())
        // Floor must advance only AFTER apply (between apply and ack in call order).
        assertEquals(listOf("stage:11", "apply:11", "ack:11"), applier.calls)
    }

    // =========================================================================
    // ADR-040 / #91: verify over received bytes, not typed re-canonicalization
    // =========================================================================

    @Test
    fun verifiesOverReceivedBytesNotTypedReCanonicalization() {
        // ADR-040: the child verifies over the RECEIVED document. A parent-signed field the child's
        // typed SignedBundle does not model must NOT flip the signature — but a verifier that
        // re-canonicalizes its own typed re-serialization (which DROPS the unmodeled field) computes
        // different bytes and FAILS the valid signature. This pins the fix end of that drift.
        val kp = newKeypair()
        val base = bundle(policySeq = 7L, childId = "child-aaaa")
        // The exact bytes the parent signs: the wire document INCLUDING a field the child omits.
        val unsignedWire =
            JsonObject(
                docOf(base).toMutableMap().apply {
                    remove("sig")
                    put("x_future_field", JsonPrimitive("parent-signed-but-child-unmodeled"))
                },
            )
        val sigHex = signBytesHex(Canonical.canonicalize(unsignedWire).encodeToByteArray(), kp)
        val receivedWire = JsonObject(unsignedWire.toMutableMap().apply { put("sig", JsonPrimitive(sigHex)) })

        // Verify over the RECEIVED bytes => the parent-signed field is honored => VALID.
        assertTrue(
            BundleVerifier.verifyDocument(receivedWire, kp.publicKeyRaw),
            "verifyDocument must verify over the received document, incl. parent-signed fields the child does not model",
        )

        // The pre-ADR-040 behavior — canonicalize the child's typed re-serialization (no
        // x_future_field) — computes different bytes and FAILS the very same valid signature.
        val typedReSerialized =
            JsonObject(
                docOf(base).toMutableMap().apply { put("sig", JsonPrimitive(sigHex)) },
            )
        assertFalse(
            BundleVerifier.verifyDocument(typedReSerialized, kp.publicKeyRaw),
            "re-canonicalizing the typed model drops the unmodeled field and fails the valid sig (the drift ADR-040 fixes)",
        )
    }

    @Test
    fun verifiedButUnparseableBodyRejectedMalformedNeverApplied() {
        // ADR-019 D2 / ADR-040: verify first, parse second. A document that VERIFIES (valid sig over
        // the received bytes) but whose typed decode then fails must be rejected MALFORMED AFTER
        // verification, and never applied. policy_seq is carried as a JSON string — JCS-safe (it is a
        // string, not an integer), validly signed, but SignedBundle.policy_seq:Long cannot decode it.
        val kp = newKeypair()
        val state = FakeFloorState(provisioned = true, atRest = 10L)
        val applier = RecordingApplier()
        val unsigned =
            JsonObject(
                docOf(bundle(policySeq = 11L, childId = state.childDeviceId())).toMutableMap().apply {
                    remove("sig")
                    put("policy_seq", JsonPrimitive("not-a-number"))
                },
            )
        val sigHex = signBytesHex(Canonical.canonicalize(unsigned).encodeToByteArray(), kp)
        val doc = JsonObject(unsigned.toMutableMap().apply { put("sig", JsonPrimitive(sigHex)) })

        val result = PolicyAdmission.admit(doc, state, applier, pinParentKey = {}, pinnedParentPubkey = kp.publicKeyRaw)

        assertTrue(result is PolicyAdmission.Result.Rejected, "a verified-but-unparseable body must be rejected")
        assertTrue((result as PolicyAdmission.Result.Rejected).malformed, "verified-but-unparseable is MALFORMED")
        assertTrue(result.reason.contains("unparseable"), "reason should name the post-verify parse failure")
        assertTrue(applier.calls.isEmpty(), "nothing may be staged/applied")
    }

    @Test
    fun admitAppliesADocumentWithAParentSignedFieldTheChildDoesNotModel() {
        // ADR-040 end-to-end (Codex review): a parent-signed field the child's typed model omits must
        // NOT break admission — the sig verifies over the received doc and the bundle applies. Locks
        // the FULL pipeline (admit), not just the verifier unit.
        val kp = newKeypair()
        val state = FakeFloorState(provisioned = true, atRest = 10L)
        val applier = RecordingApplier()
        val unsigned =
            JsonObject(
                docOf(bundle(policySeq = 11L, childId = state.childDeviceId())).toMutableMap().apply {
                    remove("sig")
                    put("x_future_field", JsonPrimitive("parent-signed-but-child-unmodeled"))
                },
            )
        val sigHex = signBytesHex(Canonical.canonicalize(unsigned).encodeToByteArray(), kp)
        val doc = JsonObject(unsigned.toMutableMap().apply { put("sig", JsonPrimitive(sigHex)) })

        val result = PolicyAdmission.admit(doc, state, applier, pinParentKey = {}, pinnedParentPubkey = kp.publicKeyRaw)

        assertTrue(result is PolicyAdmission.Result.Applied, "an unmodeled parent-signed field must not break admission")
        assertEquals(11L, state.atRestFloor())
        assertEquals(listOf("stage:11", "apply:11", "ack:11"), applier.calls)
    }

    @Test
    fun verifyDocumentHandlesUnicodeAndEscapingOverReceivedBytes() {
        // ADR-019 D5 / PROTOCOL §3.1 rule 5 (crypto review LOW-1): unicode + escaped strings must
        // canonicalize identically on the received-bytes path. Sign over the canonical bytes of a doc
        // with accented + tab + astral-plane characters, then verify through verifyDocument.
        val kp = newKeypair()
        val b = bundle(policySeq = 7L, childId = "child-aaaa", allowlist = listOf("café", "tab\there", "emoji😀"))
        val unsigned = JsonObject(docOf(b).toMutableMap().apply { remove("sig") })
        val sigHex = signBytesHex(Canonical.canonicalize(unsigned).encodeToByteArray(), kp)
        val signed = JsonObject(unsigned.toMutableMap().apply { put("sig", JsonPrimitive(sigHex)) })
        assertTrue(
            BundleVerifier.verifyDocument(signed, kp.publicKeyRaw),
            "a bundle with unicode/escaped strings must verify over the received bytes",
        )
        // Same signature, one accent stripped from the received doc => different canonical bytes => fail.
        val b2 = bundle(policySeq = 7L, childId = "child-aaaa", allowlist = listOf("cafe", "tab\there", "emoji😀"))
        val tampered = JsonObject(docOf(b2).toMutableMap().apply { put("sig", JsonPrimitive(sigHex)) })
        assertFalse(
            BundleVerifier.verifyDocument(tampered, kp.publicKeyRaw),
            "altering a unicode character must break verification over received bytes",
        )
    }

    @Test
    fun duplicateKeyInReceivedJsonIsFailClosed() {
        // crypto-review MED-1 / Codex INFO: kotlinx parseToJsonElement is last-wins on duplicate keys.
        // The signature is over the value the parent signed; a received body with a duplicate key whose
        // LAST value differs collapses to a different canonical form => SIG_FAIL. No bypass possible.
        val kp = newKeypair()
        val signed = sign(bundle(policySeq = 11L, childId = "child-aaaa"), kp)
        val wire = Canonical.canonicalize(docOf(signed)) // canonical wire JSON incl sig
        // Inject a duplicate policy_seq with a DIFFERENT last value (what a MITM would attempt).
        val tamperedRaw = wire.replaceFirst("\"policy_seq\":11", "\"policy_seq\":11,\"policy_seq\":99")
        val parsed = Json.parseToJsonElement(tamperedRaw) as JsonObject
        assertEquals("99", (parsed["policy_seq"] as JsonPrimitive).content, "kotlinx collapses duplicates last-wins")
        assertFalse(
            BundleVerifier.verifyDocument(parsed, kp.publicKeyRaw),
            "a duplicate key that changes the effective value must fail verification (fail-closed)",
        )
    }

    @Test
    fun nullValueInReceivedDocumentRejected() {
        // PROTOCOL §3.1 rule 6 / ADR-019 D4 (Codex review): null is forbidden in a signed document.
        // Even validly signed, a received doc carrying a null must be rejected before verification.
        val kp = newKeypair()
        val unsigned =
            JsonObject(
                docOf(bundle(policySeq = 11L, childId = "child-aaaa")).toMutableMap().apply {
                    remove("sig")
                    put("private_dns_probe", JsonNull)
                },
            )
        val sigHex = signBytesHex(Canonical.canonicalize(unsigned).encodeToByteArray(), kp)
        val doc = JsonObject(unsigned.toMutableMap().apply { put("sig", JsonPrimitive(sigHex)) })
        assertFalse(
            BundleVerifier.verifyDocument(doc, kp.publicKeyRaw),
            "a null anywhere in the signed document must fail closed (§3.1 rule 6)",
        )
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

        val crashed =
            admit(
                bundle = b,
                store = state,
                applier = crashing,
                pinParentKey = {},
                pinnedParentPubkey = kp.publicKeyRaw,
            )
        assertTrue(crashed is PolicyAdmission.Result.Rejected, "apply failure => fail-closed reject")
        assertEquals(10L, state.atRestFloor(), "floor must NOT advance when apply fails (crash safety)")

        // Restart: the SAME bundle re-applies cleanly because the floor is still 10
        // (seq 11 > 10), so it is NOT a permanent REGRESSION.
        val healthy = RecordingApplier()
        val retried =
            admit(
                bundle = b,
                store = state,
                applier = healthy,
                pinParentKey = {},
                pinnedParentPubkey = kp.publicKeyRaw,
            )
        assertTrue(retried is PolicyAdmission.Result.Applied, "re-apply after crash must succeed (idempotent)")
        assertEquals(11L, state.atRestFloor())
    }

    @Test
    fun reapplyingSameSeqAfterSuccessfulApplyDoesNotRegress() {
        val kp = newKeypair()
        val state = FakeFloorState(provisioned = true, atRest = 10L)
        val b = sign(bundle(policySeq = 11L, childId = state.childDeviceId()), kp)

        val first =
            admit(
                bundle = b,
                store = state,
                applier = RecordingApplier(),
                pinParentKey = {},
                pinnedParentPubkey = kp.publicKeyRaw,
            )
        assertTrue(first is PolicyAdmission.Result.Applied)
        assertEquals(11L, state.atRestFloor())

        // Replaying the identical bundle now hits seq == floor => REGRESSION reject,
        // which is the correct idempotent outcome: the live policy is already this one,
        // the floor does not change, and we never DOWN-grade.
        val replay =
            admit(
                bundle = b,
                store = state,
                applier = RecordingApplier(),
                pinParentKey = {},
                pinnedParentPubkey = kp.publicKeyRaw,
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
        val result =
            admit(
                bundle = b,
                store = state,
                applier = RecordingApplier(),
                pinParentKey = {},
                pinnedParentPubkey = kp.publicKeyRaw,
            )
        assertTrue(result is PolicyAdmission.Result.Rejected)
        assertTrue((result as PolicyAdmission.Result.Rejected).malformed)
    }

    // =========================================================================
    // §2 conformance: the conformed field set verifies (the PR #50 fix)
    // =========================================================================

    @Test
    fun exactProtocolSection2FieldSetVerifies() {
        // A bundle whose field set is EXACTLY PROTOCOL.md §2 — every required field present
        // (v, policy_seq, child_device_id, issued_at, not_before, not_after, nonce, policy{
        // allowlist, blocklist, windows, restrictions}) plus optional policy fields — must
        // verify and apply at the child. This is the conformed shape the parent now signs;
        // before the fix the child schema lacked not_before/not_after/blocklist and carried a
        // non-§2 ISO expires_at, so a real parent bundle never verified (SIG_FAIL).
        val kp = newKeypair()
        val state = FakeFloorState(provisioned = true, atRest = 10L)
        val full =
            SignedBundle(
                v = 1,
                child_device_id = state.childDeviceId(),
                policy_seq = 11L,
                issued_at = 1_767_225_600_000L,
                not_before = 1_767_225_600_000L,
                not_after = 4_102_444_799_000L,
                nonce = "9f1b3c4d5e6f70819a2b3c4d5e6f7081",
                policy =
                    PolicyDoc(
                        allowlist = listOf("com.android.chrome"),
                        blocklist = listOf("com.discord"),
                        windows =
                            listOf(
                                TimeWindow(
                                    pkg = "com.google.android.youtube",
                                    allow = "16:00-18:00",
                                    days = "Mon,Tue",
                                    tz = "America/Los_Angeles",
                                ),
                            ),
                        restrictions = listOf("DISALLOW_CONFIG_VPN"),
                        private_dns = "openwarden.example.com",
                        frp_account_email = "parent@example.com",
                    ),
                sig = "",
            )
        val signed = sign(full, kp)
        val result =
            admit(
                bundle = signed,
                store = state,
                applier = RecordingApplier(),
                pinParentKey = {},
                pinnedParentPubkey = kp.publicKeyRaw,
            )
        assertTrue(result is PolicyAdmission.Result.Applied, "exact §2 field set must verify + apply")
        assertEquals(11L, (result as PolicyAdmission.Result.Applied).policySeq)
    }

    // =========================================================================
    // CROSS-IMPLEMENTATION INTEROP VECTOR (the assertion that catches SIG_FAIL)
    // =========================================================================

    /**
     * The child [Canonical] (ported) MUST produce the SAME canonical bytes for the fixed §2
     * bundle as the proto [com.openwarden.proto.Canonical] (parent side). Both this and the
     * proto CanonicalTest.interopGoldenCanonicalBytesAreStable() pin the SAME hardcoded hex.
     * If the two canonicalizers ever diverge on the §2 schema, exactly one of these two
     * tests fails against the shared constant — the PR #50 crux (parent/child signing
     * different field sets) is then caught at unit-test time, not at runtime SIG_FAIL.
     */
    private val interopGoldenHex =
        "7b226368696c645f6465766963655f6964223a226465762d31222c226973737565645f6174223a35302c" +
            "226e6f6e6365223a223966316233633464356536663730383139613262336334643565366637303831" +
            "222c226e6f745f6166746572223a3230302c226e6f745f6265666f7265223a3130302c22706f6c6963" +
            "79223a7b22616c6c6f776c697374223a5b22636f6d2e61225d2c22626c6f636b6c697374223a5b5d2c" +
            "227265737472696374696f6e73223a5b5d2c2277696e646f7773223a5b5d7d2c22706f6c6963795f73" +
            "6571223a352c2276223a317d"

    private fun interopBundle() =
        SignedBundle(
            v = 1,
            child_device_id = "dev-1",
            policy_seq = 5L,
            issued_at = 50L,
            not_before = 100L,
            not_after = 200L,
            nonce = "9f1b3c4d5e6f70819a2b3c4d5e6f7081",
            policy = PolicyDoc(allowlist = listOf("com.a")),
            sig = "",
        )

    @Test
    fun interopGoldenCanonicalBytesMatchProto() {
        // body := JCS canonical of the §2 bundle with "sig" removed (BundleVerifier.canonicalBody).
        val body = BundleVerifier.canonicalBody(interopBundle())
        val hex = body.joinToString("") { "%02x".format(it) }
        assertEquals(interopGoldenHex, hex, "child Canonical must agree with proto on the §2 golden bundle")
    }

    @Test
    fun interopLibsodiumSignatureVerifiesAtChild() {
        // STRONGEST cross-impl assertion: a signature produced by libsodium crypto_sign
        // (the PARENT's crypto) over the §2 golden canonical bytes is pinned below, and the
        // child's net.i2p verifier MUST accept it. Both libsodium and net.i2p are RFC 8032
        // Ed25519; this proves a real parent-signed §2 bundle verifies at the child — the exact
        // path that was broken in PR #50 when the two sides signed different field sets.
        val pubRaw = hex(KAT_PUB)
        val signed = interopBundle().copy(sig = KAT_SIG_GOLDEN)
        // ADR-040: pin the LIVE verifier path — verifyDocument over the received wire document.
        assertTrue(
            BundleVerifier.verifyDocument(docOf(signed), pubRaw),
            "libsodium signature over the §2 canonical bytes must verify under the child verifier",
        )
        // Tamper check: flipping the audience invalidates the (whole-object) signature.
        assertFalse(
            BundleVerifier.verifyDocument(docOf(signed.copy(child_device_id = "dev-2")), pubRaw),
            "altering a signed field must break verification",
        )
    }

    @Test
    fun childSignThenVerifyRoundTripsOverSection2Bytes() {
        // The child can also sign+verify the §2 canonical bytes itself (round-trip), confirming
        // the verifier consumes exactly the bytes a signer produces over the conformed schema.
        val kp = newKeypair()
        val b = interopBundle()
        val body = BundleVerifier.canonicalBody(b)
        val engine = EdDSAEngine(MessageDigest.getInstance("SHA-512"))
        engine.initSign(kp.privateKey)
        engine.update(body)
        val sig = engine.sign().joinToString("") { "%02x".format(it) }
        assertTrue(BundleVerifier.verifyDocument(docOf(b.copy(sig = sig)), kp.publicKeyRaw))
    }

    @Test
    fun ed25519MatchesLibsodiumKat() {
        // Known-answer test: the child's net.i2p Ed25519 over a FIXED seed must produce the
        // SAME pubkey and the SAME signature (over the empty message) as libsodium crypto_sign
        // (the parent's crypto). Both are RFC 8032, so they are byte-for-byte interoperable —
        // a bundle the parent signs is one the child can verify. Pinned values below were
        // produced by libsodium (PyNaCl) for seed = 00 01 02 ... 1f.
        val seed = hex(KAT_SEED)
        val privSpec = EdDSAPrivateKeySpec(seed, curve)
        val priv = EdDSAPrivateKey(privSpec)
        val pubRaw = EdDSAPublicKeySpec(privSpec.a, curve).a.toByteArray()
        assertEquals(KAT_PUB, pubRaw.joinToString("") { "%02x".format(it) }, "Ed25519 pubkey must match libsodium")

        val engine = EdDSAEngine(MessageDigest.getInstance("SHA-512"))
        engine.initSign(priv)
        engine.update(ByteArray(0)) // empty message
        val sig = engine.sign().joinToString("") { "%02x".format(it) }
        assertEquals(KAT_SIG_EMPTY, sig, "Ed25519 signature over empty message must match libsodium (RFC 8032)")
    }

    private fun hex(s: String): ByteArray = s.chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    companion object {
        // Cross-impl Ed25519 KAT. Values produced by libsodium (PyNaCl) — the parent's crypto —
        // for the fixed seed below; libsodium and net.i2p both implement RFC 8032 Ed25519, so
        // these must match byte-for-byte on the child side (proves interoperability).
        const val KAT_SEED = "000102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f"
        const val KAT_PUB = "03a107bff3ce10be1d70dd18e74bc09967e4d6309ba50d5f1ddc8664125531b8"
        const val KAT_SIG_EMPTY =
            "9ca53579530654d5c3df77089ef45eda613e2fedf670e96bedac4639504e5845" +
                "ef4b95d5793077233dd16817b2532e9c5525872a73a4ad74b759369a9e05c102"

        // libsodium signature over the §2 GOLDEN canonical bytes (interopBundle, sig stripped),
        // under KAT_SEED. The child must verify this exact parent-produced signature.
        const val KAT_SIG_GOLDEN =
            "389438b0038772a39ba2bcc203a20befcd905af4fb24169d1cfae0bef49b06fc" +
                "ec28936a14447f15464491461407bb6ebedff060f1cbdcb347448bf61b9a1f09"
    }
}

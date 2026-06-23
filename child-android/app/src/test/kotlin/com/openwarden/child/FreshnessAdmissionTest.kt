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
import kotlin.test.assertTrue

/**
 * ADR-041 §5.1 freshness-window admission (issue #90, surface A). Pure JVM — the §5.1 monotonic
 * estimate is injected via the in-memory anchor + the `nowElapsedMs` admit() arg, so the
 * CLOCK_SKEW / EXPIRED / within-window / re-anchor branches are deterministic without a device.
 */
class FreshnessAdmissionTest {

    private val curve = EdDSANamedCurveTable.getByName("Ed25519")

    private class Keypair(val privateKey: EdDSAPrivateKey, val publicKeyRaw: ByteArray)

    private fun newKeypair(): Keypair {
        val seed = ByteArray(32).also { SecureRandom().nextBytes(it) }
        val privSpec = EdDSAPrivateKeySpec(seed, curve)
        val pubSpec = EdDSAPublicKeySpec(privSpec.a, curve)
        return Keypair(EdDSAPrivateKey(privSpec), pubSpec.a.toByteArray())
    }

    private fun sign(bundle: SignedBundle, kp: Keypair): SignedBundle {
        val body = BundleVerifier.canonicalBody(bundle.copy(sig = ""))
        val engine = EdDSAEngine(MessageDigest.getInstance("SHA-512"))
        engine.initSign(kp.privateKey)
        engine.update(body)
        return bundle.copy(sig = engine.sign().joinToString("") { "%02x".format(it) })
    }

    private fun bundle(seq: Long, issuedAt: Long, notBefore: Long, notAfter: Long, childId: String = "child-aaaa") =
        SignedBundle(
            v = 1,
            child_device_id = childId,
            policy_seq = seq,
            issued_at = issuedAt,
            not_before = notBefore,
            not_after = notAfter,
            nonce = "9f1b3c4d5e6f70819a2b3c4d5e6f7081",
            policy = PolicyDoc(allowlist = listOf("com.example.app")),
            sig = "",
        )

    /** In-memory FloorState with a real §5.1 anchor (advance via the shared pure [FreshnessClock.nextAnchor]). */
    private class FakeFreshnessFloorState(
        val myId: String = "child-aaaa",
        var atRest: Long? = 10L,
        var provisioned: Boolean = true,
        var anchorParent: Long? = null,
        var anchorElapsed: Long? = null,
        var watermark: Long? = null,
    ) : PolicyAdmission.FloorState {
        override fun childDeviceId() = myId
        override fun isProvisioned() = provisioned
        override fun markProvisioned() {}
        override fun atRestFloor() = atRest
        override fun chainFloor(): Long? = null
        override fun effectiveFloor() = atRest
        override fun advanceFloor(policySeq: Long) { if (atRest == null || policySeq > atRest!!) atRest = policySeq }
        override fun freshnessAnchorParentMs() = anchorParent
        override fun freshnessAnchorElapsedMs() = anchorElapsed
        override fun notAfterWatermarkMs() = watermark
        override fun advanceFreshnessAnchor(parentIssuedAtMs: Long, nowElapsedMs: Long, notAfterMs: Long?) {
            val w = FreshnessClock.nextAnchor(
                FreshnessClock.Anchor(anchorParent, anchorElapsed, watermark),
                parentIssuedAtMs, nowElapsedMs, notAfterMs,
            )
            anchorParent = w.parentMs
            anchorElapsed = w.elapsedMs
            watermark = w.watermarkMs
        }
    }

    private class RecordingApplier : PolicyAdmission.Applier {
        val calls = mutableListOf<String>()
        override fun stage(bundle: SignedBundle) { calls += "stage:${bundle.policy_seq}" }
        override fun applyAndFsync(bundle: SignedBundle) { calls += "apply:${bundle.policy_seq}" }
        override fun ack(policySeq: Long) { calls += "ack:$policySeq" }
    }

    private fun admit(b: SignedBundle, state: FakeFreshnessFloorState, applier: RecordingApplier, kp: Keypair, nowElapsedMs: Long) =
        PolicyAdmission.admit(
            BundleVerifier.toWireDocument(sign(b, kp)),
            state, applier, pinParentKey = {}, pinnedParentPubkey = kp.publicKeyRaw, nowElapsedMs = nowElapsedMs,
        )

    @Test
    fun withinWindowAppliesAndReAnchors() {
        val kp = newKeypair()
        // Prior anchor at parent=1_000_000 (elapsed 0); 600_000 ms have since elapsed => now ≈ 1_600_000.
        val state = FakeFreshnessFloorState(atRest = 10L, anchorParent = 1_000_000L, anchorElapsed = 0L, watermark = 1_500_000L)
        val applier = RecordingApplier()
        val b = bundle(seq = 11L, issuedAt = 1_500_000L, notBefore = 1_000_000L, notAfter = 2_000_000L)

        val r = admit(b, state, applier, kp, nowElapsedMs = 600_000L)

        assertTrue(r is PolicyAdmission.Result.Applied, "monotonic_now 1_600_000 ∈ [not_before, not_after] => applied")
        assertEquals(listOf("stage:11", "apply:11", "ack:11"), applier.calls)
        assertEquals(1_500_000L, state.anchorParent, "anchor re-seeded to the applied bundle's issued_at")
        assertEquals(600_000L, state.anchorElapsed, "anchor elapsed re-seeded to now")
        assertEquals(2_000_000L, state.watermark, "watermark rose to the applied not_after")
    }

    @Test
    fun notBeforeInTheFutureDefersClockSkew() {
        val kp = newKeypair()
        val state = FakeFreshnessFloorState(anchorParent = 1_000_000L, anchorElapsed = 0L)
        val applier = RecordingApplier()
        // monotonic_now ≈ 1_600_000; window opens at 1_800_000 (future).
        val b = bundle(seq = 11L, issuedAt = 1_500_000L, notBefore = 1_800_000L, notAfter = 3_000_000L)

        val r = admit(b, state, applier, kp, nowElapsedMs = 600_000L)

        assertTrue(r is PolicyAdmission.Result.Deferred, "not_before in the future => CLOCK_SKEW Defer")
        assertTrue(applier.calls.isEmpty(), "a deferred bundle is not applied")
        assertEquals(10L, state.atRestFloor(), "floor unchanged on defer")
    }

    @Test
    fun notAfterPassedRejectsExpired() {
        val kp = newKeypair()
        val state = FakeFreshnessFloorState(anchorParent = 1_000_000L, anchorElapsed = 0L)
        val applier = RecordingApplier()
        // monotonic_now ≈ 1_600_000; window already closed at 1_500_000.
        val b = bundle(seq = 11L, issuedAt = 1_400_000L, notBefore = 1_000_000L, notAfter = 1_500_000L)

        val r = admit(b, state, applier, kp, nowElapsedMs = 600_000L)

        assertTrue(r is PolicyAdmission.Result.Expired, "not_after in the past => EXPIRED")
        assertTrue(applier.calls.isEmpty(), "an expired bundle is not applied")
    }

    @Test
    fun unusableAnchorAdmitsOnSigAndFloorThenSeedsAnchor() {
        val kp = newKeypair()
        // No prior anchor (post-pairing / post-reboot): the window cannot be evaluated.
        val state = FakeFreshnessFloorState(atRest = 10L, anchorParent = null, anchorElapsed = null, watermark = null)
        val applier = RecordingApplier()
        // not_after is in the PAST relative to wall epoch, but with no anchor the window is skipped.
        val b = bundle(seq = 11L, issuedAt = 1_500_000L, notBefore = 1_000_000L, notAfter = 1_500_001L)

        val r = admit(b, state, applier, kp, nowElapsedMs = 600_000L)

        assertTrue(r is PolicyAdmission.Result.Applied, "Unusable anchor => admit on sig + floor alone, re-anchor on apply")
        assertEquals(1_500_000L, state.anchorParent, "the apply seeds the anchor")
        assertEquals(600_000L, state.anchorElapsed)
    }

    @Test
    fun freshnessIsCheckedAgainstThePriorAnchorNotTheCandidate() {
        val kp = newKeypair()
        // Prior anchor says now ≈ 1_000_000 (no elapsed delta).
        val state = FakeFreshnessFloorState(anchorParent = 1_000_000L, anchorElapsed = 0L)
        val applier = RecordingApplier()
        // The candidate CLAIMS a far-future issued_at whose own window is open at that claimed time.
        // If freshness used the candidate's own issued_at (5_000_000 ∈ [4M,6M]) it would apply; using
        // the PRIOR anchor (now ≈ 1_000_000 < not_before 4_000_000) it must DEFER. Proves D4 ordering.
        val b = bundle(seq = 11L, issuedAt = 5_000_000L, notBefore = 4_000_000L, notAfter = 6_000_000L)

        val r = admit(b, state, applier, kp, nowElapsedMs = 0L)

        assertTrue(r is PolicyAdmission.Result.Deferred, "window must be evaluated against the prior anchor, not the candidate")
        assertTrue(applier.calls.isEmpty())
    }

    @Test
    fun genesisSkipsTheFreshnessWindow() {
        // ADR-041 D3: a never-provisioned (genesis TOFU) accept returns before the freshness gate, so
        // the window is NOT evaluated even with a (here artificially injected) Usable anchor that would
        // mark a non-genesis bundle EXPIRED. Genesis can only ever have an Unusable clock in practice;
        // this pins that genesis never gets rejected/deferred by freshness.
        val kp = newKeypair()
        val state = FakeFreshnessFloorState(
            atRest = null, provisioned = false, // never provisioned ⇒ genesis candidate
            anchorParent = 1_000_000L, anchorElapsed = 0L, // would make a non-genesis bundle look expired
        )
        val applier = RecordingApplier()
        // not_after well before the injected monotonic_now (≈1_600_000) — would be EXPIRED if checked.
        val b = bundle(seq = 1L, issuedAt = 1_000L, notBefore = 0L, notAfter = 2_000L)

        val r = PolicyAdmission.admit(
            BundleVerifier.toWireDocument(sign(b, kp)),
            state, applier, pinParentKey = {}, pinnedParentPubkey = null,
            genesisPubkey = kp.publicKeyRaw, nowElapsedMs = 600_000L,
        )

        assertTrue(r is PolicyAdmission.Result.Applied, "genesis TOFU must apply regardless of the freshness window")
        assertTrue((r as PolicyAdmission.Result.Applied).genesis)
    }
}

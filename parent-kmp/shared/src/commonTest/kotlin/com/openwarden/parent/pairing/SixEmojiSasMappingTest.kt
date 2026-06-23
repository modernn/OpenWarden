package com.openwarden.parent.pairing

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Pure host tests (ADR-038 D6) for the §7.4 SAS table, bit→emoji mapping, IKM assembly, and the
 * [PairingSasStage] Match/Mismatch lifecycle — driven by a fake [SasKdf] so the mapping is
 * deterministic without any crypto. The real-HKDF KATs + the four-key substitution regressions live in
 * `androidUnitTest` (`SixEmojiSasRealKdfTest`).
 */
class SixEmojiSasMappingTest {
    // ---- table invariants (ADR-038 D2) ----------------------------------------------------------

    @Test
    fun tableIs64Distinct() {
        assertEquals(64, SasEmojiTable.EMOJIS.size, "the pinned SAS table MUST be exactly 64 entries")
        assertEquals(64, SasEmojiTable.EMOJIS.toSet().size, "the 64 SAS emojis MUST be distinct")
    }

    // ---- bit→emoji mapping KATs (ADR-038 D3), cross-checked against an independent Python HKDF -----

    private fun emojisFor(indices: List<Int>) = indices.map { SasEmojiTable.EMOJIS[it] }

    @Test
    fun allZeroOutputMapsToIndexZeroSixTimes() {
        assertContentEquals(emojisFor(List(6) { 0 }), SasEmojiTable.lookup(ByteArray(16)))
    }

    @Test
    fun allOnesOutputMapsToIndex63SixTimes() {
        assertContentEquals(emojisFor(List(6) { 63 }), SasEmojiTable.lookup(ByteArray(16) { 0xFF.toByte() }))
    }

    @Test
    fun craftedOutputMapsToFirstSixIndices() {
        // 36-bit big-endian stream 000000 000001 000010 000011 000100 000101 -> indices 0..5.
        val out = byteArrayOf(0x00, 0x10, 0x83.toByte(), 0x10, 0x50) + ByteArray(11)
        assertContentEquals(emojisFor(listOf(0, 1, 2, 3, 4, 5)), SasEmojiTable.lookup(out))
    }

    @Test
    fun lookupRejectsTooFewBits() {
        // 4 bytes = 32 bits < the 36 bits six 6-bit indices need: fail-closed.
        assertFailsWith<IllegalArgumentException> { SasEmojiTable.lookup(ByteArray(4)) }
    }

    // ---- SixEmojiSas IKM assembly (ADR-038 D1) --------------------------------------------------

    /** Records what the core passed to the KDF, and returns a caller-chosen output. */
    private class CapturingKdf(
        private val out: ByteArray,
    ) : SasKdf {
        var salt: ByteArray? = null
        var ikm: ByteArray? = null
        var info: ByteArray? = null
        var length: Int = -1

        override fun hkdfSha256(
            salt: ByteArray,
            ikm: ByteArray,
            info: ByteArray,
            length: Int,
        ): ByteArray {
            this.salt = salt
            this.ikm = ikm
            this.info = info
            this.length = length
            return out.copyOf(length)
        }
    }

    @Test
    fun ikmIsFourKeysInFixedOrderWithCanonicalSaltAndNonceInfo() {
        val pe = ByteArray(32) { 0x11 }
        val px = ByteArray(32) { 0x22 }
        val ce = ByteArray(32) { 0x33 }
        val cx = ByteArray(32) { 0x44 }
        val nonce = ByteArray(32) { 0x55 }
        val kdf = CapturingKdf(ByteArray(16))

        SixEmojiSas(kdf).derive(pe, px, ce, cx, nonce)

        assertContentEquals("openwarden-pair-v1".encodeToByteArray(), kdf.salt, "salt is the canonical label")
        assertContentEquals(pe + px + ce + cx, kdf.ikm, "ikm = parentEd ‖ parentX ‖ childEd ‖ childX (D1 order)")
        assertContentEquals(nonce, kdf.info, "info is the raw provisioning_nonce")
        assertEquals(16, kdf.length, "HKDF output is 16 bytes")
    }

    @Test
    fun wrongLengthKeyFailsClosed() {
        val ok = ByteArray(32)
        val short = ByteArray(31)
        val sas = SixEmojiSas(CapturingKdf(ByteArray(16)))
        assertFailsWith<IllegalArgumentException> { sas.derive(short, ok, ok, ok, ByteArray(32)) }
        assertFailsWith<IllegalArgumentException> { sas.derive(ok, ok, ok, ByteArray(33), ByteArray(32)) }
    }

    // ---- PairingSasStage lifecycle (ADR-038 D4) -------------------------------------------------

    private class CountingBurner : PairingNonceBurner {
        var burns = 0

        override fun burn() {
            burns++
        }
    }

    private fun postWith(
        parentEd: ByteArray,
        parentX: ByteArray,
        childEd: ByteArray,
        childX: ByteArray,
        nonce: ByteArray,
    ): ValidatedPairingPost {
        val qr =
            PairingQrPayload(
                v = 1,
                parentEd25519Pub = Base64Url.encode(parentEd),
                parentX25519Pub = Base64Url.encode(parentX),
                provisioningNonce = Base64Url.encode(nonce),
                transportHints = TransportHints(mdns = "_openwarden._tcp.local"),
            )
        val session = PairingSession(qr.toJson(), nonce, createdAtMs = 0L, ttlMs = 300_000L)
        val response =
            ChildPairingResponse(
                v = 1,
                childEd25519Pub = Base64Url.encode(childEd),
                childX25519Pub = Base64Url.encode(childX),
                childAttestationCertChain = listOf("AA"),
                childBindingSig = "00",
            )
        return ValidatedPairingPost(session, response, childEd, childX)
    }

    @Test
    fun deriveMapsKdfOutputToEmojis() {
        val out = byteArrayOf(0x00, 0x10, 0x83.toByte(), 0x10, 0x50) + ByteArray(11)
        val stage = PairingSasStage(SixEmojiSas(CapturingKdf(out)), CountingBurner())
        val challenge =
            stage.derive(
                postWith(ByteArray(32) { 1 }, ByteArray(32) { 2 }, ByteArray(32) { 3 }, ByteArray(32) { 4 }, ByteArray(32) { 5 }),
            )
        assertContentEquals(listOf(0, 1, 2, 3, 4, 5).map { SasEmojiTable.EMOJIS[it] }, challenge.emojis)
    }

    @Test
    fun mismatchBurnsTheNonceAndPinsNothing() {
        val burner = CountingBurner()
        val stage = PairingSasStage(SixEmojiSas(CapturingKdf(ByteArray(16))), burner)
        val challenge =
            stage.derive(
                postWith(ByteArray(32) { 1 }, ByteArray(32) { 2 }, ByteArray(32) { 3 }, ByteArray(32) { 4 }, ByteArray(32) { 5 }),
            )

        val outcome = stage.confirm(challenge, matched = false)

        assertTrue(outcome is SasOutcome.Mismatch, "a SAS mismatch aborts the pair")
        assertEquals(1, burner.burns, "mismatch burns the single-use nonce exactly once (ADR-036 D4)")
    }

    @Test
    fun matchYieldsChildKeysAndBurnsNothing() {
        val burner = CountingBurner()
        val stage = PairingSasStage(SixEmojiSas(CapturingKdf(ByteArray(16))), burner)
        val childEd = ByteArray(32) { 3 }
        val childX = ByteArray(32) { 4 }
        val challenge =
            stage.derive(postWith(ByteArray(32) { 1 }, ByteArray(32) { 2 }, childEd, childX, ByteArray(32) { 5 }))

        val outcome = stage.confirm(challenge, matched = true)

        assertTrue(outcome is SasOutcome.Match, "a SAS match proceeds toward pinning")
        assertContentEquals(childEd, outcome.childEd25519Pub, "match carries the child Ed25519 key to slice (e)")
        assertContentEquals(childX, outcome.childX25519Pub, "match carries the child X25519 key to slice (e)")
        assertEquals(0, burner.burns, "a match never burns the nonce")
    }
}

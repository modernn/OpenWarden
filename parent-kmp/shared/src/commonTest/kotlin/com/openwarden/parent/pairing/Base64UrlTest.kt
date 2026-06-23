package com.openwarden.parent.pairing

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class Base64UrlTest {
    /** RFC 4648 §10 test vectors (url-safe == standard for these inputs; no padding). */
    @Test
    fun rfc4648Vectors() {
        assertEquals("", Base64Url.encode(byteArrayOf()))
        assertEquals("Zg", Base64Url.encode("f".encodeToByteArray()))
        assertEquals("Zm8", Base64Url.encode("fo".encodeToByteArray()))
        assertEquals("Zm9v", Base64Url.encode("foo".encodeToByteArray()))
        assertEquals("Zm9vYg", Base64Url.encode("foob".encodeToByteArray()))
        assertEquals("Zm9vYmE", Base64Url.encode("fooba".encodeToByteArray()))
        assertEquals("Zm9vYmFy", Base64Url.encode("foobar".encodeToByteArray()))
    }

    /** Bytes mapping to the url-safe sextets 62/63 → '-'/'_' (standard base64 would emit '+'/'/'). */
    @Test
    fun urlSafeAlphabetNoPadding() {
        val out = Base64Url.encode(byteArrayOf(0xFB.toByte(), 0xFF.toByte(), 0xBF.toByte()))
        assertEquals("-_-_", out)
        assertTrue(out.none { it == '+' || it == '/' || it == '=' }, "url-safe, unpadded: $out")
    }

    /** A 32-byte key/nonce encodes to exactly 43 unpadded chars — the §7.1 wire width. */
    @Test
    fun thirtyTwoBytesIs43Chars() {
        assertEquals(43, Base64Url.encode(ByteArray(32) { it.toByte() }).length)
    }

    // --- decode (ADR-036 D3 inbound seam) ---

    /** RFC 4648 §10 vectors decode back to the original bytes. */
    @Test
    fun decodeRfc4648Vectors() {
        assertEquals(0, Base64Url.decode("")!!.size)
        assertEquals("f", Base64Url.decode("Zg")!!.decodeToString())
        assertEquals("fo", Base64Url.decode("Zm8")!!.decodeToString())
        assertEquals("foo", Base64Url.decode("Zm9v")!!.decodeToString())
        assertEquals("foobar", Base64Url.decode("Zm9vYmFy")!!.decodeToString())
    }

    /** Encode→decode is identity across all partial-group lengths and full byte range. */
    @Test
    fun decodeRoundTripsEncode() {
        for (len in 0..40) {
            val data = ByteArray(len) { (it * 7 + 3).toByte() }
            val round = Base64Url.decode(Base64Url.encode(data))
            assertTrue(round != null && round.contentEquals(data), "round-trip failed at len=$len")
        }
    }

    /** The url-safe alphabet ('-'/'_') decodes; padding and the standard '+'/'/' do not. */
    @Test
    fun decodeRejectsPaddingAndStandardAlphabet() {
        assertTrue(Base64Url.decode("-_-_")!!.contentEquals(byteArrayOf(0xFB.toByte(), 0xFF.toByte(), 0xBF.toByte())))
        assertNull(Base64Url.decode("Zm9v="), "trailing '=' padding is malformed")
        assertNull(Base64Url.decode("++//"), "standard-base64 '+'/'/' are not in the url-safe alphabet")
        assertNull(Base64Url.decode("Zm9 v"), "embedded space is not in the alphabet")
        assertNull(Base64Url.decode("A"), "len % 4 == 1 cannot encode any byte")
    }

    // --- decode32 (ADR-025 D6 byte-level pubkey validation) ---

    /** Exactly 32 decoded bytes is accepted; the decoded bytes are returned. */
    @Test
    fun decode32AcceptsExactly32Bytes() {
        val key = ByteArray(32) { (it + 1).toByte() }
        val decoded = Base64Url.decode32(Base64Url.encode(key))
        assertTrue(decoded != null && decoded.contentEquals(key))
    }

    /** 31 or 33 decoded bytes — and the PR #64 "32.25-byte" over-long blob — are rejected. */
    @Test
    fun decode32RejectsWrongLength() {
        assertNull(Base64Url.decode32(Base64Url.encode(ByteArray(31))), "31 bytes")
        assertNull(Base64Url.decode32(Base64Url.encode(ByteArray(33))), "33 bytes")
        // 33 raw bytes ≈ 32.25 keys' worth — the ≥43-char-but-not-32-bytes gap PR #64 had.
        assertNull(Base64Url.decode32(Base64Url.encode(ByteArray(33) { it.toByte() })))
        assertNull(Base64Url.decode32("not*base64url*"), "non-alphabet ⇒ null")
    }
}

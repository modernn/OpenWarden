package com.openwarden.parent.pairing

import kotlin.test.Test
import kotlin.test.assertEquals
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
}

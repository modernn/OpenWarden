package com.openwarden.parent.pairing

/**
 * RFC 4648 §5 base64url encoding, no padding. Pure Kotlin so the §7.1 QR payload assembly stays
 * in `commonMain` (`java.util.Base64` is JVM-only and would force the payload into `androidMain`,
 * out of reach of the host-side `commonTest` suite).
 *
 * Encode assembles outbound payloads (slice (a)). Inbound [decode] + the byte-length validation
 * ([decode32], ADR-025 D6 — decode then assert exactly 32 bytes) land here with the (b) pairing
 * endpoint (ADR-036 D3), where untrusted child input is parsed.
 */
internal object Base64Url {
    private const val ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_"

    /** Reverse lookup: alphabet char -> 6-bit value; -1 for any non-alphabet byte. */
    private val DECODE: IntArray =
        IntArray(128) { -1 }.also { table ->
            for (i in ALPHABET.indices) table[ALPHABET[i].code] = i
        }

    fun encode(data: ByteArray): String {
        if (data.isEmpty()) return ""
        val sb = StringBuilder((data.size + 2) / 3 * 4)
        var i = 0
        while (i + 3 <= data.size) {
            val n =
                ((data[i].toInt() and 0xFF) shl 16) or
                    ((data[i + 1].toInt() and 0xFF) shl 8) or
                    (data[i + 2].toInt() and 0xFF)
            sb.append(ALPHABET[(n ushr 18) and 0x3F])
            sb.append(ALPHABET[(n ushr 12) and 0x3F])
            sb.append(ALPHABET[(n ushr 6) and 0x3F])
            sb.append(ALPHABET[n and 0x3F])
            i += 3
        }
        when (data.size - i) {
            1 -> {
                val n = (data[i].toInt() and 0xFF) shl 16
                sb.append(ALPHABET[(n ushr 18) and 0x3F])
                sb.append(ALPHABET[(n ushr 12) and 0x3F])
            }

            2 -> {
                val n = ((data[i].toInt() and 0xFF) shl 16) or ((data[i + 1].toInt() and 0xFF) shl 8)
                sb.append(ALPHABET[(n ushr 18) and 0x3F])
                sb.append(ALPHABET[(n ushr 12) and 0x3F])
                sb.append(ALPHABET[(n ushr 6) and 0x3F])
            }
        }
        return sb.toString()
    }

    /**
     * Strict RFC 4648 §5 base64url decode, **no padding** (ADR-036 D3). Returns `null` — never throws
     * — on any malformed input: a `=` pad char, any non-alphabet character, or an invalid unpadded
     * length (a `len % 4 == 1` group cannot encode any byte). Fail-closed: the caller treats `null` as
     * "reject this field".
     */
    fun decode(s: String): ByteArray? {
        val n = s.length
        if (n == 0) return ByteArray(0)
        if (n % 4 == 1) return null // no valid base64 group has a single trailing char
        val fullGroups = n / 4
        val rem = n % 4
        val outSize = fullGroups * 3 + if (rem == 0) 0 else rem - 1
        val out = ByteArray(outSize)
        var oi = 0
        var i = 0
        // Full 4-char groups -> 3 bytes.
        repeat(fullGroups) {
            val c0 = sextet(s[i])
            val c1 = sextet(s[i + 1])
            val c2 = sextet(s[i + 2])
            val c3 = sextet(s[i + 3])
            if (c0 < 0 || c1 < 0 || c2 < 0 || c3 < 0) return null
            val v = (c0 shl 18) or (c1 shl 12) or (c2 shl 6) or c3
            out[oi++] = ((v ushr 16) and 0xFF).toByte()
            out[oi++] = ((v ushr 8) and 0xFF).toByte()
            out[oi++] = (v and 0xFF).toByte()
            i += 4
        }
        // Trailing partial group: 2 chars -> 1 byte, 3 chars -> 2 bytes.
        when (rem) {
            2 -> {
                val c0 = sextet(s[i])
                val c1 = sextet(s[i + 1])
                if (c0 < 0 || c1 < 0) return null
                out[oi] = (((c0 shl 18) or (c1 shl 12)) ushr 16 and 0xFF).toByte()
            }

            3 -> {
                val c0 = sextet(s[i])
                val c1 = sextet(s[i + 1])
                val c2 = sextet(s[i + 2])
                if (c0 < 0 || c1 < 0 || c2 < 0) return null
                val v = (c0 shl 18) or (c1 shl 12) or (c2 shl 6)
                out[oi++] = ((v ushr 16) and 0xFF).toByte()
                out[oi] = ((v ushr 8) and 0xFF).toByte()
            }
        }
        return out
    }

    /**
     * ADR-025 D6 / ADR-036 D3: [decode] then require **exactly 32 bytes**. Returns the decoded key or
     * `null` for any malformed, wrong-length, or over-long input — the byte-level check that a mere
     * string-length test (the PR #64 gap) does not provide.
     */
    fun decode32(s: String): ByteArray? = decode(s)?.takeIf { it.size == 32 }

    private fun sextet(c: Char): Int {
        val code = c.code
        return if (code in 0..127) DECODE[code] else -1
    }
}

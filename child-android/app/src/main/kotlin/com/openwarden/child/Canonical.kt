package com.openwarden.child

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * RFC 8785 (JCS) canonicalization — the single signing input for bundles
 * (ADR-015: sign the JCS of the object with its `sig` field removed).
 *
 * PORTED, byte-rule-identical, from
 * `parent-kmp/proto/src/commonMain/kotlin/com/openwarden/proto/Canonical.kt`
 * (branch `feat/proto-replay-floor`). The child cannot import the proto module
 * (composite build deferred / version skew), so the same rules are duplicated
 * here. **If you change one, change both** — signer (parent) and verifier
 * (child) MUST produce byte-identical canonical bytes or every legitimate bundle
 * fails `SIG_FAIL`.
 *
 * Scope (matches PROTOCOL.md §3.1 constraints, intentionally a subset of full JCS):
 * - object members sorted by key, UTF-16 code-unit order
 * - arrays preserve order
 * - **integers only** — JSON numbers with a fraction/exponent are rejected
 *   (PROTOCOL.md §3.1 rule 4: integers only, no floats); integer fields are
 *   additionally bounded to 0..2^53-1 at validation time via [requireJcsSafe]
 *   (ADR-017 JC1)
 * - minimal RFC 8259 string escaping; output is UTF-8 when encoded to bytes
 */
object Canonical {
    /** 2^53 − 1: the largest integer JCS number formatting round-trips exactly (ADR-017 JC1). */
    const val MAX_JCS_SAFE_INTEGER: Long = ReplayFloor.MAX_JCS_SAFE_INTEGER

    /** True iff [value] is within the JCS-safe range 0..2^53-1 (ADR-017 JC1). */
    fun isJcsSafe(value: Long): Boolean = value in 0..MAX_JCS_SAFE_INTEGER

    /** Fail-closed guard: reject any integer/timestamp outside the JCS-safe range before signing/verifying. */
    fun requireJcsSafe(value: Long) {
        require(isJcsSafe(value)) {
            "integer $value outside JCS-safe range 0..2^53-1 (ADR-017)"
        }
    }

    /** Canonical JCS string of [element]. */
    fun canonicalize(element: JsonElement): String = buildString { write(element, this) }

    /** Canonical JCS string of [obj] with the named field removed (the signing input; ADR-015). */
    fun canonicalizeWithout(obj: JsonObject, field: String): String =
        canonicalize(JsonObject(obj.filterKeys { it != field }))

    private fun write(e: JsonElement, sb: StringBuilder) {
        when (e) {
            is JsonObject -> {
                sb.append('{')
                val keys = e.keys.sorted() // Kotlin String order == UTF-16 code-unit order
                for ((i, k) in keys.withIndex()) {
                    if (i > 0) sb.append(',')
                    writeString(k, sb)
                    sb.append(':')
                    write(e.getValue(k), sb)
                }
                sb.append('}')
            }
            is JsonArray -> {
                sb.append('[')
                for ((i, v) in e.withIndex()) {
                    if (i > 0) sb.append(',')
                    write(v, sb)
                }
                sb.append(']')
            }
            is JsonNull -> sb.append("null")
            is JsonPrimitive -> writePrimitive(e, sb)
        }
    }

    private fun writePrimitive(p: JsonPrimitive, sb: StringBuilder) {
        if (p.isString) {
            writeString(p.content, sb)
            return
        }
        when (val c = p.content) {
            "true", "false", "null" -> sb.append(c)
            else -> {
                val l = c.toLongOrNull()
                    ?: throw IllegalArgumentException(
                        "non-integer JSON number '$c' not allowed (JCS subset, PROTOCOL.md §3.1)",
                    )
                sb.append(l.toString())
            }
        }
    }

    private fun writeString(s: String, sb: StringBuilder) {
        sb.append('"')
        for (ch in s) {
            when {
                ch == '"' -> sb.append("\\\"")
                ch == '\\' -> sb.append("\\\\")
                ch.code == 0x08 -> sb.append("\\b")
                ch.code == 0x0C -> sb.append("\\f")
                ch == '\n' -> sb.append("\\n")
                ch == '\r' -> sb.append("\\r")
                ch == '\t' -> sb.append("\\t")
                ch.code < 0x20 -> sb.append("\\u").append(ch.code.toString(16).padStart(4, '0'))
                else -> sb.append(ch)
            }
        }
        sb.append('"')
    }
}

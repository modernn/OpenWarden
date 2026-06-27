package com.openwarden.proto

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class CanonicalTest {
    private fun canon(json: String): String = Canonical.canonicalize(Json.parseToJsonElement(json))

    @Test
    fun sortsObjectKeysAndStripsWhitespace() {
        assertEquals(
            """{"a":2,"b":1}""",
            canon("""  { "b" : 1 , "a" : 2 }  """),
        )
    }

    @Test
    fun sortsNestedKeysButPreservesArrayOrder() {
        assertEquals(
            """{"o":{"x":1,"y":2},"z":[3,1,2]}""",
            canon("""{"z":[3,1,2],"o":{"y":2,"x":1}}"""),
        )
    }

    @Test
    fun canonicalizeWithoutDropsSigField() {
        val obj = Json.parseToJsonElement("""{"sig":"AA","policySeq":5,"a":1}""") as JsonObject
        assertEquals("""{"a":1,"policySeq":5}""", Canonical.canonicalizeWithout(obj, "sig"))
    }

    @Test
    fun rejectsNonIntegerNumbers() {
        assertFailsWith<IllegalArgumentException> { canon("""{"x":1.5}""") }
    }

    @Test
    fun rejectsIntegerAboveJcsBound() {
        assertFailsWith<IllegalArgumentException> {
            Canonical.requireJcsSafe(Canonical.MAX_JCS_SAFE_INTEGER + 1)
        }
    }

    @Test
    fun acceptsZeroAndBoundary() {
        Canonical.requireJcsSafe(0)
        Canonical.requireJcsSafe(Canonical.MAX_JCS_SAFE_INTEGER)
    }

    @Test
    fun rejectsNegative() {
        assertFailsWith<IllegalArgumentException> { Canonical.requireJcsSafe(-1) }
    }

    @Test
    fun versionIsOneAndSelfCompatible() {
        assertEquals(1, POLICY_BUNDLE_FORMAT_VERSION)
        assertTrue(Versioning.isCompatible(POLICY_BUNDLE_FORMAT_VERSION))
    }

    // ----- CROSS-IMPLEMENTATION INTEROP VECTOR (PROTOCOL.md §2) -----
    //
    // This is the assertion that would have caught the PR #50 SIG_FAIL crux: the
    // parent signer (proto PolicyBundle) and the child verifier (SignedBundle) used
    // DIFFERENT field sets, so a real parent-signed bundle never verified at the child.
    //
    // The fixed §2 bundle below is the SAME logical bundle the child pins in
    // PolicyAdmissionTest.interopGoldenCanonicalBytesMatchProto(). Both sides assert
    // their canonicalizer produces EXACTLY this hex. If the two diverge, this and the
    // child test disagree with the shared constant — the canonicalizers don't agree on
    // §2, and the bug is caught at unit-test time, not at runtime SIG_FAIL.
    //
    // body := JCS-canonical of the §2 bundle with "sig" removed.
    private val interopBundle =
        PolicyBundle(
            v = 1,
            policySeq = 5,
            childDeviceId = "dev-1",
            issuedAt = 50,
            notBefore = 100,
            notAfter = 200,
            nonce = "9f1b3c4d5e6f70819a2b3c4d5e6f7081",
            policy = Policy(allowlist = listOf("com.a")),
            sig = null,
        )

    @Test
    fun interopGoldenCanonicalBytesAreStable() {
        // GOLDEN. Must stay byte-identical to the child's pinned hex (cross-impl parity).
        val canonical = SigningInput.forBundle(interopBundle).decodeToString()
        assertEquals(INTEROP_GOLDEN_CANONICAL, canonical)
        assertEquals(INTEROP_GOLDEN_HEX, canonical.encodeToByteArray().toHex())
    }

    private fun ByteArray.toHex(): String = joinToString("") { (it.toInt() and 0xFF).toString(16).padStart(2, '0') }

    companion object {
        /** The exact §2 canonical body (sig-stripped) for [interopBundle]. */
        const val INTEROP_GOLDEN_CANONICAL: String =
            """{"child_device_id":"dev-1","issued_at":50,"nonce":"9f1b3c4d5e6f70819a2b3c4d5e6f7081",""" +
                """"not_after":200,"not_before":100,""" +
                """"policy":{"allowlist":["com.a"],"blocklist":[],"restrictions":[],"windows":[]},""" +
                """"policy_seq":5,"v":1}"""

        /** UTF-8 hex of [INTEROP_GOLDEN_CANONICAL]; the child test pins this same string. */
        const val INTEROP_GOLDEN_HEX: String =
            "7b226368696c645f6465766963655f6964223a226465762d31222c226973737565645f6174223a35302c" +
                "226e6f6e6365223a223966316233633464356536663730383139613262336334643565366637303831" +
                "222c226e6f745f6166746572223a3230302c226e6f745f6265666f7265223a3130302c22706f6c6963" +
                "79223a7b22616c6c6f776c697374223a5b22636f6d2e61225d2c22626c6f636b6c697374223a5b5d2c" +
                "227265737472696374696f6e73223a5b5d2c2277696e646f7773223a5b5d7d2c22706f6c6963795f73" +
                "6571223a352c2276223a317d"
    }
}

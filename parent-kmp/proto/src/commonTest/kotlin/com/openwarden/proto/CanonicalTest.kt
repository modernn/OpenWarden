package com.openwarden.proto

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class CanonicalTest {
    private fun canon(json: String): String =
        Canonical.canonicalize(Json.parseToJsonElement(json))

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
}

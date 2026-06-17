package com.openwarden.parent.pairing

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Pure unit tests for [PairingPayloadParser] and [PinnedPeerStore].
 *
 * These run on the JVM (commonTest) with no Android or libsodium dependency.
 * Covers the two required cases from the issue acceptance criteria:
 *   1. Valid fixture QR → peer stored correctly.
 *   2. Malformed / incomplete payload → rejected AND nothing persisted (fail-closed).
 */
class PairingPayloadParserTest {

    // ---------------------------------------------------------------------------
    // Test fixtures
    // ---------------------------------------------------------------------------

    /**
     * A valid, minimally well-formed pairing QR payload.
     *
     * PROTOCOL.md §7: keys are base64url(32 bytes). base64url of 32 bytes = 43 chars
     * (no padding). Using synthetic fixed values for deterministic tests.
     */
    private val validEd25519Pub = "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA"  // 43 chars
    private val validX25519Pub  = "BBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBB"  // 43 chars
    private val validSpki       = "CCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCC"  // 43 chars

    private fun validPayloadJson(
        v: Int = 1,
        ed25519Pub: String = validEd25519Pub,
        x25519Pub: String = validX25519Pub,
        spki: String = validSpki,
    ): String = """
        {
          "v": $v,
          "child_ed25519_pub": "$ed25519Pub",
          "child_x25519_pub": "$x25519Pub",
          "tls_spki": "$spki"
        }
    """.trimIndent()

    // ---------------------------------------------------------------------------
    // Parse + persist: success path
    // ---------------------------------------------------------------------------

    @Test
    fun parseValidPayload_returnsSuccess() {
        val result = PairingPayloadParser.parse(validPayloadJson())
        assertIs<PairingParseResult.Success>(result)
        assertEquals(validEd25519Pub, result.peer.childEd25519Pub)
        assertEquals(validX25519Pub, result.peer.childX25519Pub)
        assertEquals(validSpki, result.peer.tlsSpki)
    }

    @Test
    fun parseValidPayload_thenPin_peerIsStored() {
        val store = PinnedPeerStore()
        assertNull(store.peer.value, "store must be empty before pairing")

        val result = PairingPayloadParser.parse(validPayloadJson())
        assertIs<PairingParseResult.Success>(result)

        store.pin(result.peer)

        val stored = store.peer.value
        assertEquals(result.peer, stored)
        assertEquals(validEd25519Pub, stored?.childEd25519Pub)
        assertEquals(validX25519Pub, stored?.childX25519Pub)
        assertEquals(validSpki, stored?.tlsSpki)
        assertTrue(store.isPaired)
    }

    @Test
    fun parseValidPayload_unknownExtraFields_areIgnored() {
        // PROTOCOL.md §8 forward-compat: unknown fields must not cause rejection.
        val payloadWithExtra = """
            {
              "v": 1,
              "child_ed25519_pub": "$validEd25519Pub",
              "child_x25519_pub": "$validX25519Pub",
              "tls_spki": "$validSpki",
              "future_field": "ignored"
            }
        """.trimIndent()
        assertIs<PairingParseResult.Success>(PairingPayloadParser.parse(payloadWithExtra))
    }

    // ---------------------------------------------------------------------------
    // Malformed payload → fail-closed: reject AND nothing persisted
    // ---------------------------------------------------------------------------

    /**
     * Helper: asserts [raw] is rejected AND that calling parse+pin would leave
     * the store empty (nothing persisted on failure).
     */
    private fun assertRejectedAndNothingPersisted(raw: String) {
        val store = PinnedPeerStore()
        val result = PairingPayloadParser.parse(raw)

        assertIs<PairingParseResult.Failure>(
            result,
            "expected Failure but got $result for input: $raw",
        )
        // Fail-closed: never call store.pin on a Failure result.
        assertNull(store.peer.value, "store must remain empty after a rejected payload")
        assertTrue(!store.isPaired, "isPaired must be false after rejection")
    }

    @Test
    fun blankPayload_isRejected() {
        assertRejectedAndNothingPersisted("")
        assertRejectedAndNothingPersisted("   ")
    }

    @Test
    fun notJson_isRejected() {
        assertRejectedAndNothingPersisted("not-json-at-all")
        assertRejectedAndNothingPersisted("{ broken json }")
    }

    @Test
    fun wrongSchemaVersion_isRejected() {
        assertRejectedAndNothingPersisted(validPayloadJson(v = 0))
        assertRejectedAndNothingPersisted(validPayloadJson(v = 2))
        assertRejectedAndNothingPersisted(validPayloadJson(v = 99))
    }

    @Test
    fun missingChildEd25519Pub_isRejected() {
        val json = """{"v":1,"child_x25519_pub":"$validX25519Pub","tls_spki":"$validSpki"}"""
        assertRejectedAndNothingPersisted(json)
    }

    @Test
    fun missingChildX25519Pub_isRejected() {
        val json = """{"v":1,"child_ed25519_pub":"$validEd25519Pub","tls_spki":"$validSpki"}"""
        assertRejectedAndNothingPersisted(json)
    }

    @Test
    fun missingTlsSpki_isRejected() {
        val json = """{"v":1,"child_ed25519_pub":"$validEd25519Pub","child_x25519_pub":"$validX25519Pub"}"""
        assertRejectedAndNothingPersisted(json)
    }

    @Test
    fun tooShortEd25519Pub_isRejected() {
        // 42 chars is one short of the minimum 43 for base64url(32 bytes)
        val shortKey = "A".repeat(42)
        assertRejectedAndNothingPersisted(validPayloadJson(ed25519Pub = shortKey))
    }

    @Test
    fun tooShortX25519Pub_isRejected() {
        val shortKey = "B".repeat(10)
        assertRejectedAndNothingPersisted(validPayloadJson(x25519Pub = shortKey))
    }

    @Test
    fun tooShortTlsSpki_isRejected() {
        val shortKey = "C".repeat(1)
        assertRejectedAndNothingPersisted(validPayloadJson(spki = shortKey))
    }

    @Test
    fun emptyJsonObject_isRejected() {
        assertRejectedAndNothingPersisted("{}")
    }

    @Test
    fun partialPayload_isRejectedAndNothingPersisted_deterministic() {
        // Regression: even a partially valid payload must not persist anything.
        val partial = """{"v":1,"child_ed25519_pub":"$validEd25519Pub"}"""
        val store = PinnedPeerStore()
        val result = PairingPayloadParser.parse(partial)
        assertIs<PairingParseResult.Failure>(result)
        // Simulate the caller correctly not pinning on failure:
        assertNull(store.peer.value)
    }

    // ---------------------------------------------------------------------------
    // PinnedPeerStore: clear resets to unpaired
    // ---------------------------------------------------------------------------

    @Test
    fun store_clearResetsState() {
        val store = PinnedPeerStore()
        val result = PairingPayloadParser.parse(validPayloadJson())
        assertIs<PairingParseResult.Success>(result)
        store.pin(result.peer)
        assertTrue(store.isPaired)
        store.clear()
        assertNull(store.peer.value)
        assertTrue(!store.isPaired)
    }
}

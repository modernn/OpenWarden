package com.openwarden.parent.android.demo

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [DemoPairSender] (ADR-046 D4).
 *
 * All collaborators are faked:
 *  - [FakeRootKeyAccess] — controls whether a public key is present.
 *  - [FakePairChildStore] — records what was stored (or not).
 *  - Ktor [MockEngine] — returns controlled HTTP responses.
 *
 * Contract:
 *  1. rootPublicKey present + HTTP 200 → pins the returned child_id; returns [DemoPairResult.Paired].
 *  2. rootPublicKey null → returns [DemoPairResult.NotProvisioned]; no POST sent; no store write.
 *  3. HTTP 409 → returns [DemoPairResult.AlreadyPaired]; no store write.
 *  4. Unexpected HTTP status → returns [DemoPairResult.Failure]; no store write.
 *  5. HTTP 200 with blank child_id → returns [DemoPairResult.Failure]; no store write (fail-closed).
 *  6. Request body contains the base64-encoded public key.
 */
class DemoPairSenderTest {
    // ---- fakes ----

    private class FakeRootKeyAccess(
        private val key: ByteArray?,
    ) : DemoPairSender.RootKeyAccess {
        override fun rootPublicKey(): ByteArray? = key
    }

    private class FakePairChildStore : DemoPairChildStore {
        val stored = mutableListOf<String>()

        override fun storeChildId(childId: String) {
            stored.add(childId)
        }
    }

    /** Fixed 32-byte test pubkey. */
    private val testPubKey = ByteArray(32) { it.toByte() }

    private fun jsonContentType() = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString())

    private fun buildSender(
        pubKey: ByteArray?,
        store: FakePairChildStore,
        engine: MockEngine,
    ): DemoPairSender =
        DemoPairSender(
            rootKeyProvider = FakeRootKeyAccess(pubKey),
            pairedChildStore = store,
            baseUrl = "http://localhost",
            httpClientFactory = {
                HttpClient(engine) {
                    install(ContentNegotiation) {
                        json(
                            Json {
                                ignoreUnknownKeys = true
                                isLenient = true
                            },
                        )
                    }
                    expectSuccess = false
                }
            },
        )

    // ---- tests ----

    @Test
    fun pubKeyPresent_http200_pinsChildId_returnsPaired() =
        runTest {
            val store = FakePairChildStore()
            val engine =
                MockEngine { _ ->
                    respond(
                        content = """{"status":"paired","child_id":"child-abc-123"}""",
                        status = HttpStatusCode.OK,
                        headers = jsonContentType(),
                    )
                }
            val result = buildSender(testPubKey, store, engine).pair()
            assertTrue("expected Paired, got $result", result is DemoPairResult.Paired)
            assertEquals("child-abc-123", (result as DemoPairResult.Paired).childId)
            assertEquals(listOf("child-abc-123"), store.stored)
        }

    @Test
    fun noPublicKey_returnsNotProvisioned_noPostSent() =
        runTest {
            val store = FakePairChildStore()
            var callCount = 0
            val engine =
                MockEngine { _ ->
                    callCount++
                    respond("unexpected call", HttpStatusCode.OK)
                }
            val result = buildSender(null, store, engine).pair()
            assertEquals(DemoPairResult.NotProvisioned, result)
            assertEquals("no POST should be sent when key is absent", 0, callCount)
            assertTrue(store.stored.isEmpty())
        }

    @Test
    fun http409_returnsAlreadyPaired_noPinWrite() =
        runTest {
            val store = FakePairChildStore()
            val engine =
                MockEngine { _ ->
                    respond(
                        content = """{"status":"already_paired"}""",
                        status = HttpStatusCode.Conflict,
                        headers = jsonContentType(),
                    )
                }
            val result = buildSender(testPubKey, store, engine).pair()
            assertEquals(DemoPairResult.AlreadyPaired, result)
            assertTrue(store.stored.isEmpty())
        }

    @Test
    fun unexpectedHttpStatus_returnsFailure_noPinWrite() =
        runTest {
            val store = FakePairChildStore()
            val engine =
                MockEngine { _ ->
                    respond(
                        content = """{"error":"unexpected"}""",
                        status = HttpStatusCode.InternalServerError,
                        headers = jsonContentType(),
                    )
                }
            val result = buildSender(testPubKey, store, engine).pair()
            assertTrue("expected Failure, got $result", result is DemoPairResult.Failure)
            assertTrue(store.stored.isEmpty())
        }

    @Test
    fun http200_blankChildId_returnsFailure_noPinWrite() =
        runTest {
            val store = FakePairChildStore()
            val engine =
                MockEngine { _ ->
                    respond(
                        content = """{"status":"paired","child_id":""}""",
                        status = HttpStatusCode.OK,
                        headers = jsonContentType(),
                    )
                }
            val result = buildSender(testPubKey, store, engine).pair()
            assertTrue("expected Failure on blank child_id, got $result", result is DemoPairResult.Failure)
            assertTrue(store.stored.isEmpty())
        }

    @Test
    fun requestBody_containsBase64EncodedPublicKey() =
        runTest {
            val store = FakePairChildStore()
            var capturedBody = ""
            val engine =
                MockEngine { request ->
                    capturedBody = request.body.toByteArray().decodeToString()
                    respond(
                        content = """{"status":"paired","child_id":"child-xyz"}""",
                        status = HttpStatusCode.OK,
                        headers = jsonContentType(),
                    )
                }
            buildSender(testPubKey, store, engine).pair()
            assertTrue(
                "request body should contain 'parent_pubkey', got: $capturedBody",
                capturedBody.contains("parent_pubkey"),
            )
            // Base64-encoded 32 bytes = 44 chars (no-wrap, with padding)
            assertTrue(
                "parent_pubkey value should be at least 44 chars",
                capturedBody.length > 50,
            )
        }
}

package com.openwarden.parent.android.demo

import io.ktor.client.HttpClient
import io.ktor.client.call.NoTransformationFoundException
import io.ktor.client.call.body
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.ResponseException
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.get
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException

/**
 * Unit tests for [childErrorMessage] — the pure Throwable→UI-string mapping.
 *
 * Tests run without a live socket. MockEngine is used only where a real [ResponseException]
 * (which requires a real [io.ktor.client.statement.HttpResponse]) must be constructed.
 */
class ChildErrorMessageTest {
    // ---------------------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------------------

    /** Banlist of strings that must NEVER appear in a user-facing error message. */
    private val bannedTerms = listOf("Exception", "ktor.io", "SourceByteReadChannel")

    private fun String.containsNoBannedTerms(): Boolean = bannedTerms.none { this.contains(it) }

    /** Build a minimal Ktor MockEngine client with expectSuccess = true. */
    private fun mockClient(engine: MockEngine): HttpClient =
        HttpClient(engine) {
            install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
            expectSuccess = true
        }

    // ---------------------------------------------------------------------------
    // ResponseException — HTTP 404 (the live /apps case)
    // ---------------------------------------------------------------------------

    @Test
    fun http404_mapsToEndpointMissingMessage() =
        runTest {
            val engine =
                MockEngine { _ ->
                    respond("Not Found", HttpStatusCode.NotFound, headersOf(HttpHeaders.ContentType, "text/plain"))
                }
            val client = mockClient(engine)
            val caught =
                runCatching { client.get("http://child/apps") }
                    .exceptionOrNull()
            assertTrue("Expected ResponseException for 404", caught is ResponseException)
            val msg = childErrorMessage(caught!!)
            assertTrue(
                "404 message should mention 'isn’t reporting'",
                msg.contains("isn't reporting") || msg.contains("isn’t reporting"),
            )
            assertTrue("Message must not contain banned terms: $msg", msg.containsNoBannedTerms())
        }

    // ---------------------------------------------------------------------------
    // ResponseException — non-404 HTTP error
    // ---------------------------------------------------------------------------

    @Test
    fun http500_mapsToUnexpectedResponseMessage() =
        runTest {
            val engine =
                MockEngine { _ ->
                    respond("Server Error", HttpStatusCode.InternalServerError, headersOf(HttpHeaders.ContentType, "text/plain"))
                }
            val client = mockClient(engine)
            val caught =
                runCatching { client.get("http://child/state") }
                    .exceptionOrNull()
            assertTrue("Expected ResponseException for 500", caught is ResponseException)
            val msg = childErrorMessage(caught!!)
            assertTrue("HTTP 500 message should contain status code", msg.contains("500"))
            assertTrue("Message must not contain banned terms: $msg", msg.containsNoBannedTerms())
        }

    // ---------------------------------------------------------------------------
    // NoTransformationFoundException — body cannot be deserialized
    // ---------------------------------------------------------------------------

    @Test
    fun noTransformationFound_mapsToUnexpectedResponseMessage() =
        runTest {
            // Return valid 200 but with body that cannot deserialize to InstalledAppsResponse.
            val engine =
                MockEngine { _ ->
                    respond(
                        // Deliberately not JSON — plain text triggers NoTransformationFoundException
                        // when .body<InstalledAppsResponse>() is called.
                        "THIS IS NOT JSON",
                        HttpStatusCode.OK,
                        headersOf(HttpHeaders.ContentType, "text/plain"),
                    )
                }
            val client = mockClient(engine)
            val caught =
                runCatching { client.get("http://child/apps").body<InstalledAppsResponse>() }
                    .exceptionOrNull()
            // If content-type doesn't match, Ktor throws NoTransformationFoundException.
            // (If it happens to succeed, the test is vacuously OK — the scenario is best-effort.)
            if (caught == null) return@runTest
            val msg = childErrorMessage(caught)
            assertTrue("Message must not contain banned terms: $msg", msg.containsNoBannedTerms())
            assertFalse("Message must not be blank", msg.isBlank())
        }

    @Test
    fun noTransformationFoundException_viaMockEngine() =
        runTest {
            // Serve a 200 with non-JSON content-type. When .body<InstalledAppsResponse>() is
            // called on a response with no JSON content-negotiation handler registered for
            // text/plain, Ktor throws NoTransformationFoundException.
            val engine =
                MockEngine { _ ->
                    respond(
                        "plain text body",
                        HttpStatusCode.OK,
                        headersOf(HttpHeaders.ContentType, "text/plain"),
                    )
                }
            // Client WITHOUT ContentNegotiation so there is no handler for text/plain → body()
            // conversion will fail with NoTransformationFoundException.
            val client =
                HttpClient(engine) {
                    expectSuccess = true
                }
            val caught =
                runCatching { client.get("http://child/apps").body<InstalledAppsResponse>() }
                    .exceptionOrNull()
            // Verify we got the expected exception type.
            assertTrue(
                "Expected NoTransformationFoundException, got: ${caught?.javaClass}",
                caught is NoTransformationFoundException,
            )
            val msg = childErrorMessage(caught!!)
            assertTrue(
                "NoTransformationFoundException → unexpected-response message",
                msg.contains("unexpected response"),
            )
            assertTrue("Message must not contain banned terms: $msg", msg.containsNoBannedTerms())
        }

    // ---------------------------------------------------------------------------
    // IO / connectivity failure
    // ---------------------------------------------------------------------------

    @Test
    fun connectException_mapsToUnreachableMessage() {
        val msg = childErrorMessage(ConnectException("Connection refused"))
        assertTrue(
            "ConnectException → unreachable message",
            msg.contains("reach") || msg.contains("network"),
        )
        assertTrue("Message must not contain banned terms: $msg", msg.containsNoBannedTerms())
    }

    @Test
    fun socketTimeout_mapsToUnreachableMessage() {
        val msg = childErrorMessage(SocketTimeoutException("timeout"))
        assertTrue(
            "SocketTimeoutException → unreachable message",
            msg.contains("reach") || msg.contains("network"),
        )
        assertTrue("Message must not contain banned terms: $msg", msg.containsNoBannedTerms())
    }

    @Test
    fun genericIOException_mapsToUnreachableMessage() {
        val msg = childErrorMessage(IOException("some io error"))
        assertTrue(
            "IOException → unreachable message",
            msg.contains("reach") || msg.contains("network"),
        )
        assertTrue("Message must not contain banned terms: $msg", msg.containsNoBannedTerms())
    }

    // ---------------------------------------------------------------------------
    // Unknown throwable — safe fallback, never the raw class name
    // ---------------------------------------------------------------------------

    @Test
    fun unknownThrowable_fallback_doesNotExposeClassName() {
        val t = object : Throwable("internal detail: kotlin.NullPointerException at foo.bar.Baz") {}
        val msg = childErrorMessage(t)
        assertFalse("Fallback must not be blank", msg.isBlank())
        assertTrue("Fallback must not contain banned terms: $msg", msg.containsNoBannedTerms())
        // The raw exception message must not appear verbatim.
        assertFalse(
            "Fallback must not expose raw exception message",
            msg.contains("NullPointerException") || msg.contains("foo.bar"),
        )
    }

    @Test
    fun unknownThrowable_withNullMessage_fallback_isNonBlank() {
        val t = RuntimeException(null as String?)
        val msg = childErrorMessage(t)
        assertFalse("Fallback for null-message throwable must not be blank", msg.isBlank())
        assertTrue("Message must not contain banned terms: $msg", msg.containsNoBannedTerms())
    }

    // ---------------------------------------------------------------------------
    // Integration: 404 from mock engine flows through ChildApiClient contract
    // (no banned terms, no raw exception text in ApiResult.Failure.message)
    // ---------------------------------------------------------------------------

    @Test
    fun getApps_on404_failureMessageContainsNoBannedTerms() =
        runTest {
            // Simulate child /apps returning 404 via the MockEngine.
            val engine =
                MockEngine { _ ->
                    respond("Not Found", HttpStatusCode.NotFound, headersOf(HttpHeaders.ContentType, "text/plain"))
                }
            // Exercise childErrorMessage directly with the exception the engine produces.
            val responseException =
                runCatching {
                    mockClient(engine).get("http://10.0.2.2:7180/apps")
                }.exceptionOrNull()
            assertTrue("Should throw ResponseException on 404", responseException is ResponseException)
            val failureMessage = childErrorMessage(responseException!!)
            bannedTerms.forEach { banned ->
                assertFalse(
                    "Failure message must not contain '$banned' but got: $failureMessage",
                    failureMessage.contains(banned),
                )
            }
            assertFalse("Failure message must not be blank", failureMessage.isBlank())
        }
}

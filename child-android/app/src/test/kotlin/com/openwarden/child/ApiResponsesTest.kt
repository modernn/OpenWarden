package com.openwarden.child

import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Regression guard for #114: the child read endpoints returned HTTP 500 on-device because
 * `call.respond(mapOf<String, Any>(...))` has no kotlinx serializer at runtime. These tests
 * encode the replacement `@Serializable` DTOs through the SAME Json config the server installs
 * and assert the exact wire keys + null-omission the parent client depends on.
 *
 * Pure JVM (no Robolectric) — kotlinx only. The live endpoint 200 is proven separately by the
 * on-device E2E.
 */
class ApiResponsesTest {

    // Mirror of ApiServer's ContentNegotiation Json config.
    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }

    @Test
    fun `StateResponse serializes with exact snake_case wire keys and mixed types`() {
        val out = json.encodeToString(
            StateResponse(
                version = "0.1.0-dev",
                policyVersion = "none",
                policyNotAfter = "n/a",
                isLocked = false,
                paired = true,
                reportedAt = 1_700_000_000_000L,
            ),
        )
        // The very thing mapOf<String, Any> could not do: encode String + Boolean + Long together.
        assertTrue(out.contains("\"version\":\"0.1.0-dev\""), out)
        assertTrue(out.contains("\"policy_version\":\"none\""), out)
        assertTrue(out.contains("\"policy_not_after\":\"n/a\""), out)
        assertTrue(out.contains("\"is_locked\":false"), out)
        assertTrue(out.contains("\"paired\":true"), out)
        assertTrue(out.contains("\"reported_at\":1700000000000"), out)
    }

    @Test
    fun `UsageResponse on-device emits only source, window_hours, per_app (notices omitted)`() {
        val out = json.encodeToString(
            UsageResponse(
                source = "on-device",
                windowHours = 24,
                perApp = listOf(UsageStatsHelper.AppUsageEntry("com.x", "X", 12)),
            ),
        )
        assertTrue(out.contains("\"source\":\"on-device\""), out)
        assertTrue(out.contains("\"window_hours\":24"), out)
        assertTrue(out.contains("\"per_app\":["), out)
        assertTrue(out.contains("\"packageName\":\"com.x\""), out)
        assertTrue(out.contains("\"foregroundMinutes\":12"), out)
        // explicitNulls = false: null notices/error must NOT appear.
        assertFalse(out.contains("demo_notice"), out)
        assertFalse(out.contains("\"notice\""), out)
        assertFalse(out.contains("\"error\""), out)
    }

    @Test
    fun `UsageResponse demo-fallback includes demo_notice`() {
        val out = json.encodeToString(
            UsageResponse(source = "demo-fallback", windowHours = 24, perApp = emptyList(), demoNotice = "x"),
        )
        assertTrue(out.contains("\"demo_notice\":\"x\""), out)
        assertFalse(out.contains("\"notice\""), out)
    }

    @Test
    fun `UsageResponse error carries error key`() {
        val out = json.encodeToString(
            UsageResponse(source = "error", windowHours = 24, perApp = emptyList(), error = "boom"),
        )
        assertTrue(out.contains("\"source\":\"error\""), out)
        assertTrue(out.contains("\"error\":\"boom\""), out)
    }

    @Test
    fun `AppUsageEntry with null label omits the label key (parent defaults it)`() {
        val out = json.encodeToString(
            UsageResponse(
                source = "on-device",
                windowHours = 24,
                perApp = listOf(UsageStatsHelper.AppUsageEntry("com.x", null, 5)),
            ),
        )
        assertFalse(out.contains("\"label\""), out)
    }

    @Test
    fun `StateResponse round-trips back to an equal value`() {
        val original = StateResponse(
            "0.1.0-dev", "1700000000000", "1700000086400000",
            isLocked = true, paired = false, reportedAt = 1_700_000_100_000L,
        )
        val decoded = json.decodeFromString<StateResponse>(json.encodeToString(original))
        assertEquals(original, decoded)
    }
}

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
    private val json =
        Json {
            ignoreUnknownKeys = true
            explicitNulls = false
        }

    @Test
    fun `StateResponse serializes with exact snake_case wire keys and mixed types`() {
        val out =
            json.encodeToString(
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
        val out =
            json.encodeToString(
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
        val out =
            json.encodeToString(
                UsageResponse(source = "demo-fallback", windowHours = 24, perApp = emptyList(), demoNotice = "x"),
            )
        assertTrue(out.contains("\"demo_notice\":\"x\""), out)
        assertFalse(out.contains("\"notice\""), out)
    }

    @Test
    fun `UsageResponse error carries source, error, and the normalized window_hours + empty per_app`() {
        // The error body (HTTP 500) intentionally normalizes to the same envelope as the other
        // source states — source + window_hours + per_app + error. (The old mapOf error omitted
        // window_hours; the DTO adds it. The parent tolerates it via ignoreUnknownKeys + defaults.)
        val out =
            json.encodeToString(
                UsageResponse(source = "error", windowHours = 24, perApp = emptyList(), error = "boom"),
            )
        assertTrue(out.contains("\"source\":\"error\""), out)
        assertTrue(out.contains("\"error\":\"boom\""), out)
        assertTrue(out.contains("\"window_hours\":24"), out)
        assertTrue(out.contains("\"per_app\":[]"), out)
        // Never fabricated notices on the error path.
        assertFalse(out.contains("demo_notice"), out)
        assertFalse(out.contains("\"notice\""), out)
    }

    @Test
    fun `AppUsageEntry with null label omits the label key (parent defaults it)`() {
        val out =
            json.encodeToString(
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
        val original =
            StateResponse(
                "0.1.0-dev",
                "1700000000000",
                "1700000086400000",
                isLocked = true,
                paired = false,
                reportedAt = 1_700_000_100_000L,
            )
        val decoded = json.decodeFromString<StateResponse>(json.encodeToString(original))
        assertEquals(original, decoded)
    }

    // ---- /apps endpoint DTO tests ----

    @Test
    fun `AppEntry serializes with camelCase packageName and label wire keys`() {
        val out = json.encodeToString(AppEntry(packageName = "com.example.game", label = "My Game"))
        assertTrue(out.contains("\"packageName\":\"com.example.game\""), out)
        assertTrue(out.contains("\"label\":\"My Game\""), out)
    }

    @Test
    fun `AppsResponse serializes the apps envelope with a list of entries`() {
        val out =
            json.encodeToString(
                AppsResponse(
                    apps =
                        listOf(
                            AppEntry(packageName = "com.a", label = "App A"),
                            AppEntry(packageName = "com.b", label = "App B"),
                        ),
                ),
            )
        assertTrue(out.contains("\"apps\":["), out)
        assertTrue(out.contains("\"packageName\":\"com.a\""), out)
        assertTrue(out.contains("\"label\":\"App A\""), out)
        assertTrue(out.contains("\"packageName\":\"com.b\""), out)
    }

    @Test
    fun `AppsResponse empty list serializes to apps array empty`() {
        val out = json.encodeToString(AppsResponse(apps = emptyList()))
        assertTrue(out.contains("\"apps\":[]"), out)
    }

    @Test
    fun `AppsResponse round-trips through encode+decode`() {
        val original =
            AppsResponse(
                apps =
                    listOf(
                        AppEntry("com.example.one", "One"),
                        AppEntry("com.example.two", "Two"),
                    ),
            )
        val decoded = json.decodeFromString<AppsResponse>(json.encodeToString(original))
        assertEquals(original, decoded)
    }

    @Test
    fun `InstalledAppsHelper mapToEntries filters system and self packages`() {
        val raw =
            listOf(
                Triple("com.example.user", 0, "User App"), // user — include
                Triple("android", android.content.pm.ApplicationInfo.FLAG_SYSTEM, "Android"), // system — exclude
                Triple("com.openwarden.child", 0, "Self"), // self — exclude
                Triple("com.example.another", 0, null), // user, no label — fallback to pkg
            )
        val entries = InstalledAppsHelper.mapToEntries(raw, selfPackage = "com.openwarden.child")
        assertEquals(2, entries.size)
        val pkgs = entries.map { it.packageName }
        assertTrue(pkgs.contains("com.example.user"), "expected com.example.user in $pkgs")
        assertTrue(pkgs.contains("com.example.another"), "expected com.example.another in $pkgs")
        // No-label case must fall back to packageName, not null or empty.
        val noLabel = entries.first { it.packageName == "com.example.another" }
        assertEquals("com.example.another", noLabel.label)
    }

    @Test
    fun `InstalledAppsHelper mapToEntries returns sorted by label`() {
        val raw =
            listOf(
                Triple("com.z", 0, "Zebra"),
                Triple("com.a", 0, "Apple"),
                Triple("com.m", 0, "Mango"),
            )
        val entries = InstalledAppsHelper.mapToEntries(raw, selfPackage = "com.self")
        assertEquals(listOf("Apple", "Mango", "Zebra"), entries.map { it.label })
    }

    @Test
    fun `InstalledAppsHelper mapToEntries returns empty list when all are system or self`() {
        val raw =
            listOf(
                Triple("android", android.content.pm.ApplicationInfo.FLAG_SYSTEM, "Android"),
                Triple("com.self", 0, "Self"),
            )
        val entries = InstalledAppsHelper.mapToEntries(raw, selfPackage = "com.self")
        assertTrue(entries.isEmpty(), "expected empty list, got $entries")
    }
}

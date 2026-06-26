package com.openwarden.parent.android.demo

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * DEMO ONLY — insecure, hardcoded, no auth/TLS.
 *
 * Real transport = mDNS peer discovery + pinned TLS certificate + signed policy bundles
 * (all unbuilt). This client is scoped to the two-emulator live showcase ONLY.
 *
 * The child emulator (emulator-5554) exposes its Ktor server on :7180 which is bridged
 * to the host via:
 *   adb -s emulator-5554 forward tcp:7180 tcp:7180
 * Android emulators reach the host loopback at 10.0.2.2, so the URL below works
 * across both emulators without any extra network config.
 */
internal const val DEMO_CHILD_BASE_URL = "http://10.0.2.2:7180"

@Serializable
data class ChildStateResponse(
    val version: String = "",
    // Child sends a string here ("none" when no policy yet), NOT a number.
    @SerialName("policy_version") val policyVersion: String = "none",
    // Child wire key is policy_not_after (§2/ADR-042), NOT policy_expires_at.
    @SerialName("policy_not_after") val policyNotAfter: String = "n/a",
    val paired: Boolean = false,
    @SerialName("is_locked") val isLocked: Boolean = false,
    // Child self-reported wall-clock (epoch ms) at response time — the liveness signal the
    // dashboard's freshness window judges. 0 (default) when absent → treated as not-fresh.
    @SerialName("reported_at") val reportedAt: Long = 0L,
)

@Serializable
data class AppUsageEntry(
    // Child wire keys are camelCase: packageName / foregroundMinutes / label.
    val packageName: String = "",
    val foregroundMinutes: Long = 0L,
    val label: String = "",
)

/** Child /usage envelope: {"source","window_hours","per_app":[…]}. */
@Serializable
data class UsageResponse(
    val source: String = "",
    @SerialName("window_hours") val windowHours: Int = 0,
    @SerialName("per_app") val perApp: List<AppUsageEntry> = emptyList(),
)

/**
 * Child /apps response entry: {"packageName","label"}.
 *
 * The child endpoint does not yet exist in the demo server — [ChildApiClient.getApps]
 * handles the 404/connection-refused case fail-closed and returns an [ApiResult.Failure].
 * When the child-side /apps endpoint ships (#30), this type will match its wire shape.
 */
@Serializable
data class InstalledAppEntry(
    val packageName: String = "",
    val label: String = "",
)

/** Child /apps envelope: {"apps":[…]}. */
@Serializable
data class InstalledAppsResponse(
    val apps: List<InstalledAppEntry> = emptyList(),
)

sealed class ApiResult<out T> {
    data class Success<T>(
        val data: T,
    ) : ApiResult<T>()

    data class Failure(
        val message: String,
    ) : ApiResult<Nothing>()
}

/**
 * Thin Ktor client scoped to the demo child server.
 *
 * Implements [java.io.Closeable]: call [close] when the client is no longer needed
 * (e.g. when the enclosing repository is discarded) to release OkHttp's thread pool
 * and connection pool. [DemoAllowlistRepository] closes this client in its own [close].
 */
internal class ChildApiClient : java.io.Closeable {
    private val json =
        Json {
            ignoreUnknownKeys = true
            isLenient = true
        }

    private val http =
        HttpClient(OkHttp) {
            install(ContentNegotiation) { json(json) }
            // Short timeouts so the UI fails fast and visibly rather than hanging.
            engine {
                config {
                    connectTimeout(
                        java.util.concurrent.TimeUnit.SECONDS
                            .toMillis(5),
                        java.util.concurrent.TimeUnit.MILLISECONDS,
                    )
                    readTimeout(
                        java.util.concurrent.TimeUnit.SECONDS
                            .toMillis(5),
                        java.util.concurrent.TimeUnit.MILLISECONDS,
                    )
                }
            }
        }

    suspend fun getState(): ApiResult<ChildStateResponse> =
        runCatching {
            http.get("$DEMO_CHILD_BASE_URL/state").body<ChildStateResponse>()
        }.fold(
            onSuccess = { ApiResult.Success(it) },
            onFailure = { ApiResult.Failure(it.message ?: "Unknown error") },
        )

    suspend fun postLock(): ApiResult<Unit> =
        runCatching {
            http.post("$DEMO_CHILD_BASE_URL/lock")
        }.fold(
            onSuccess = { ApiResult.Success(Unit) },
            onFailure = { ApiResult.Failure(it.message ?: "Unknown error") },
        )

    suspend fun getUsage(): ApiResult<List<AppUsageEntry>> =
        runCatching {
            // Child returns an object envelope, not a bare array — unwrap per_app.
            http.get("$DEMO_CHILD_BASE_URL/usage").body<UsageResponse>().perApp
        }.fold(
            onSuccess = { ApiResult.Success(it) },
            onFailure = { ApiResult.Failure(it.message ?: "Unknown error") },
        )

    /**
     * Fetch the installed-app list from the child's /apps endpoint.
     *
     * Fail-closed: any network error, non-200, or parse failure returns
     * [ApiResult.Failure]. The caller MUST NOT treat failure as "empty allowlist";
     * it must surface the error and keep the last-known allowlist frozen.
     *
     * NOTE: the child /apps endpoint is a stub (not yet implemented in child-android).
     * Until it ships, this will return a Failure, which the UI surfaces as an explicit
     * error (not a silent empty list). See child-android issue #30.
     */
    suspend fun getApps(): ApiResult<List<InstalledAppEntry>> =
        runCatching {
            http.get("$DEMO_CHILD_BASE_URL/apps").body<InstalledAppsResponse>().apps
        }.fold(
            onSuccess = { ApiResult.Success(it) },
            onFailure = { ApiResult.Failure(it.message ?: "Unknown error fetching /apps") },
        )

    /** Release the underlying OkHttp thread pool and connection pool. */
    override fun close() {
        http.close()
    }
}

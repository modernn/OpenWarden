package com.openwarden.parent.android.policy

import com.openwarden.parent.android.demo.ApiResult
import com.openwarden.parent.android.demo.ChildApiClient
import com.openwarden.parent.dashboard.AppCategory
import com.openwarden.parent.policy.AllowlistRepository
import com.openwarden.parent.policy.AppInfo
import com.openwarden.parent.policy.FetchAppsResult

/**
 * DEMO-grade [AllowlistRepository] backed by the insecure demo [ChildApiClient].
 *
 * Allowlist persistence uses an in-memory store — this is intentional for the demo:
 * there is no Keystore-backed storage yet. The production implementation will use
 * AndroidKeyStore / EncryptedSharedPreferences.
 *
 * Fail-closed: [fetchInstalledApps] wraps [ApiResult.Failure] as [FetchAppsResult.Error];
 * no failure path silently returns an empty-success that would appear to allow all apps.
 *
 * Implements [java.io.Closeable]: call [close] to release the underlying OkHttp resources
 * when this repository is no longer needed (e.g. on Activity destroy).
 *
 * The secondary constructor accepting [fetchOverride] is intended for unit tests only;
 * it injects a fake fetch function so tests never hit the real network.
 */
class DemoAllowlistRepository private constructor(
    private val client: ChildApiClient?,
    private val fetchOverride: (suspend () -> FetchAppsResult)?,
) : AllowlistRepository,
    java.io.Closeable {
    /** Production constructor — uses the real [ChildApiClient]. */
    constructor() : this(client = ChildApiClient(), fetchOverride = null)

    /**
     * Test-only constructor — [fetchFn] is called instead of the real network client.
     * No [ChildApiClient] is created, so no OkHttp resources are allocated.
     */
    internal constructor(fetchFn: suspend () -> FetchAppsResult) :
        this(client = null, fetchOverride = fetchFn)

    // In-memory allowlist store (demo only — survives the screen but not process death).
    private var persistedAllowlist: Set<String> = emptySet()

    override suspend fun fetchInstalledApps(): FetchAppsResult {
        if (fetchOverride != null) return fetchOverride.invoke()
        return when (val result = checkNotNull(client).getApps()) {
            is ApiResult.Success -> {
                FetchAppsResult.Success(
                    result.data.map {
                        AppInfo(
                            packageName = it.packageName,
                            label = it.label,
                            category = AppCategory.fromRaw(it.category),
                        )
                    },
                )
            }

            is ApiResult.Failure -> {
                FetchAppsResult.Error(result.message)
            }
        }
    }

    override fun saveAllowlist(allowlist: Set<String>) {
        persistedAllowlist = allowlist
    }

    override fun loadAllowlist(): Set<String> = persistedAllowlist

    /** Release the [ChildApiClient]'s OkHttp thread pool and connection pool. */
    override fun close() {
        client?.close()
    }
}

package com.openwarden.parent.android.policy

import com.openwarden.parent.android.demo.ApiResult
import com.openwarden.parent.android.demo.ChildApiClient
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
 */
class DemoAllowlistRepository internal constructor(
    private val client: ChildApiClient = ChildApiClient(),
) : AllowlistRepository {

    // In-memory allowlist store (demo only — survives the screen but not process death).
    private var persistedAllowlist: Set<String> = emptySet()

    override suspend fun fetchInstalledApps(): FetchAppsResult {
        return when (val result = client.getApps()) {
            is ApiResult.Success -> FetchAppsResult.Success(
                result.data.map { AppInfo(packageName = it.packageName, label = it.label) },
            )
            is ApiResult.Failure -> FetchAppsResult.Error(result.message)
        }
    }

    override fun saveAllowlist(allowlist: Set<String>) {
        persistedAllowlist = allowlist
    }

    override fun loadAllowlist(): Set<String> = persistedAllowlist
}

package com.openwarden.parent.policy

/**
 * Result of fetching the installed-app list from the child.
 *
 * Fail-closed: any error produces [Error], which the UI renders as an explicit
 * warning and leaves the allowlist untouched (no silent pass-through).
 */
sealed class FetchAppsResult {
    data class Success(val apps: List<AppInfo>) : FetchAppsResult()

    /**
     * Transport or parse error. The [message] is surfaced verbatim to the parent
     * (never to the child). The allowlist must NOT be changed on this path.
     */
    data class Error(val message: String) : FetchAppsResult()
}

/**
 * App-layer seam between the allowlist editor and the child transport.
 *
 * The real Android implementation (demo or production) satisfies this interface
 * via [ChildApiClient]. The interface is declared in :shared so that
 * [AllowlistEditorViewModel] (also in :shared) can be unit-tested without
 * a real HTTP stack.
 *
 * DO NOT add signing, bundle-building, or crypto calls here — those belong
 * in the policy-bundle send flow (#27, human-gated).
 */
interface AllowlistRepository {
    /**
     * Fetch the list of installed apps from the paired child device.
     *
     * Implementations MUST fail closed: on any error return [FetchAppsResult.Error]
     * and never silently return an empty list that would appear to allow all apps.
     */
    suspend fun fetchInstalledApps(): FetchAppsResult

    /**
     * Persist the current allowlist snapshot to local storage so it survives
     * process death and is available for bundle assembly (#27).
     *
     * This is a fire-and-forget write; callers should not block on it.
     */
    fun saveAllowlist(allowlist: Set<String>)

    /**
     * Load the allowlist that was last saved via [saveAllowlist].
     * Returns empty set if nothing was ever saved (first-launch safe).
     */
    fun loadAllowlist(): Set<String>
}

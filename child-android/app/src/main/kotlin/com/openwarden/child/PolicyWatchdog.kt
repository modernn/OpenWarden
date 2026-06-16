package com.openwarden.child

import android.content.Context
import android.util.Log

/**
 * The self-healing policy watchdog. A single [reassert] re-applies the local policy surfaces —
 * the Day-One restriction baseline and the app allowlist — so the child stays locked down even
 * if a restriction is cleared, a bundle changes, or connectivity flips. The DNS floor is a
 * wired-but-empty seam (#19). [PolicyService] drives it from three triggers (ADR-021): boot,
 * connectivity change, and a periodic timer.
 *
 * ## Fail-closed-but-alive
 * Each step is independently guarded and `reassert()` **never throws**. The day-one enforcer
 * already calls `lockNow()` on a verify gap (ADR-020); the watchdog's job is to keep RETRYING,
 * so a thrown step must not kill the service — it is logged and the next trigger re-attempts. A
 * failure in one surface (restrictions) must not skip the others (allowlist, DNS) — re-asserting
 * fewer surfaces would be failing *open*.
 *
 * The surfaces are injected as seams so the fail-closed-but-alive contract is testable without a
 * live Device Owner. [forContext] wires the real [PolicyEnforcer] + [PolicyStore].
 */
class PolicyWatchdog(
    private val reassertRestrictions: () -> Unit,
    private val reassertAllowlist: () -> Unit,
    private val reassertDnsFloor: () -> Unit = {},
) {

    /**
     * Re-assert every local policy surface, in fail-closed order (restrictions first). Each step
     * is guarded independently so a failure in one does not skip the others, and nothing
     * propagates — the service must survive to retry.
     */
    fun reassert() {
        runCatching { reassertRestrictions() }
            .onFailure { Log.e(TAG, "restriction re-assert failed (lock attempted, retry next tick): ${it.message}") }
        runCatching { reassertAllowlist() }
            .onFailure { Log.e(TAG, "allowlist re-assert failed: ${it.message}") }
        runCatching { reassertDnsFloor() }
            .onFailure { Log.e(TAG, "DNS floor re-assert failed: ${it.message}") }
    }

    companion object {
        const val TAG = "OpenWardenWatchdog"

        /** Cadence of the periodic watchdog tick — bounds how long drift can persist. */
        const val INTERVAL_MS = 30_000L

        /**
         * The allowlist to enforce for a [PolicyStore.LoadResult] — **fail-closed**:
         *   - [PolicyStore.LoadResult.Loaded] → the bundle's allowlist.
         *   - [PolicyStore.LoadResult.Missing] / [PolicyStore.LoadResult.Corrupt] → **deny-all**
         *     (empty set → `applyAllowlist` suspends every non-allowlisted app). A missing or
         *     corrupt bundle (the G2 storage-fill / tamper vector) must yield *more* restriction,
         *     never a frozen allowlist (ATTACKS.md item 4, DEFENSES.md G2). `loadActive()`
         *     collapses Missing+Corrupt to null, which is why the watchdog branches on `load()`.
         */
        fun allowlistFor(result: PolicyStore.LoadResult): Set<String> = when (result) {
            is PolicyStore.LoadResult.Loaded -> result.bundle.policy.allowlist.toSet()
            else -> emptySet()
        }

        /** Wire the watchdog to the real on-device policy surfaces. */
        fun forContext(context: Context): PolicyWatchdog {
            val enforcer = PolicyEnforcer(context)
            val store = PolicyStore(context)
            return PolicyWatchdog(
                reassertRestrictions = { enforcer.applyDayOneRestrictions() },
                reassertAllowlist = { enforcer.applyAllowlist(allowlistFor(store.load())) },
                // TODO(#19): re-assert the fail-closed DNS floor here (pin Private DNS to the
                // public filtering resolver — ADR-016). The triggers (boot / connectivity /
                // timer) already call this seam; only the body is deferred to the DNS-floor issue.
                reassertDnsFloor = {},
            )
        }
    }
}

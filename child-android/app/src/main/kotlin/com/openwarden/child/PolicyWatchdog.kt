package com.openwarden.child

import android.content.Context
import android.os.SystemClock
import android.util.Log

/**
 * The self-healing policy watchdog. A single [reassert] re-applies the local policy surfaces —
 * the Day-One restriction baseline, the app allowlist, and the DNS floor — and runs the
 * profile-escape detection check, so the child stays locked down even if a restriction is
 * cleared, a bundle changes, or connectivity flips. [PolicyService] drives it from three
 * triggers (ADR-021): boot, connectivity change, and a periodic timer.
 *
 * ## Fail-closed-but-alive
 * Each step is independently guarded and `reassert()` **never throws**. The day-one enforcer
 * already calls `lockNow()` on a verify gap (ADR-020); the watchdog's job is to keep RETRYING,
 * so a thrown step must not kill the service — it is logged and the next trigger re-attempts. A
 * failure in one surface (restrictions) must not skip the others (allowlist, DNS, profile check)
 * — re-asserting fewer surfaces would be failing *open*.
 *
 * The surfaces are injected as seams so the fail-closed-but-alive contract is testable without a
 * live Device Owner. [forContext] wires the real [PolicyEnforcer] + [PolicyStore] + [ProfileGuard].
 */
class PolicyWatchdog(
    private val reassertRestrictions: () -> Unit,
    private val reassertAllowlist: () -> Unit,
    private val reassertDnsFloor: () -> Unit = {},
    private val checkProfiles: () -> Unit = {},
) {

    /**
     * Re-assert every local policy surface, in fail-closed order (restrictions first). Each step
     * is guarded independently so a failure in one does not skip the others, and nothing
     * propagates — the service must survive to retry. The profile-escape check runs last: it is
     * detection layered on top of the restriction that already blocks profile creation (ADR-022).
     */
    fun reassert() {
        runCatching { reassertRestrictions() }
            .onFailure { Log.e(TAG, "restriction re-assert failed (lock attempted, retry next tick): ${it.message}") }
        runCatching { reassertAllowlist() }
            .onFailure { Log.e(TAG, "allowlist re-assert failed: ${it.message}") }
        runCatching { reassertDnsFloor() }
            .onFailure { Log.e(TAG, "DNS floor re-assert failed: ${it.message}") }
        runCatching { checkProfiles() }
            .onFailure { Log.e(TAG, "profile-escape check failed: ${it.message}") }
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

        /**
         * ADR-024 no-contact ratchet — the allowlist to enforce given the current [tier]:
         *   - [Ratchet.Tier.FRESH] → the bundle's allowlist ([allowlistFor]).
         *   - [Ratchet.Tier.STALE] / [Ratchet.Tier.STRICT] → **deny-all** (`emptySet`): once the
         *     parent has been silent past the stale threshold the frozen bundle's allowlist is no
         *     longer trusted, so every non-allowlisted (i.e. every) app is suspended.
         * Pure so the ratchet wiring is unit-testable without a device.
         */
        fun ratchetAllowlist(tier: Ratchet.Tier, result: PolicyStore.LoadResult): Set<String> =
            if (tier == Ratchet.Tier.FRESH) allowlistFor(result) else emptySet()

        /**
         * ADR-024 — the DNS resolver to pin given the current [tier]:
         *   - [Ratchet.Tier.STRICT] → `null`: distrust the frozen bundle entirely, so the DNS floor
         *     falls to its default filtering resolver (the Missing/Corrupt path) — the stale
         *     bundle's `private_dns` choice is ignored.
         *   - otherwise → the bundle's `private_dns` (still trusted at FRESH/STALE).
         * The floor itself never resolves to OFF/OPPORTUNISTIC regardless (ADR-016).
         */
        fun ratchetDns(tier: Ratchet.Tier, result: PolicyStore.LoadResult): String? =
            if (tier == Ratchet.Tier.STRICT) {
                null
            } else {
                (result as? PolicyStore.LoadResult.Loaded)?.bundle?.policy?.private_dns
            }

        /**
         * ADR-041 §5.1 freshness tier for the ACTIVE bundle (surface B — the window must bite an
         * already-applied bundle as time passes, not only at admission):
         *   - active bundle present + a [FreshnessClock.Now.Usable] estimate **past `not_after`**
         *     → [Ratchet.Tier.STALE] (deny-all): the applied policy has expired (PROTOCOL §5 step 10).
         *   - otherwise → [Ratchet.Tier.FRESH]: an `Unusable` clock (post-reboot / not-yet-anchored)
         *     does NOT itself force stale — it DEFERS to the ADR-024 no-contact ratchet (which denies
         *     all after the silence threshold), so a routine reboot doesn't blanket-block every
         *     allowlisted app until the next parent contact ("restrictions intact + apps usable after
         *     restart"). No active bundle → the Missing/Corrupt deny-all path already covers it.
         * Pure → unit-testable without a device.
         */
        fun freshnessTier(result: PolicyStore.LoadResult, now: FreshnessClock.Now): Ratchet.Tier {
            val bundle = (result as? PolicyStore.LoadResult.Loaded)?.bundle ?: return Ratchet.Tier.FRESH
            val usable = now as? FreshnessClock.Now.Usable ?: return Ratchet.Tier.FRESH // defer to ratchet
            return if (usable.monotonicNowMs >= bundle.not_after) Ratchet.Tier.STALE else Ratchet.Tier.FRESH
        }

        /** The stricter of the no-contact ratchet tier and the freshness tier (freshness only tightens). */
        fun effectiveTier(ratchetTier: Ratchet.Tier, freshnessTier: Ratchet.Tier): Ratchet.Tier =
            if (freshnessTier.ordinal > ratchetTier.ordinal) freshnessTier else ratchetTier

        /** The §5.1 monotonic estimate from the persisted anchor + the kernel clock (ADR-041). */
        fun freshnessNow(store: ReplayFloorStore, nowElapsedMs: Long): FreshnessClock.Now =
            FreshnessClock.estimate(
                FreshnessClock.Anchor(
                    parentAnchorMs = store.freshnessAnchorParentMs(),
                    elapsedAtAnchorMs = store.freshnessAnchorElapsedMs(),
                    notAfterWatermarkMs = store.notAfterWatermarkMs(),
                ),
                nowElapsedMs,
            )

        /** Wire the watchdog to the real on-device policy surfaces. */
        fun forContext(context: Context): PolicyWatchdog {
            val enforcer = PolicyEnforcer(context)
            val store = PolicyStore(context)
            val ratchet = ContactClock.forContext(context)
            val floorStore = ReplayFloorStore(context)
            return PolicyWatchdog(
                reassertRestrictions = { enforcer.applyDayOneRestrictions() },
                // R4: load the active allowlist INSIDE the apply lock (reassertActiveAllowlist), so a
                // watchdog tick never applies a stale snapshot that a newer /policy apply superseded.
                // ADR-024/ADR-041: the *tier* (silence + active-bundle freshness, bundle-independent
                // for the silence part) is read outside the lock; the bundle load stays inside it, so
                // deny-all on STALE/STRICT still honors R4.
                reassertAllowlist = {
                    val tier = effectiveTier(
                        ratchet.currentTier(),
                        freshnessTier(store.load(), freshnessNow(floorStore, SystemClock.elapsedRealtime())),
                    )
                    enforcer.reassertActiveAllowlist { ratchetAllowlist(tier, store.load()) }
                },
                // Pin the fail-closed DNS floor (ADR-016). The parent's chosen resolver comes
                // from the active bundle's private_dns; a missing/corrupt bundle (null) or any
                // non-filtering host resolves to the default filtering host — never OFF. Re-pinned
                // on every trigger (boot / connectivity / timer), incl. airplane-mode toggles.
                // ADR-024/ADR-041: at STRICT (silence) or an EXPIRED active bundle the bundle's
                // resolver is ignored (default filtering host).
                reassertDnsFloor = {
                    val tier = effectiveTier(
                        ratchet.currentTier(),
                        freshnessTier(store.load(), freshnessNow(floorStore, SystemClock.elapsedRealtime())),
                    )
                    DnsFloor(context).applyFloor(ratchetDns(tier, store.load()))
                },
                // ADR-022 profile-escape backstop: the restrictions above already BLOCK
                // managed/private-profile creation; this detects a profile that exists anyway
                // (a full allowlist bypass) and contains it with lockNow().
                checkProfiles = { ProfileGuard.forContext(context).check() },
            )
        }
    }
}

package com.openwarden.child

import org.junit.Test
import kotlin.test.assertEquals

/**
 * ADR-041 D5 (issue #90, surface B): the active-bundle freshness tier folded into the watchdog.
 * Pure — the [PolicyWatchdog] companion helpers are device-free.
 */
class FreshnessTierTest {

    private fun bundle(notAfter: Long) = SignedBundle(
        v = 1,
        child_device_id = "child-aaaa",
        policy_seq = 5L,
        issued_at = 1_000L,
        not_before = 1_000L,
        not_after = notAfter,
        nonce = "9f1b3c4d5e6f70819a2b3c4d5e6f7081",
        policy = PolicyDoc(allowlist = listOf("com.example.app")),
        sig = "",
    )

    private fun loaded(notAfter: Long) = PolicyStore.LoadResult.Loaded(bundle(notAfter))
    private fun usable(now: Long) = FreshnessClock.Now.Usable(now)

    @Test
    fun activeBundlePastNotAfterIsStale() {
        // monotonic_now (2500) >= not_after (2000) => the applied policy has expired => deny-all.
        assertEquals(
            Ratchet.Tier.STALE,
            PolicyWatchdog.freshnessTier(loaded(notAfter = 2000L), usable(2500L)),
        )
    }

    @Test
    fun activeBundleWithinWindowIsFresh() {
        assertEquals(
            Ratchet.Tier.FRESH,
            PolicyWatchdog.freshnessTier(loaded(notAfter = 2000L), usable(1500L)),
        )
    }

    @Test
    fun unusableClockDefersToTheRatchetNotForcedStale() {
        // Post-reboot / not-yet-anchored: freshness must NOT itself force deny-all (the silence
        // ratchet governs); a routine reboot does not blanket-block every allowlisted app.
        assertEquals(
            Ratchet.Tier.FRESH,
            PolicyWatchdog.freshnessTier(loaded(notAfter = 2000L), FreshnessClock.Now.Unusable),
        )
    }

    @Test
    fun noActiveBundleIsFreshFreshnessWise() {
        // Missing/Corrupt is handled by the existing deny-all path, not the freshness tier.
        assertEquals(Ratchet.Tier.FRESH, PolicyWatchdog.freshnessTier(PolicyStore.LoadResult.Missing, usable(9999L)))
        assertEquals(Ratchet.Tier.FRESH, PolicyWatchdog.freshnessTier(PolicyStore.LoadResult.Corrupt, usable(9999L)))
    }

    @Test
    fun effectiveTierTakesTheStricter() {
        // Freshness can only tighten, never loosen.
        assertEquals(Ratchet.Tier.STALE, PolicyWatchdog.effectiveTier(Ratchet.Tier.FRESH, Ratchet.Tier.STALE))
        assertEquals(Ratchet.Tier.STRICT, PolicyWatchdog.effectiveTier(Ratchet.Tier.STRICT, Ratchet.Tier.STALE))
        assertEquals(Ratchet.Tier.STALE, PolicyWatchdog.effectiveTier(Ratchet.Tier.STALE, Ratchet.Tier.FRESH))
        assertEquals(Ratchet.Tier.FRESH, PolicyWatchdog.effectiveTier(Ratchet.Tier.FRESH, Ratchet.Tier.FRESH))
    }
}

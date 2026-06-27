package com.openwarden.child

import org.junit.Test
import kotlin.test.assertEquals

/**
 * The pure tier→surface mapping the watchdog uses (ADR-024 D5): how the ratchet tier rewrites the
 * allowlist + DNS source. Deterministic, so the ratchet's enforcement effect is testable without a
 * device (the issue's "watchdog ratchet step" coverage).
 */
class RatchetWiringTest {
    private fun loaded(
        allow: List<String>,
        dns: String? = null,
    ) = PolicyStore.LoadResult.Loaded(
        SignedBundle(
            v = 1,
            child_device_id = "c",
            policy_seq = 1L,
            issued_at = 1L,
            not_before = 1L,
            not_after = 2L,
            nonce = "00",
            policy = PolicyDoc(allowlist = allow, private_dns = dns),
            sig = "",
        ),
    )

    @Test
    fun `FRESH enforces the bundle allowlist`() {
        assertEquals(setOf("a", "b"), PolicyWatchdog.ratchetAllowlist(Ratchet.Tier.FRESH, loaded(listOf("a", "b"))))
    }

    @Test
    fun `STALE and STRICT both deny-all`() {
        assertEquals(emptySet<String>(), PolicyWatchdog.ratchetAllowlist(Ratchet.Tier.STALE, loaded(listOf("a"))))
        assertEquals(emptySet<String>(), PolicyWatchdog.ratchetAllowlist(Ratchet.Tier.STRICT, loaded(listOf("a"))))
    }

    @Test
    fun `a missing bundle is deny-all at every tier`() {
        assertEquals(emptySet<String>(), PolicyWatchdog.ratchetAllowlist(Ratchet.Tier.FRESH, PolicyStore.LoadResult.Missing))
        assertEquals(emptySet<String>(), PolicyWatchdog.ratchetAllowlist(Ratchet.Tier.STRICT, PolicyStore.LoadResult.Corrupt))
    }

    @Test
    fun `FRESH and STALE keep the bundle's DNS resolver`() {
        assertEquals("dns.example", PolicyWatchdog.ratchetDns(Ratchet.Tier.FRESH, loaded(emptyList(), dns = "dns.example")))
        assertEquals("dns.example", PolicyWatchdog.ratchetDns(Ratchet.Tier.STALE, loaded(emptyList(), dns = "dns.example")))
    }

    @Test
    fun `STRICT ignores the frozen bundle's DNS resolver (default filtering floor)`() {
        assertEquals(null, PolicyWatchdog.ratchetDns(Ratchet.Tier.STRICT, loaded(emptyList(), dns = "dns.example")))
    }
}

package com.openwarden.child

import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Unit tests for [PolicyWatchdog] — the self-healing re-assert core (issue #11 / ADR-021).
 *
 * The watchdog's surfaces are injected as function seams, so the **fail-closed-but-alive**
 * contract is proven deterministically without a live Device Owner: a throwing surface must not
 * skip the others and must never propagate (the FGS has to survive to retry). Runs under
 * Robolectric only so `android.util.Log` resolves — the logic itself is pure.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PolicyWatchdogTest {

    @Test
    fun `reassert invokes every surface in fail-closed order`() {
        val calls = mutableListOf<String>()
        val wd = PolicyWatchdog(
            reassertRestrictions = { calls += "restrictions" },
            reassertAllowlist = { calls += "allowlist" },
            reassertDnsFloor = { calls += "dns" },
            checkProfiles = { calls += "profiles" },
        )

        wd.reassert()

        // Restrictions first (the fail-closed floor), then allowlist, the DNS hook, and finally
        // the profile-escape detection backstop (ADR-022).
        assertEquals(listOf("restrictions", "allowlist", "dns", "profiles"), calls)
    }

    @Test
    fun `reassert does not skip later surfaces when restrictions throws`() {
        var allowlistRan = false
        var dnsRan = false
        val wd = PolicyWatchdog(
            reassertRestrictions = { throw IllegalStateException("restriction re-assert blew up") },
            reassertAllowlist = { allowlistRan = true },
            reassertDnsFloor = { dnsRan = true },
        )

        wd.reassert() // must not throw

        // Re-asserting fewer surfaces because an earlier one failed would be failing OPEN.
        assertTrue(allowlistRan, "allowlist must still re-assert after restrictions threw")
        assertTrue(dnsRan, "DNS floor must still re-assert after restrictions threw")
    }

    @Test
    fun `reassert attempts every surface and never propagates even when all throw`() {
        var attempts = 0
        val wd = PolicyWatchdog(
            reassertRestrictions = { attempts++; throw RuntimeException("a") },
            reassertAllowlist = { attempts++; throw RuntimeException("b") },
            reassertDnsFloor = { attempts++; throw RuntimeException("c") },
            checkProfiles = { attempts++; throw RuntimeException("d") },
        )

        wd.reassert() // must not propagate

        // All four attempted (no early-out on the first throw) AND nothing escaped — reaching
        // this assertion at all proves no exception propagated out of reassert().
        assertEquals(
            4,
            attempts,
            "every surface must be attempted though each throws, with no exception propagated",
        )
    }

    // ---------------------------------------------------------------------
    // allowlistFor — fail-closed deny-all on a missing/corrupt bundle
    // ---------------------------------------------------------------------

    @Test
    fun `allowlistFor returns the bundle allowlist when loaded`() {
        val bundle = SignedBundle(
            v = 1, issued_at = 1L, not_before = 1L, not_after = 2L, nonce = "00",
            policy = PolicyDoc(allowlist = listOf("com.foo", "com.bar")),
        )
        assertEquals(
            setOf("com.foo", "com.bar"),
            PolicyWatchdog.allowlistFor(PolicyStore.LoadResult.Loaded(bundle)),
        )
    }

    @Test
    fun `allowlistFor denies all when the bundle is missing`() {
        assertEquals(emptySet<String>(), PolicyWatchdog.allowlistFor(PolicyStore.LoadResult.Missing))
    }

    @Test
    fun `allowlistFor denies all when the bundle is corrupt`() {
        // Corrupt = the G2 storage-fill / tamper vector: deny-all, never freeze the allowlist.
        assertEquals(emptySet<String>(), PolicyWatchdog.allowlistFor(PolicyStore.LoadResult.Corrupt))
    }

    @Test
    fun `repeated reassert re-invokes restrictions each tick - drift revert`() {
        var count = 0
        val wd = PolicyWatchdog(
            reassertRestrictions = { count++ },
            reassertAllowlist = {},
        )

        wd.reassert()
        wd.reassert()
        wd.reassert()

        // A silently cleared restriction is reverted because every tick re-applies it.
        assertEquals(3, count, "each watchdog tick must re-invoke the restriction re-assert")
    }

    @Test
    fun `interval is a sane drift bound`() {
        assertTrue(
            PolicyWatchdog.INTERVAL_MS in 1_000..120_000,
            "watchdog interval must bound drift to a reasonable window",
        )
    }

    @Test
    fun `forContext wires the real surfaces without throwing`() {
        // Construction must not require Device Owner (reassert would; we only build here).
        val wd = PolicyWatchdog.forContext(RuntimeEnvironment.getApplication())
        assertNotNull(wd)
    }
}

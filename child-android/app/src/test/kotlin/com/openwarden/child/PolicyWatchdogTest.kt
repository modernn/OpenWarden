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
        )

        wd.reassert()

        // Restrictions first (the fail-closed floor), then allowlist, then the DNS hook.
        assertEquals(listOf("restrictions", "allowlist", "dns"), calls)
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
    fun `reassert never propagates even when every surface throws`() {
        val wd = PolicyWatchdog(
            reassertRestrictions = { throw RuntimeException("a") },
            reassertAllowlist = { throw RuntimeException("b") },
            reassertDnsFloor = { throw RuntimeException("c") },
        )

        // Reaching the assertion means no exception escaped — the FGS survives to retry.
        wd.reassert()
        assertTrue(true)
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

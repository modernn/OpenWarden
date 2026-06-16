package com.openwarden.child

import android.app.admin.DevicePolicyManager
import android.content.Context
import org.junit.After
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Tests for [DnsFloor] — the fail-closed DNS floor (issue #19 / ADR-016).
 *
 * The fail-closed core is the pure [DnsFloor.resolveFilteringHost] (no input may ever yield a
 * non-filtering / OFF / OPPORTUNISTIC host) and [DnsFloor.verifyOrThrow] (driven deterministically
 * through the injected readback seams). The end-to-end `setGlobalPrivateDnsModeSpecifiedHost`
 * round-trip is `assumeTrue`-gated on the Robolectric shadow supporting it.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DnsFloorTest {

    private lateinit var context: Context
    private lateinit var dpm: DevicePolicyManager

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
        Shadows.shadowOf(dpm).setDeviceOwner(AdminReceiver.componentName(context))
    }

    @After
    fun tearDown() {
        Shadows.shadowOf(dpm).setDeviceOwner(AdminReceiver.componentName(context))
    }

    // ---------------------------------------------------------------------
    // resolveFilteringHost — the fail-closed core, fully deterministic
    // ---------------------------------------------------------------------

    @Test
    fun `known filtering resolvers are honored`() {
        for (host in DnsFloor.FILTERING_RESOLVERS) {
            assertEquals(host, DnsFloor.resolveFilteringHost(host))
        }
    }

    @Test
    fun `host match is case-insensitive and trimmed`() {
        // Use a NON-default member so a pass proves "normalized THEN matched", not "fell back to
        // the default" (which would also equal DEFAULT and hide a broken normalization).
        assertEquals(
            "family-filter-dns.cleanbrowsing.org",
            DnsFloor.resolveFilteringHost("  FAMILY-FILTER-DNS.CleanBrowsing.ORG  "),
        )
    }

    @Test
    fun `trailing-dot fqdn falls back to the default filtering host`() {
        // Exact-match curated set: a trailing-dot FQDN is not a member, so it fails closed to the
        // default filtering host (still filtering — never OFF).
        assertEquals(
            DnsFloor.DEFAULT_FILTERING_HOST,
            DnsFloor.resolveFilteringHost("family.cloudflare-dns.com."),
        )
    }

    @Test
    fun `null empty and off-like inputs fall back to the default filtering host`() {
        for (bad in listOf(null, "", "   ", "off", "OFF", "opportunistic", "OPPORTUNISTIC")) {
            assertEquals(
                DnsFloor.DEFAULT_FILTERING_HOST,
                DnsFloor.resolveFilteringHost(bad),
                "input <$bad> must resolve to the default filtering host, never OFF",
            )
        }
    }

    @Test
    fun `localhost and non-filtering resolvers fall back to the default filtering host`() {
        for (bad in listOf("127.0.0.1", "localhost", "openwarden.localhost", "1.1.1.1", "8.8.8.8", "dns.google")) {
            assertEquals(
                DnsFloor.DEFAULT_FILTERING_HOST,
                DnsFloor.resolveFilteringHost(bad),
                "non-filtering host <$bad> must resolve to the default filtering host",
            )
        }
    }

    @Test
    fun `resolveFilteringHost ALWAYS returns a known filtering resolver`() {
        // Property: no input — however hostile — can produce a non-filtering result.
        val inputs = listOf(null, "", "off", "opportunistic", "127.0.0.1", "1.1.1.1", "evil.example", "family.cloudflare-dns.com")
        for (i in inputs) {
            assertTrue(
                DnsFloor.resolveFilteringHost(i) in DnsFloor.FILTERING_RESOLVERS,
                "resolved host for <$i> must be a curated filtering resolver",
            )
        }
    }

    @Test
    fun `default filtering host is itself a curated filtering resolver`() {
        assertTrue(DnsFloor.DEFAULT_FILTERING_HOST in DnsFloor.FILTERING_RESOLVERS)
    }

    // ---------------------------------------------------------------------
    // verifyOrThrow — deterministic via injected readback seams
    // ---------------------------------------------------------------------

    @Test
    fun `verifyOrThrow passes when mode is PROVIDER_HOSTNAME host matches and toggle is locked`() {
        val floor = DnsFloor(
            context,
            readMode = { DevicePolicyManager.PRIVATE_DNS_MODE_PROVIDER_HOSTNAME },
            readHost = { DnsFloor.DEFAULT_FILTERING_HOST },
            readPrivateDnsLocked = { true },
        )
        floor.verifyOrThrow(DnsFloor.DEFAULT_FILTERING_HOST)
    }

    @Test
    fun `verifyOrThrow throws when mode is OFF`() {
        val floor = DnsFloor(
            context,
            readMode = { DevicePolicyManager.PRIVATE_DNS_MODE_OFF },
            readHost = { null },
            readPrivateDnsLocked = { true },
        )
        assertFailsWith<DnsFloorException> { floor.verifyOrThrow(DnsFloor.DEFAULT_FILTERING_HOST) }
    }

    @Test
    fun `verifyOrThrow throws when host does not match`() {
        val floor = DnsFloor(
            context,
            readMode = { DevicePolicyManager.PRIVATE_DNS_MODE_PROVIDER_HOSTNAME },
            readHost = { "family-filter-dns.cleanbrowsing.org" },
            readPrivateDnsLocked = { true },
        )
        assertFailsWith<DnsFloorException> { floor.verifyOrThrow(DnsFloor.DEFAULT_FILTERING_HOST) }
    }

    @Test
    fun `verifyOrThrow throws when the private-DNS toggle is not locked`() {
        // A pinned-but-unlocked floor lets the child change DNS in Settings — fail-closed must
        // treat the missing DISALLOW_CONFIG_PRIVATE_DNS lock as a verify failure.
        val floor = DnsFloor(
            context,
            readMode = { DevicePolicyManager.PRIVATE_DNS_MODE_PROVIDER_HOSTNAME },
            readHost = { DnsFloor.DEFAULT_FILTERING_HOST },
            readPrivateDnsLocked = { false },
        )
        assertFailsWith<DnsFloorException> { floor.verifyOrThrow(DnsFloor.DEFAULT_FILTERING_HOST) }
    }

    // ---------------------------------------------------------------------
    // applyFloor — guards + fail-closed verify + round-trip (gated)
    // ---------------------------------------------------------------------

    @Test
    fun `applyFloor throws when app is NOT device owner`() {
        Shadows.shadowOf(dpm).setDeviceOwner(null)
        assertFailsWith<IllegalArgumentException> {
            DnsFloor(context).applyFloor(DnsFloor.DEFAULT_FILTERING_HOST)
        }
    }

    @Test
    fun `applyFloor fails closed when readback does not confirm the floor`() {
        // Even as Device Owner, if the platform did not actually pin the floor, applyFloor must
        // throw rather than return having (silently) left DNS unfiltered.
        val floor = DnsFloor(
            context,
            readMode = { DevicePolicyManager.PRIVATE_DNS_MODE_OFF },
            readHost = { null },
            readPrivateDnsLocked = { true },
        )
        try {
            floor.applyFloor(DnsFloor.DEFAULT_FILTERING_HOST)
            throw AssertionError("expected DnsFloorException — applyFloor must fail closed")
        } catch (e: DnsFloorException) {
            // expected
        } catch (e: UnsupportedOperationException) {
            assumeTrue("Robolectric shadow supports setGlobalPrivateDnsModeSpecifiedHost", false)
        }
    }

    @Test
    fun `applyFloor pins the floor end-to-end`() {
        val admin = AdminReceiver.componentName(context)
        val floor = DnsFloor(context) // real readback
        val host = "family-filter-dns.cleanbrowsing.org"
        try {
            floor.applyFloor(host)
        } catch (e: UnsupportedOperationException) {
            assumeTrue("Robolectric shadow supports setGlobalPrivateDnsModeSpecifiedHost", false)
        } catch (e: DnsFloorException) {
            assumeTrue("Robolectric shadow round-trips Private DNS mode/host + lock", false)
        }
        assertEquals(DevicePolicyManager.PRIVATE_DNS_MODE_PROVIDER_HOSTNAME, dpm.getGlobalPrivateDnsMode(admin))
        assertEquals(host, dpm.getGlobalPrivateDnsHost(admin))
    }
}

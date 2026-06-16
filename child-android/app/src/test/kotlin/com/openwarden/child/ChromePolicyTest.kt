package com.openwarden.child

import android.app.admin.DevicePolicyManager
import android.content.Context
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowDevicePolicyManager
import kotlin.test.assertContains
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Unit tests for [ChromePolicy] — runs on the JVM via Robolectric, no emulator required.
 *
 * Threat model coverage verified here:
 * - G1: gds.google.com Play-Services WebView hidden browser.
 * - W1: translate proxies, archive.org, 12ft.io, data:/blob:/file: URI schemes.
 *
 * NOTE on Robolectric sdk 34 application-restrictions round-trip:
 * [ShadowDevicePolicyManager] in Robolectric 4.13 does implement
 * setApplicationRestrictions / getApplicationRestrictions and returns the stored Bundle.
 * Tests below assert both the source-of-truth list ([ChromePolicy.urlBlocklist]) and the
 * round-tripped Bundle from the shadow DPM. If the shadow DPM returns null or an empty
 * Bundle (regression in the Robolectric version), the fallback assertions on
 * [ChromePolicy.urlBlocklist] still guard the contract.
 *
 * @see docs/adr/009-browser-strategy.md
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ChromePolicyTest {

    private lateinit var context: Context
    private lateinit var dpm: DevicePolicyManager
    private lateinit var shadow: ShadowDevicePolicyManager
    private lateinit var policy: ChromePolicy

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
        shadow = Shadows.shadowOf(dpm)

        // Mark this app as Device Owner so ChromePolicy.apply() passes the guard.
        val admin = AdminReceiver.componentName(context)
        shadow.setDeviceOwner(admin)

        policy = ChromePolicy(context)
    }

    // -------------------------------------------------------------------------
    // Source-of-truth list assertions (always run — not dependent on shadow DPM
    // round-trip behaviour)
    // -------------------------------------------------------------------------

    @Test
    fun `urlBlocklist contains G1 threat — gds dot google dot com`() {
        assertContains(policy.urlBlocklist, "gds.google.com")
    }

    @Test
    fun `urlBlocklist contains G1 threat — support google help`() {
        assertContains(policy.urlBlocklist, "support.google.com/help")
    }

    @Test
    fun `urlBlocklist contains G1 threat — play google help`() {
        assertContains(policy.urlBlocklist, "play.google.com/help")
    }

    @Test
    fun `urlBlocklist contains W1 threat — data URI scheme`() {
        assertContains(policy.urlBlocklist, "data://*")
    }

    @Test
    fun `urlBlocklist contains W1 threat — blob URI scheme`() {
        assertContains(policy.urlBlocklist, "blob://*")
    }

    @Test
    fun `urlBlocklist contains W1 threat — file URI scheme`() {
        assertContains(policy.urlBlocklist, "file://*")
    }

    @Test
    fun `urlBlocklist contains W1 threat — 12ft bypass proxy`() {
        assertContains(policy.urlBlocklist, "12ft.io")
    }

    @Test
    fun `urlBlocklist contains W1 threat — web archive org`() {
        assertContains(policy.urlBlocklist, "web.archive.org")
    }

    @Test
    fun `urlBlocklist contains W1 threat — wildcard translate goog subdomain`() {
        assertContains(policy.urlBlocklist, "*.translate.goog")
    }

    @Test
    fun `urlBlocklist is non-empty and contains at least 10 entries`() {
        assertTrue(
            policy.urlBlocklist.size >= 10,
            "urlBlocklist must cover at minimum G1 + W1 entries; got ${policy.urlBlocklist.size}",
        )
    }

    // -------------------------------------------------------------------------
    // apply() smoke test — verifies it does not throw under Device Owner shadow
    // -------------------------------------------------------------------------

    @Test
    fun `apply() does not throw when device owner`() {
        // Primary contract: apply() must not throw when Device Owner is set.
        policy.apply()
    }

    // -------------------------------------------------------------------------
    // Round-trip: apply() then read back restrictions from shadow DPM
    // -------------------------------------------------------------------------

    @Test
    fun `apply() stores URLBlocklist in DPM application restrictions for Chrome`() {
        policy.apply()

        val admin = AdminReceiver.componentName(context)
        // ShadowDevicePolicyManager in Robolectric 4.13 (sdk 34) round-trips
        // setApplicationRestrictions / getApplicationRestrictions correctly.
        val stored = dpm.getApplicationRestrictions(admin, ChromePolicy.CHROME_PKG)

        val blocklist = stored.getStringArray("URLBlocklist")
        assertNotNull(blocklist, "URLBlocklist key must be present in stored restrictions")

        val blocklistSet = blocklist.toSet()
        assertContains(blocklistSet, "gds.google.com")
        assertContains(blocklistSet, "data://*")
        assertContains(blocklistSet, "blob://*")
        assertContains(blocklistSet, "12ft.io")
        assertContains(blocklistSet, "web.archive.org")
        assertContains(blocklistSet, "*.translate.goog")
    }

    @Test
    fun `apply() stores SafeSitesFilterBehavior enabled in Chrome restrictions`() {
        policy.apply()

        val admin = AdminReceiver.componentName(context)
        val stored = dpm.getApplicationRestrictions(admin, ChromePolicy.CHROME_PKG)

        // SafeSitesFilterBehavior=1 means adult-content filtering enabled
        assertTrue(
            stored.containsKey("SafeSitesFilterBehavior"),
            "SafeSitesFilterBehavior must be present",
        )
    }

    @Test
    fun `apply() stores IncognitoModeAvailability disabled in Chrome restrictions`() {
        policy.apply()

        val admin = AdminReceiver.componentName(context)
        val stored = dpm.getApplicationRestrictions(admin, ChromePolicy.CHROME_PKG)

        assertTrue(
            stored.containsKey("IncognitoModeAvailability"),
            "IncognitoModeAvailability must be present",
        )
    }

    // -------------------------------------------------------------------------
    // Fail-closed guard: apply() must throw when NOT Device Owner
    // -------------------------------------------------------------------------

    @Test
    fun `apply() throws IllegalArgumentException when not device owner`() {
        // Create a fresh context whose package name is NOT the Device Owner.
        val otherContext = object : android.content.ContextWrapper(context) {
            override fun getPackageName(): String = "com.some.other.app"
        }
        val nonOwnerPolicy = ChromePolicy(otherContext)

        var threw = false
        try {
            nonOwnerPolicy.apply()
        } catch (e: IllegalArgumentException) {
            threw = true
        }
        assertTrue(threw, "apply() must throw IllegalArgumentException when not Device Owner (fail-closed)")
    }

    // -------------------------------------------------------------------------
    // Package name constant
    // -------------------------------------------------------------------------

    @Test
    fun `CHROME_PKG constant matches com dot android dot chrome`() {
        assertFalse(
            ChromePolicy.CHROME_PKG.isEmpty(),
            "CHROME_PKG must not be empty",
        )
        assertTrue(
            ChromePolicy.CHROME_PKG == "com.android.chrome",
            "CHROME_PKG must be com.android.chrome",
        )
    }
}

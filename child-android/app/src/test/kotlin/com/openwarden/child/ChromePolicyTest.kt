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
import kotlin.test.assertEquals
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
 * Contract tests call [ChromePolicy.apply] and assert against the round-tripped Bundle
 * from [ShadowDevicePolicyManager.getApplicationRestrictions] — this is the real contract
 * (the entries must be in the DPM bundle, not only in the in-memory list).
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
    // Source-of-truth list assertions (in-memory list content)
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
    // Contract tests: apply() → stored DPM bundle must contain key W1 entries.
    // These are the authoritative threat-model tests — a bug where apply() fails
    // to write entries into the DPM bundle would NOT be caught by the in-memory
    // list assertions above.
    // -------------------------------------------------------------------------

    @Test
    fun `apply() stores W1 threat — data URI scheme (correct Chrome form data colon star)`() {
        policy.apply()
        val stored = dpm.getApplicationRestrictions(AdminReceiver.componentName(context), ChromePolicy.CHROME_PKG)
        val blocklistSet = stored.getStringArray("URLBlocklist")?.toSet()
        assertNotNull(blocklistSet, "URLBlocklist must be present in stored restrictions")
        assertContains(blocklistSet, "data:*", "data:* must be in stored DPM bundle (not data://*)")
    }

    @Test
    fun `apply() stores W1 threat — blob URI scheme (correct Chrome form blob colon star)`() {
        policy.apply()
        val stored = dpm.getApplicationRestrictions(AdminReceiver.componentName(context), ChromePolicy.CHROME_PKG)
        val blocklistSet = stored.getStringArray("URLBlocklist")?.toSet()
        assertNotNull(blocklistSet, "URLBlocklist must be present in stored restrictions")
        assertContains(blocklistSet, "blob:*", "blob:* must be in stored DPM bundle (not blob://*)")
    }

    @Test
    fun `apply() stores W1 threat — file URI scheme (correct Chrome form file colon star)`() {
        policy.apply()
        val stored = dpm.getApplicationRestrictions(AdminReceiver.componentName(context), ChromePolicy.CHROME_PKG)
        val blocklistSet = stored.getStringArray("URLBlocklist")?.toSet()
        assertNotNull(blocklistSet, "URLBlocklist must be present in stored restrictions")
        assertContains(blocklistSet, "file:*", "file:* must be in stored DPM bundle (not file://*)")
    }

    @Test
    fun `apply() stores W1 threat — 12ft bypass proxy in DPM bundle`() {
        policy.apply()
        val stored = dpm.getApplicationRestrictions(AdminReceiver.componentName(context), ChromePolicy.CHROME_PKG)
        val blocklistSet = stored.getStringArray("URLBlocklist")?.toSet()
        assertNotNull(blocklistSet, "URLBlocklist must be present in stored restrictions")
        assertContains(blocklistSet, "12ft.io")
    }

    @Test
    fun `apply() stores W1 threat — web archive org in DPM bundle`() {
        policy.apply()
        val stored = dpm.getApplicationRestrictions(AdminReceiver.componentName(context), ChromePolicy.CHROME_PKG)
        val blocklistSet = stored.getStringArray("URLBlocklist")?.toSet()
        assertNotNull(blocklistSet, "URLBlocklist must be present in stored restrictions")
        assertContains(blocklistSet, "web.archive.org")
    }

    @Test
    fun `apply() stores G1 threat — gds google com in DPM bundle`() {
        policy.apply()
        val stored = dpm.getApplicationRestrictions(AdminReceiver.componentName(context), ChromePolicy.CHROME_PKG)
        val blocklistSet = stored.getStringArray("URLBlocklist")?.toSet()
        assertNotNull(blocklistSet, "URLBlocklist must be present in stored restrictions")
        assertContains(blocklistSet, "gds.google.com")
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
        assertContains(blocklistSet, "data:*")
        assertContains(blocklistSet, "blob:*")
        assertContains(blocklistSet, "12ft.io")
        assertContains(blocklistSet, "web.archive.org")
        assertContains(blocklistSet, "*.translate.goog")
    }

    @Test
    fun `apply() stores SafeSitesFilterBehavior=1 (filtering enabled) in Chrome restrictions`() {
        policy.apply()

        val admin = AdminReceiver.componentName(context)
        val stored = dpm.getApplicationRestrictions(admin, ChromePolicy.CHROME_PKG)

        // SafeSitesFilterBehavior=1 means adult-content filtering enabled.
        // Assert the actual value — containsKey alone would pass on SafeSitesFilterBehavior=0 (filtering OFF).
        assertEquals(
            1,
            stored.getInt("SafeSitesFilterBehavior"),
            "SafeSitesFilterBehavior must be 1 (filtering enabled)",
        )
    }

    @Test
    fun `apply() stores IncognitoModeAvailability=1 (incognito disabled) in Chrome restrictions`() {
        policy.apply()

        val admin = AdminReceiver.componentName(context)
        val stored = dpm.getApplicationRestrictions(admin, ChromePolicy.CHROME_PKG)

        // IncognitoModeAvailability=1 means incognito not available.
        // Assert the actual value — containsKey alone would pass on IncognitoModeAvailability=0 (incognito ON).
        assertEquals(
            1,
            stored.getInt("IncognitoModeAvailability"),
            "IncognitoModeAvailability must be 1 (incognito disabled)",
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

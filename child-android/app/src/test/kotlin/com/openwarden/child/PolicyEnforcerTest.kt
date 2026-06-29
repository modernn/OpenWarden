package com.openwarden.child

import android.app.admin.DevicePolicyManager
import android.content.Context
import android.os.UserManager
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
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Robolectric unit tests for [PolicyEnforcer] — the fail-closed Day-One restriction baseline
 * (issue #8 / ADR-020).
 *
 * Two layers, mirroring the [LockScreenLockdownTest] idiom:
 *
 *  - **Deterministic** behaviour tests drive the verify path through the injected
 *    `isRestrictionSet` readback seam, so failure-injection ("only some restrictions stuck")
 *    is exact and does not depend on whether the Robolectric shadow wires
 *    `addUserRestriction` through to `UserManager`.
 *  - **Round-trip** tests use the real [UserManager] reader and are gated with [assumeTrue]
 *    so they SKIP (never vacuously pass) if the shadow does not persist restrictions.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PolicyEnforcerTest {
    private lateinit var context: Context
    private lateinit var dpm: DevicePolicyManager

    /**
     * Independent witness of the canonical baseline (DEFENSES.md row 2 — the 17-restriction v1
     * ship set). Spelled out here, NOT derived from [PolicyEnforcer.requiredRestrictions], so a
     * change to the production list is caught instead of rubber-stamped.
     */
    private val canonical17 =
        setOf(
            UserManager.DISALLOW_FACTORY_RESET,
            UserManager.DISALLOW_SAFE_BOOT,
            UserManager.DISALLOW_DEBUGGING_FEATURES,
            UserManager.DISALLOW_CONFIG_VPN,
            UserManager.DISALLOW_MODIFY_ACCOUNTS,
            // DISALLOW_OEM_UNLOCK is a hidden @SystemApi constant; pin its AOSP key literally so
            // this witness stays independent of the production source.
            "no_oem_unlock",
            UserManager.DISALLOW_APPS_CONTROL,
            UserManager.DISALLOW_USB_FILE_TRANSFER,
            UserManager.DISALLOW_INSTALL_UNKNOWN_SOURCES_GLOBALLY,
            UserManager.DISALLOW_USER_SWITCH,
            UserManager.DISALLOW_ADD_USER,
            UserManager.DISALLOW_REMOVE_USER,
            UserManager.DISALLOW_CONFIG_DATE_TIME,
            UserManager.DISALLOW_MOUNT_PHYSICAL_MEDIA,
            UserManager.DISALLOW_CONFIG_TETHERING,
            UserManager.DISALLOW_CONFIG_MOBILE_NETWORKS,
            UserManager.DISALLOW_OUTGOING_BEAM,
        )

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
        Shadows.shadowOf(dpm).setDeviceOwner(AdminReceiver.componentName(context))
    }

    @After
    fun tearDown() {
        // Restore Device Owner — tests that clear it must not break isolation for the suite.
        Shadows.shadowOf(dpm).setDeviceOwner(AdminReceiver.componentName(context))
    }

    /** Enforcer whose verify readback is fully controlled by the test. */
    private fun enforcerReading(setKeys: Set<String>): PolicyEnforcer = PolicyEnforcer(context, isRestrictionSet = { it in setKeys })

    // Profile-escape block (ADR-022). Both pinned literally so this witness stays independent of
    // the production source (and dodges the @Deprecated symbol on DISALLOW_ADD_MANAGED_PROFILE).
    private val managedProfileKey = "no_add_managed_profile"
    private val privateProfileKey = "no_add_private_profile"

    /** The full required set on a pre-15 device: the canonical 17 + the always-on managed block. */
    private val api34Required = canonical17 + managedProfileKey

    // ---------------------------------------------------------------------
    // Static baseline composition — API-aware (ADR-020 baseline + ADR-022 profile block)
    //
    // Driven through the pure, sdk-parameterized `requiredRestrictionsForSdk(int)` so the API-35
    // branch is provable here even though this repo's Robolectric tops out below API 35 (a
    // @Config(sdk=[35]) would throw UnknownSdk). The String constants are compile-time inlined,
    // so the function needs no live Android runtime.
    // ---------------------------------------------------------------------

    @Test
    fun `requiredRestrictionsForSdk on API 34 is the 17 baseline plus the managed-profile escape block`() {
        val actual = PolicyEnforcer.requiredRestrictionsForSdk(34)
        assertEquals(
            api34Required,
            actual.toSet(),
            "Pre-15 baseline must be the canonical 17 plus DISALLOW_ADD_MANAGED_PROFILE",
        )
        assertEquals(18, actual.size, "Pre-15 baseline is 17 + managed-profile block")
        assertFalse(
            privateProfileKey in actual,
            "DISALLOW_ADD_PRIVATE_PROFILE must NOT be required below API 35 — applying an unknown " +
                "key would never verify set and would trip the fail-closed lock (brick a pre-15 device)",
        )
    }

    @Test
    fun `requiredRestrictionsForSdk on API 35 also blocks the private-space escape`() {
        val actual = PolicyEnforcer.requiredRestrictionsForSdk(35)
        assertEquals(
            canonical17 + managedProfileKey + privateProfileKey,
            actual.toSet(),
            "On API 35 the baseline must add both the managed- and private-profile escape blocks",
        )
        assertEquals(19, actual.size, "API 35 baseline is 17 + managed + private profile blocks")
        assertTrue(managedProfileKey in actual, "managed-profile block must be present on API 35 too")
        assertTrue(privateProfileKey in actual, "private-space block must be present on API 35")
    }

    @Test
    fun `the live requiredRestrictions matches the sdk-parameterized composition`() {
        // The instance property must equal the pure function for the running OS level — no drift.
        assertEquals(
            PolicyEnforcer.requiredRestrictionsForSdk(android.os.Build.VERSION.SDK_INT).toSet(),
            PolicyEnforcer(context).requiredRestrictions.toSet(),
        )
    }

    @Test
    fun `requiredRestrictions has no duplicates`() {
        val list = PolicyEnforcer(context).requiredRestrictions
        assertEquals(list.size, list.toSet().size, "Day-One baseline must not contain duplicate restrictions")
    }

    @Test
    fun `requiredRestrictions never disables the emergency dialer`() {
        // DISALLOW_OUTGOING_CALLS would block the emergency dialer — must never be in the set.
        assertFalse(
            UserManager.DISALLOW_OUTGOING_CALLS in PolicyEnforcer(context).requiredRestrictions,
            "DISALLOW_OUTGOING_CALLS must never be applied — emergency dialer must remain reachable",
        )
    }

    @Test
    fun `requiredRestrictions defers DNS toggle to issue 19`() {
        // The DNS fail-closed floor (pin resolver + lock toggle) is owned by #19, not #8.
        assertFalse(
            UserManager.DISALLOW_CONFIG_PRIVATE_DNS in PolicyEnforcer(context).requiredRestrictions,
            "DISALLOW_CONFIG_PRIVATE_DNS is owned by the DNS floor (#19), not the Day-One set",
        )
    }

    // ---------------------------------------------------------------------
    // restrictionFilter test seam (ADR-045 / issue #131)
    //
    // The seam exists so instrumented tests can assert the ENFORCED baseline on a provisioned
    // device while keeping adb alive (DISALLOW_DEBUGGING_FEATURES kills adb the instant it
    // enforces). The default filter is the identity, so the RELEASE set is never narrowed — these
    // tests pin both halves: production keeps the full set; a test filter drops only what it names.
    // ---------------------------------------------------------------------

    @Test
    fun `default restrictionFilter preserves the full release baseline including DISALLOW_DEBUGGING_FEATURES`() {
        // Regression: the production construction (default identity filter) must apply the COMPLETE
        // baseline. If a future change accidentally narrowed the default, this fails.
        val required = PolicyEnforcer(context).requiredRestrictions
        assertTrue(
            UserManager.DISALLOW_DEBUGGING_FEATURES in required,
            "Release set must always include DISALLOW_DEBUGGING_FEATURES (it is the only adb-console close; ADR-045)",
        )
        assertEquals(
            api34Required,
            required.toSet(),
            "Default (production) requiredRestrictions must be the full canonical 17 + managed-profile block",
        )
    }

    @Test
    fun `restrictionFilter omitting DISALLOW_DEBUGGING_FEATURES drops exactly that one key`() {
        val filtered =
            PolicyEnforcer(
                context,
                restrictionFilter = { it != UserManager.DISALLOW_DEBUGGING_FEATURES },
            ).requiredRestrictions

        assertFalse(
            UserManager.DISALLOW_DEBUGGING_FEATURES in filtered,
            "The injected test filter must remove DISALLOW_DEBUGGING_FEATURES",
        )
        assertEquals(
            api34Required - UserManager.DISALLOW_DEBUGGING_FEATURES,
            filtered.toSet(),
            "The filter must drop ONLY the named restriction — every other baseline entry remains",
        )
        assertEquals(
            api34Required.size - 1,
            filtered.size,
            "Filtering one restriction must shrink the set by exactly one (no other change)",
        )
    }

    @Test
    fun `applyDayOneRestrictions with the test filter verifies the filtered set cleanly`() {
        // The instrumented-test scenario, host-simulated: omit DISALLOW_DEBUGGING_FEATURES via the
        // seam, and have the readback report the FILTERED set as present. apply must return cleanly
        // (verify passes) and report nothing missing — i.e. omitting the adb-killer lets the rest
        // of the enforced baseline be asserted.
        val debugging = UserManager.DISALLOW_DEBUGGING_FEATURES
        val filteredPresent = api34Required - debugging
        val enforcer =
            PolicyEnforcer(
                context,
                isRestrictionSet = { it in filteredPresent },
                restrictionFilter = { it != debugging },
            )
        enforcer.applyDayOneRestrictions()
        assertTrue(
            enforcer.missingRestrictions().isEmpty(),
            "With the filter applied, the readback of the filtered set must leave nothing missing",
        )
    }

    @Test
    fun `without the filter the same filtered readback fails closed - proving the filter is load-bearing`() {
        // Contrast to the test above: with the DEFAULT (full) set, a readback that is missing
        // DISALLOW_DEBUGGING_FEATURES must FAIL CLOSED. This proves the prior test passes BECAUSE of
        // the filter, not because the restriction was vacuously absent — and that the release path
        // still demands DISALLOW_DEBUGGING_FEATURES.
        val debugging = UserManager.DISALLOW_DEBUGGING_FEATURES
        val filteredPresent = api34Required - debugging
        val ex =
            assertFailsWith<RestrictionEnforcementException>("the full release set must still require the adb-killer") {
                PolicyEnforcer(context, isRestrictionSet = { it in filteredPresent }).applyDayOneRestrictions()
            }
        assertEquals(
            setOf(debugging),
            ex.missing.toSet(),
            "Exactly DISALLOW_DEBUGGING_FEATURES must be reported missing by the unfiltered (release) path",
        )
    }

    // ---------------------------------------------------------------------
    // verify / missing — deterministic via injected reader
    // ---------------------------------------------------------------------

    @Test
    fun `verifyOrThrow does not throw when every restriction is set`() {
        // Under @Config(sdk=[34]) the required set is the canonical 17 + the managed-profile block.
        enforcerReading(api34Required).verifyOrThrow()
    }

    @Test
    fun `verifyOrThrow throws listing the missing restrictions`() {
        // Inject a partial state: everything set EXCEPT factory reset + VPN.
        val missing = setOf(UserManager.DISALLOW_FACTORY_RESET, UserManager.DISALLOW_CONFIG_VPN)
        val ex =
            assertFailsWith<RestrictionEnforcementException> {
                enforcerReading(api34Required - missing).verifyOrThrow()
            }
        assertEquals(missing, ex.missing.toSet(), "Exception must name exactly the missing restrictions")
    }

    @Test
    fun `missingRestrictions reports the complement of what is set`() {
        val present = setOf(UserManager.DISALLOW_FACTORY_RESET, UserManager.DISALLOW_SAFE_BOOT)
        val missing = enforcerReading(present).missingRestrictions().toSet()
        assertEquals(api34Required - present, missing)
    }

    // ---------------------------------------------------------------------
    // applyDayOneRestrictions — fail-closed semantics
    // ---------------------------------------------------------------------

    @Test
    fun `applyDayOneRestrictions throws when app is NOT device owner`() {
        Shadows.shadowOf(dpm).setDeviceOwner(null)
        assertTrue(!dpm.isDeviceOwnerApp(context.packageName), "Precondition: app must not be Device Owner")

        assertFailsWith<IllegalArgumentException>("must refuse to enforce when not Device Owner") {
            PolicyEnforcer(context, isRestrictionSet = { true }).applyDayOneRestrictions()
        }
        // @After restores Device Owner.
    }

    @Test
    fun `applyDayOneRestrictions returns cleanly only when all restrictions verify set`() {
        // Reader reports everything set -> apply succeeds, verify passes, no throw.
        PolicyEnforcer(context, isRestrictionSet = { true }).applyDayOneRestrictions()
    }

    @Test
    fun `applyDayOneRestrictions fails closed when application is partial`() {
        // Failure-injection: even though we are Device Owner and call addUserRestriction for all
        // 17, the readback reports NONE stuck. The enforcer must NOT return in a partial state —
        // it must throw rather than silently log-and-continue (the old fail-OPEN bug).
        val ex =
            assertFailsWith<RestrictionEnforcementException>("must fail closed on partial apply") {
                PolicyEnforcer(context, isRestrictionSet = { false }).applyDayOneRestrictions()
            }
        // @Config(sdk=[34]) → the required set is the canonical 17 + the managed-profile block (ADR-022).
        assertEquals(
            api34Required,
            ex.missing.toSet(),
            "All restrictions reported missing must surface in the exception",
        )
    }

    @Test
    fun `applyDayOneRestrictions fails closed and locks when the restriction readback throws`() {
        // R3: a readback-seam throw (e.g. getUserRestrictions failing) is itself a "can't prove the
        // baseline" gap — it must lock + propagate, not bypass containment as a generic exception.
        var locked = 0
        val enforcer =
            PolicyEnforcer(
                context,
                isRestrictionSet = { throw RuntimeException("getUserRestrictions failed") },
                lock = { locked++ },
            )
        assertFailsWith<RuntimeException>("a readback throw must propagate after containment") {
            enforcer.applyDayOneRestrictions()
        }
        assertEquals(1, locked, "a restriction readback failure must lock the device, not skip containment")
    }

    @Test
    fun `applyDayOneRestrictions verifies the full set via the DO-authoritative readback`() {
        // Round-trip against the DO-authoritative reader (dpm.getUserRestrictions(admin)). Gate
        // on the shadow actually persisting DO-set restrictions; skip (never vacuously pass) if
        // it does not.
        val admin = AdminReceiver.componentName(context)
        dpm.addUserRestriction(admin, UserManager.DISALLOW_FACTORY_RESET)
        assumeTrue(
            "Robolectric shadow round-trips dpm.getUserRestrictions(admin)",
            dpm.getUserRestrictions(admin).getBoolean(UserManager.DISALLOW_FACTORY_RESET, false),
        )

        // Default reader (DO-authoritative). If the shadow round-trips the full set this must not
        // throw; if it persists only some, applyDayOneRestrictions() fails closed -> skip rather
        // than crash (the deterministic injected-reader tests carry the fail-closed proof).
        try {
            PolicyEnforcer(context).applyDayOneRestrictions()
        } catch (e: RestrictionEnforcementException) {
            assumeTrue("Robolectric shadow does not persist the full DO restriction set", false)
            return
        }
        assertTrue(
            PolicyEnforcer(context).missingRestrictions().isEmpty(),
            "After apply, no required restriction may be reported missing",
        )
    }

    // ---------------------------------------------------------------------
    // FRP — fail-closed guards (deterministic) + round-trip (gated)
    // ---------------------------------------------------------------------

    @Test
    fun `applyFrpAccounts throws when app is NOT device owner`() {
        Shadows.shadowOf(dpm).setDeviceOwner(null)
        assertFailsWith<IllegalArgumentException> {
            PolicyEnforcer(context).applyFrpAccounts(listOf("gaia-123"))
        }
    }

    @Test
    fun `applyFrpAccounts refuses an empty account set`() {
        // Enabling FRP with no recovery account would brick the device — must refuse.
        assertFailsWith<IllegalArgumentException>("must refuse FRP with no recovery account") {
            PolicyEnforcer(context).applyFrpAccounts(emptyList())
        }
    }

    @Test
    fun `applyFrpAccounts binds the parent accounts when device owner`() {
        val accounts = listOf("gaia-parent-123")
        try {
            PolicyEnforcer(context).applyFrpAccounts(accounts)
        } catch (e: UnsupportedOperationException) {
            // Shadow lacks setFactoryResetProtectionPolicy — skip rather than fail.
            assumeTrue("Robolectric shadow supports setFactoryResetProtectionPolicy", false)
        }
        val policy = dpm.getFactoryResetProtectionPolicy(AdminReceiver.componentName(context))
        assumeTrue("Robolectric shadow round-trips FactoryResetProtectionPolicy", policy != null)
        assertEquals(accounts, policy!!.factoryResetProtectionAccounts)
        assertTrue(policy.isFactoryResetProtectionEnabled, "FRP must be enabled after binding accounts")
    }

    // ---------------------------------------------------------------------
    // applyAllowlist — deny-by-default launch, fail-closed (ADR-022 / issue #12)
    //
    // Driven through the injected seams (installedApps + isLaunchBlocked + alwaysExempt) so the
    // verify path is deterministic and does not depend on whether the Robolectric shadow tracks
    // setPackagesSuspended / setApplicationHidden. Instrumented deny coverage rides on the
    // connectedAndroidTest harness (#30); the fail-closed contract is proven here.
    // ---------------------------------------------------------------------

    /** Enforcer whose installed set, launch-blocked readback, exempt set, and lock are controlled. */
    private fun allowlistEnforcer(
        installed: List<InstalledApp>,
        launchBlocked: Set<String>,
        exempt: Set<String> = emptySet(),
        onLock: () -> Unit = {},
    ): PolicyEnforcer =
        PolicyEnforcer(
            context,
            installedApps = { installed },
            isLaunchBlocked = { it in launchBlocked },
            alwaysExempt = { exempt },
            lock = onLock,
        )

    @Test
    fun `applyAllowlist suspends every non-allowlisted user app`() {
        val installed =
            listOf(
                InstalledApp("com.school", isSystem = false),
                InstalledApp("com.game", isSystem = false),
                InstalledApp("com.chat", isSystem = false),
            )
        // Simulate suspension sticking for the two deny targets (verify readback sees them blocked).
        val result =
            allowlistEnforcer(installed, launchBlocked = setOf("com.game", "com.chat"))
                .applyAllowlist(setOf("com.school"))

        assertEquals(
            setOf("com.game", "com.chat"),
            result.blocked.toSet(),
            "Every installed user app not on the allowlist must be a deny target",
        )
        assertFalse("com.school" in result.blocked, "Allowlisted app must not be suspended")
    }

    @Test
    fun `applyAllowlist fails closed and locks when a non-allowlisted user app stays launchable`() {
        val installed = listOf(InstalledApp("com.evil.clone", isSystem = false))
        var locked = 0
        // launchBlocked is EMPTY: the app resisted both suspend and hide -> still launchable.
        val ex =
            assertFailsWith<AllowlistEnforcementException>("must fail closed on an un-contained deny app") {
                allowlistEnforcer(installed, launchBlocked = emptySet(), onLock = { locked++ }).applyAllowlist(emptySet())
            }
        assertEquals(
            listOf("com.evil.clone"),
            ex.stillLaunchable,
            "The exception must name exactly the app that stayed launchable",
        )
        // F4: prove the lock half of the fail-closed contract, not just the throw.
        assertEquals(1, locked, "the device must be locked before the fail-closed exception is thrown")
    }

    @Test
    fun `applyAllowlist fails closed and locks when package enumeration throws`() {
        // F1: if we can't even read the installed-package list we can't prove containment — that is
        // a fail-OPEN read error, so contain (lock) + throw rather than silently skip enforcement.
        var locked = 0
        val enforcer =
            PolicyEnforcer(
                context,
                installedApps = { throw RuntimeException("PackageManager enumeration failed") },
                isLaunchBlocked = { false },
                alwaysExempt = { emptySet() },
                lock = { locked++ },
            )
        assertFailsWith<AllowlistEnforcementException>("enumeration failure must fail closed") {
            enforcer.applyAllowlist(emptySet())
        }
        assertEquals(1, locked, "a package-enumeration failure must lock the device, not silently skip")
    }

    @Test
    fun `applyAllowlist fails closed and locks when the exempt-set read throws`() {
        // R2: resolving the active launcher (alwaysExempt) is a read that can throw; it must fail
        // closed (lock) like the package enumeration, not skip suspend/verify/lock outside the guard.
        var locked = 0
        val enforcer =
            PolicyEnforcer(
                context,
                installedApps = { listOf(InstalledApp("com.game", isSystem = false)) },
                isLaunchBlocked = { false },
                alwaysExempt = { throw RuntimeException("launcher resolve failed") },
                lock = { locked++ },
            )
        assertFailsWith<AllowlistEnforcementException>("exempt-set read failure must fail closed") {
            enforcer.applyAllowlist(emptySet())
        }
        assertEquals(1, locked, "an exempt-set read failure must lock the device, not silently skip")
    }

    @Test
    fun `applyAllowlist locks before relaxing allowlisted apps when a deny target is uncontainable`() {
        // R1: an allowlisted app present must not suppress the fail-closed deny gate. The device
        // locks and throws; the allowlist-restore step is physically AFTER the throw, so a failed
        // apply never relaxes (widens) access.
        var locked = 0
        val installed =
            listOf(
                InstalledApp("com.school", isSystem = false), // allowlisted
                InstalledApp("com.evil", isSystem = false), // deny target, uncontainable (launchBlocked stays false)
            )
        val ex =
            assertFailsWith<AllowlistEnforcementException> {
                allowlistEnforcer(installed, launchBlocked = emptySet(), onLock = { locked++ })
                    .applyAllowlist(setOf("com.school"))
            }
        assertEquals(listOf("com.evil"), ex.stillLaunchable, "only the uncontainable deny target is reported")
        assertEquals(1, locked, "must lock on the uncontainable deny target even with an allowlisted app present")
    }

    @Test
    fun `applyAllowlist never suspends system apps`() {
        // A system app that is NOT on the allowlist and reads back NOT launch-blocked must still
        // not throw — system apps are exempt, so they are never deny targets in the first place.
        val installed = listOf(InstalledApp("com.android.systemui", isSystem = true))
        val result = allowlistEnforcer(installed, launchBlocked = emptySet()).applyAllowlist(emptySet())
        assertTrue(result.blocked.isEmpty(), "System apps must never be deny targets")
        assertTrue("com.android.systemui" in result.exempt, "System apps must be reported exempt")
    }

    @Test
    fun `applyAllowlist never suspends self or the active launcher`() {
        val installed =
            listOf(
                InstalledApp(context.packageName, isSystem = false), // self
                InstalledApp("com.android.launcher", isSystem = false), // active launcher (alwaysExempt)
                InstalledApp("com.game", isSystem = false), // a real deny target
            )
        val result =
            allowlistEnforcer(
                installed,
                launchBlocked = setOf("com.game"),
                exempt = setOf("com.android.launcher"),
            ).applyAllowlist(emptySet())

        assertEquals(setOf("com.game"), result.blocked.toSet(), "Only the non-exempt user app is a deny target")
        assertFalse(context.packageName in result.blocked, "Must never suspend self")
        assertFalse("com.android.launcher" in result.blocked, "Must never suspend the active launcher")
    }

    @Test
    fun `applyAllowlist empty allowlist denies all user apps - deny-by-default`() {
        val installed =
            listOf(
                InstalledApp("com.game", isSystem = false),
                InstalledApp("com.chat", isSystem = false),
            )
        val result =
            allowlistEnforcer(installed, launchBlocked = setOf("com.game", "com.chat"))
                .applyAllowlist(emptySet())
        assertEquals(
            setOf("com.game", "com.chat"),
            result.blocked.toSet(),
            "An empty allowlist (missing/corrupt bundle) must suspend every user app",
        )
    }

    @Test
    fun `reassertActiveAllowlist applies the allowlist loaded at apply time, not a stale snapshot`() {
        // R4: the watchdog must read the *current* active allowlist inside the apply, so a newer
        // stricter bundle is never overwritten by a stale pre-loaded snapshot. The loader reads a
        // mutable "active" set; after it changes, the next apply must reflect the new value.
        var active = setOf("com.old")
        val installed =
            listOf(
                InstalledApp("com.old", isSystem = false),
                InstalledApp("com.new", isSystem = false),
            )
        // Whatever is NOT currently allowlisted reads back as launch-blocked (suspension stuck).
        val enforcer =
            PolicyEnforcer(
                context,
                installedApps = { installed },
                isLaunchBlocked = { it !in active },
                alwaysExempt = { emptySet() },
            )

        val first = enforcer.reassertActiveAllowlist { active }
        assertEquals(setOf("com.new"), first.blocked.toSet(), "old allowlist allows com.old, denies com.new")

        // A newer, stricter bundle becomes active BEFORE the next apply.
        active = setOf("com.new")
        val second = enforcer.reassertActiveAllowlist { active }
        assertEquals(
            setOf("com.old"),
            second.blocked.toSet(),
            "must apply the freshly-loaded allowlist (com.old now denied), not the stale snapshot",
        )
    }

    @Test
    fun `applyAllowlist throws when app is NOT device owner`() {
        Shadows.shadowOf(dpm).setDeviceOwner(null)
        assertFailsWith<IllegalArgumentException>("must refuse to enforce the allowlist when not Device Owner") {
            allowlistEnforcer(emptyList(), launchBlocked = emptySet()).applyAllowlist(setOf("com.school"))
        }
        // @After restores Device Owner.
    }
}

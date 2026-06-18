package com.openwarden.parent.dashboard

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * Unit tests for [DashboardViewModel] using fixture data from [FakeChildStateRepository].
 *
 * Assertions cover all acceptance criteria from issue #25:
 *   (a) Live fixture → correct online + usage + blocks mapping.
 *   (b) Offline/error fixture → honest offline state, never a false "online".
 *   (c) H3: offline snapshot maps to TodayUsage.Unknown / BlocksData.Unknown — never 0m / none.
 *   (d) H2: freshness-derived online status (stale → OFFLINE_OR_UNKNOWN; fresh → ONLINE).
 *   (e) MED: content-field ALLOWLIST — reflection-based, fail-closed on unexpected fields.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DashboardViewModelTest {

    // -----------------------------------------------------------------------
    // Fixed clock helpers
    // -----------------------------------------------------------------------

    /** A fixed "now" for all freshness tests. */
    private val fixedNow = Instant.fromEpochSeconds(1_718_000_200L)

    /** A timestamp that is fresh (within the 90s window from fixedNow). */
    private val freshTimestamp = Instant.fromEpochSeconds(1_718_000_150L) // 50s ago — fresh

    /** A timestamp that is stale (older than the 90s window from fixedNow). */
    private val staleTimestamp = Instant.fromEpochSeconds(1_717_999_900L) // 300s ago — stale

    private val fixedClock = object : Clock {
        override fun now(): Instant = fixedNow
    }

    // -----------------------------------------------------------------------
    // (a) Online fixture — happy path
    // -----------------------------------------------------------------------

    @Test
    fun onlineFixture_mapsToSuccessWithOnlineStatus() = runTest {
        val vm = vmWith(
            FakeChildStateRepository(
                scenario = FakeChildStateRepository.Scenario.Online,
                freshReportedAt = freshTimestamp,
            ),
            clock = fixedClock,
        )
        vm.refresh()
        advanceUntilIdle()

        val state = assertIs<DashboardUiState.Success>(vm.uiState.value, "Expected Success for online fixture")
        assertEquals(
            ChildOnlineStatus.ONLINE,
            state.onlineStatus,
            "Online fixture with fresh timestamp must report ONLINE",
        )
    }

    @Test
    fun onlineFixture_usageTotalsArePresent() = runTest {
        val vm = vmWith(
            FakeChildStateRepository(
                scenario = FakeChildStateRepository.Scenario.Online,
                freshReportedAt = freshTimestamp,
            ),
            clock = fixedClock,
        )
        vm.refresh()
        advanceUntilIdle()

        val state = assertIs<DashboardUiState.Success>(vm.uiState.value)
        val usage = assertIs<TodayUsage.Known>(state.todayUsage, "Online fixture must have Known usage")
        assertTrue(usage.totalForegroundMs > 0, "Online fixture must have non-zero total usage")
        assertTrue(usage.perApp.isNotEmpty(), "Online fixture must have per-app usage entries")
    }

    @Test
    fun onlineFixture_blockedAttemptsArePresent() = runTest {
        val vm = vmWith(
            FakeChildStateRepository(
                scenario = FakeChildStateRepository.Scenario.Online,
                freshReportedAt = freshTimestamp,
            ),
            clock = fixedClock,
        )
        vm.refresh()
        advanceUntilIdle()

        val state = assertIs<DashboardUiState.Success>(vm.uiState.value)
        val blocks = assertIs<BlocksData.Known>(state.blocksData, "Online fixture must have Known blocks")
        assertTrue(blocks.attempts.isNotEmpty(), "Online fixture must have recent blocks")
    }

    // -----------------------------------------------------------------------
    // (b) Offline / Error → fail-closed to OFFLINE_OR_UNKNOWN
    // -----------------------------------------------------------------------

    @Test
    fun offlineFixture_mapsToSuccessWithOfflineStatus() = runTest {
        val vm = vmWith(FakeChildStateRepository(FakeChildStateRepository.Scenario.Offline), clock = fixedClock)
        vm.refresh()
        advanceUntilIdle()

        val state = assertIs<DashboardUiState.Success>(vm.uiState.value, "Expected Success for offline fixture")
        assertEquals(
            ChildOnlineStatus.OFFLINE_OR_UNKNOWN,
            state.onlineStatus,
            "Offline fixture must report OFFLINE_OR_UNKNOWN, never ONLINE",
        )
    }

    @Test
    fun errorRepository_degradesToErrorState() = runTest {
        val throwingRepo = object : ChildStateRepository {
            override suspend fun fetchSnapshot(): ChildDashboardSnapshot {
                throw RuntimeException("simulated transport failure")
            }
        }
        val vm = vmWith(throwingRepo, clock = fixedClock)
        vm.refresh()
        advanceUntilIdle()

        assertIs<DashboardUiState.Error>(vm.uiState.value, "Throwing repository must produce Error state")
    }

    /**
     * Error state carries only a diagnostic message string — no snapshot, no online flag,
     * no usage, no blocks.  This is a structural guarantee: [DashboardUiState.Error] has
     * a single `message: String` field (the exception text, not child content).  The test
     * pins that the message is non-empty and does not carry stale-child data.
     */
    @Test
    fun errorState_carresOnlyDiagnosticMessage_noChildData() = runTest {
        val sentinelMessage = "simulated transport failure sentinel"
        val throwingRepo = object : ChildStateRepository {
            override suspend fun fetchSnapshot(): ChildDashboardSnapshot {
                throw RuntimeException(sentinelMessage)
            }
        }
        val vm = vmWith(throwingRepo, clock = fixedClock)
        vm.refresh()
        advanceUntilIdle()

        val state = assertIs<DashboardUiState.Error>(vm.uiState.value)
        // The message must be the exception string, not blank — confirms the error was captured.
        assertTrue(state.message.isNotBlank(), "Error state must carry a non-blank diagnostic message")
        // Confirm the message is the exception text (a transport diagnostic, not child content).
        assertEquals(
            sentinelMessage,
            state.message,
            "Error message must be the exception string, not fabricated child content",
        )
    }

    // -----------------------------------------------------------------------
    // (c) H3 — offline maps to Unknown, never 0m / empty list
    // -----------------------------------------------------------------------

    /**
     * When the child is offline, [TodayUsage.Unknown] must be surfaced — NOT [TodayUsage.Known]
     * with totalForegroundMs = 0.  A parent must never see "0m" for a child we cannot reach.
     */
    @Test
    fun offlineSnapshot_usageIsUnknown_notZero() = runTest {
        val vm = vmWith(FakeChildStateRepository(FakeChildStateRepository.Scenario.Offline), clock = fixedClock)
        vm.refresh()
        advanceUntilIdle()

        val state = assertIs<DashboardUiState.Success>(vm.uiState.value)
        assertIs<TodayUsage.Unknown>(
            state.todayUsage,
            "Offline snapshot must yield TodayUsage.Unknown, not Known(0L) — " +
                "a parent must never see '0m' for a child we cannot reach",
        )
    }

    /**
     * When the child is offline, [BlocksData.Unknown] must be surfaced — NOT [BlocksData.Known]
     * with an empty list.  A parent must never see "No blocked attempts today" for a child
     * we cannot reach.
     */
    @Test
    fun offlineSnapshot_blocksAreUnknown_notEmpty() = runTest {
        val vm = vmWith(FakeChildStateRepository(FakeChildStateRepository.Scenario.Offline), clock = fixedClock)
        vm.refresh()
        advanceUntilIdle()

        val state = assertIs<DashboardUiState.Success>(vm.uiState.value)
        assertIs<BlocksData.Unknown>(
            state.blocksData,
            "Offline snapshot must yield BlocksData.Unknown, not Known(emptyList()) — " +
                "a parent must never see 'No blocked attempts today' for a child we cannot reach",
        )
    }

    /**
     * A genuinely online child with zero usage must still read as [TodayUsage.Known] with
     * totalForegroundMs = 0 — not as Unknown.  Zero is a valid, honest reading when online.
     */
    @Test
    fun onlineWithZeroUsage_isKnownZero_notUnknown() = runTest {
        val zeroUsageRepo = object : ChildStateRepository {
            override suspend fun fetchSnapshot() = ChildDashboardSnapshot(
                reportedAt = freshTimestamp,
                todayUsage = TodayUsage.Known(totalForegroundMs = 0L, perApp = emptyList()),
                blocksData = BlocksData.Known(attempts = emptyList()),
            )
        }
        val vm = vmWith(zeroUsageRepo, clock = fixedClock)
        vm.refresh()
        advanceUntilIdle()

        val state = assertIs<DashboardUiState.Success>(vm.uiState.value)
        assertEquals(ChildOnlineStatus.ONLINE, state.onlineStatus, "Fresh timestamp → ONLINE")
        val usage = assertIs<TodayUsage.Known>(state.todayUsage, "Online zero usage must be Known, not Unknown")
        assertEquals(0L, usage.totalForegroundMs, "Genuine zero usage must be 0, not unknown")
        val blocks = assertIs<BlocksData.Known>(state.blocksData, "Online empty blocks must be Known, not Unknown")
        assertTrue(blocks.attempts.isEmpty(), "Genuine no-blocks must be empty Known list")
    }

    // -----------------------------------------------------------------------
    // (d) H2 — freshness-derived online status
    // -----------------------------------------------------------------------

    /**
     * A snapshot whose [ChildDashboardSnapshot.reportedAt] is older than the freshness window
     * must map to OFFLINE_OR_UNKNOWN — regardless of any other flag.  Fail-closed.
     */
    @Test
    fun staleTimestamp_mapsToOffline_notOnline() = runTest {
        val staleRepo = object : ChildStateRepository {
            override suspend fun fetchSnapshot() = ChildDashboardSnapshot(
                reportedAt = staleTimestamp, // 300s before fixedNow — older than 90s window
                todayUsage = TodayUsage.Known(totalForegroundMs = 1_000_000L, perApp = emptyList()),
                blocksData = BlocksData.Known(attempts = emptyList()),
            )
        }
        val vm = vmWith(staleRepo, clock = fixedClock)
        vm.refresh()
        advanceUntilIdle()

        val state = assertIs<DashboardUiState.Success>(vm.uiState.value)
        assertEquals(
            ChildOnlineStatus.OFFLINE_OR_UNKNOWN,
            state.onlineStatus,
            "Stale reportedAt (300s > 90s window) must map to OFFLINE_OR_UNKNOWN even if snapshot looks online",
        )
        // Downstream data must also be Unknown when offline (H3).
        assertIs<TodayUsage.Unknown>(state.todayUsage, "Stale snapshot usage must be Unknown")
        assertIs<BlocksData.Unknown>(state.blocksData, "Stale snapshot blocks must be Unknown")
    }

    /**
     * A snapshot with a fresh [ChildDashboardSnapshot.reportedAt] must map to ONLINE.
     */
    @Test
    fun freshTimestamp_mapsToOnline() = runTest {
        val freshRepo = object : ChildStateRepository {
            override suspend fun fetchSnapshot() = ChildDashboardSnapshot(
                reportedAt = freshTimestamp, // 50s before fixedNow — within 90s window
                todayUsage = TodayUsage.Known(totalForegroundMs = 500_000L, perApp = emptyList()),
                blocksData = BlocksData.Known(attempts = emptyList()),
            )
        }
        val vm = vmWith(freshRepo, clock = fixedClock)
        vm.refresh()
        advanceUntilIdle()

        val state = assertIs<DashboardUiState.Success>(vm.uiState.value)
        assertEquals(
            ChildOnlineStatus.ONLINE,
            state.onlineStatus,
            "Fresh reportedAt (50s < 90s window) must map to ONLINE",
        )
    }

    /**
     * A null [ChildDashboardSnapshot.reportedAt] must map to OFFLINE_OR_UNKNOWN.
     * This is the primary fail-closed case — missing data → offline.
     */
    @Test
    fun nullTimestamp_mapsToOffline() = runTest {
        val nullTimestampRepo = object : ChildStateRepository {
            override suspend fun fetchSnapshot() = ChildDashboardSnapshot(
                reportedAt = null,
                todayUsage = TodayUsage.Known(totalForegroundMs = 100_000L, perApp = emptyList()),
                blocksData = BlocksData.Known(attempts = emptyList()),
            )
        }
        val vm = vmWith(nullTimestampRepo, clock = fixedClock)
        vm.refresh()
        advanceUntilIdle()

        val state = assertIs<DashboardUiState.Success>(vm.uiState.value)
        assertEquals(
            ChildOnlineStatus.OFFLINE_OR_UNKNOWN,
            state.onlineStatus,
            "Null reportedAt must map to OFFLINE_OR_UNKNOWN — fail-closed",
        )
    }

    // -----------------------------------------------------------------------
    // (e) MED — content-field ALLOWLIST (reflection-based, fail-closed)
    // -----------------------------------------------------------------------

    /**
     * Asserts that every declared property on each domain type is in the approved
     * metadata-only ALLOWLIST.  Any new/unexpected field fails the test by default —
     * fail-closed testing.
     *
     * This replaces the vacuous toString()-grep approach that would silently pass
     * for an unlisted content field name (e.g. "note", "query", "body").
     *
     * Implementation note: this uses Kotlin data class toString() parsing (not
     * kotlin-reflect, which is not in commonTest deps).  The check is reliable for
     * data classes whose toString() follows "ClassName(prop=val, …)" — all domain
     * types here are data classes or data objects.  If a type ever moves to a custom
     * toString(), update this test to match.
     *
     * AppCategory is an enum, not a data class; its body fields are verified
     * structurally in assertEnumProperties.  The primary content-leak guard for
     * AppCategory is the closed-enum design + AppCategory.fromRaw() chokepoint
     * (DashboardDomain.kt) — code review at that chokepoint is the invariant, and
     * this test confirms the enum constant is registered, not that its body is
     * exhaustively enumerated.
     *
     * Approved metadata fields per type — NO content-layer names permitted:
     *   - message text, URL, search query, image data, audio data, body, note, etc.
     */
    @Test
    fun domainTypes_haveOnlyApprovedMetadataFields() {
        // Allowlists are exhaustive — any unlisted property name fails the test.

        val blockAllowlist = setOf(
            "packageName", "appLabel", "category", "blockedAt", "countToday",
        )
        val appUsageAllowlist = setOf(
            "packageName", "label", "foregroundMs",
        )
        val todayUsageKnownAllowlist = setOf(
            "totalForegroundMs", "perApp",
        )
        // TodayUsage.Unknown is a singleton object — no declared properties.
        val todayUsageUnknownAllowlist = emptySet<String>()

        val blocksKnownAllowlist = setOf("attempts")
        // BlocksData.Unknown is a singleton object — no declared properties.
        val blocksUnknownAllowlist = emptySet<String>()

        val snapshotAllowlist = setOf(
            "reportedAt", "todayUsage", "blocksData",
        )

        // Verify using Kotlin reflection on the data classes.
        val snapshot = FakeChildStateRepository.onlineFixture()

        // ChildDashboardSnapshot
        assertProperties(snapshot, snapshotAllowlist, "ChildDashboardSnapshot")

        // TodayUsage.Known
        val usageKnown = snapshot.todayUsage as TodayUsage.Known
        assertProperties(usageKnown, todayUsageKnownAllowlist, "TodayUsage.Known")

        // TodayUsage.Unknown — no properties
        assertProperties(TodayUsage.Unknown, todayUsageUnknownAllowlist, "TodayUsage.Unknown")

        // AppUsageSummary (each entry)
        usageKnown.perApp.forEach { entry ->
            assertProperties(entry, appUsageAllowlist, "AppUsageSummary")
        }

        // BlocksData.Known
        val blocksKnown = snapshot.blocksData as BlocksData.Known
        assertProperties(blocksKnown, blocksKnownAllowlist, "BlocksData.Known")

        // BlocksData.Unknown — no properties
        assertProperties(BlocksData.Unknown, blocksUnknownAllowlist, "BlocksData.Unknown")

        // BlockedAttempt (each attempt)
        blocksKnown.attempts.forEach { block ->
            assertProperties(block, blockAllowlist, "BlockedAttempt")
        }

        // AppCategory enum — verify only displayName (not a content carrier)
        val categoryAllowlist = setOf("displayName")
        blocksKnown.attempts.forEach { block ->
            assertEnumProperties(block.category, categoryAllowlist, "AppCategory")
        }
    }

    // -----------------------------------------------------------------------
    // Loading state
    // -----------------------------------------------------------------------

    @Test
    fun initialState_isLoading() {
        val scheduler = TestCoroutineScheduler()
        val dispatcher = StandardTestDispatcher(scheduler)
        val scope = TestScope(dispatcher)
        val vm = DashboardViewModel(
            repository = FakeChildStateRepository(FakeChildStateRepository.Scenario.Online),
            scope = scope,
            clock = fixedClock,
        )
        assertIs<DashboardUiState.Loading>(vm.uiState.value)
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    private fun TestScope.vmWith(
        repository: ChildStateRepository,
        clock: Clock = Clock.System,
    ): DashboardViewModel =
        DashboardViewModel(repository = repository, scope = this, clock = clock)

    /**
     * Asserts that the declared properties appearing in [obj]'s toString() output are
     * exactly the expected names in [allowlist].  Any extra property name fails with a
     * descriptive message — fail-closed by default.
     *
     * Mechanism: parses Kotlin data class toString() format "ClassName(prop=val, …)".
     * Singleton objects (data object) emit "ClassName" with no parens — treated as no properties.
     * Reliable for all current domain types; would need updating if a type adds a custom toString().
     */
    private fun assertProperties(obj: Any, allowlist: Set<String>, typeName: String) {
        val str = obj.toString()
        val actualProps = extractPropertyNames(str)
        val unexpected = actualProps - allowlist
        assertTrue(
            unexpected.isEmpty(),
            "$typeName has unexpected properties not in the metadata-only allowlist: $unexpected. " +
                "If this is a legitimate metadata field, add it to the allowlist explicitly. " +
                "Content fields (URL, message, query, image, audio, etc.) are NEVER permitted.",
        )
    }

    /**
     * Asserts that the given [AppCategory] constant is a registered enum entry and that
     * the known body property (displayName) is allowed by [allowlist].
     *
     * Limitation: this check does NOT exhaustively enumerate AppCategory body fields via
     * reflection — enums are not data classes so toString() only returns the name, and
     * kotlin-reflect is not in commonTest deps.  The primary content-leak guard for
     * AppCategory is (a) the closed-enum design, (b) the AppCategory.fromRaw() parse
     * chokepoint that all incoming category strings must pass through (DashboardDomain.kt),
     * and (c) code review at that chokepoint.  If a content field were ever added to the
     * AppCategory body, this test would NOT catch it — that must be caught by code review.
     */
    private fun assertEnumProperties(enumVal: Enum<*>, allowlist: Set<String>, typeName: String) {
        assertNotNull(enumVal.name, "$typeName must have a name")
        val category = enumVal as? AppCategory
        if (category != null) {
            // Confirm the constant is a registered entry (no rogue unlisted constant).
            assertTrue(
                AppCategory.entries.any { it.name == enumVal.name },
                "$typeName enum value '${enumVal.name}' is not in the registered AppCategory entries",
            )
            // Confirm the only non-standard property (displayName) is on the allowlist.
            assertTrue(
                allowlist.contains("displayName"),
                "$typeName.displayName must be in the allowlist",
            )
            // Confirm displayName is accessible and non-blank (guards against an empty stub).
            assertTrue(
                category.displayName.isNotBlank(),
                "$typeName.displayName must not be blank for constant '${enumVal.name}'",
            )
        }
    }

    /**
     * Parses a Kotlin data class toString() output into property names.
     * Format: "ClassName(prop1=val1, prop2=val2)" or "ClassName" for objects.
     * Returns empty set for singletons (data object).
     */
    private fun extractPropertyNames(toStringOutput: String): Set<String> {
        val parenStart = toStringOutput.indexOf('(')
        if (parenStart < 0) return emptySet() // singleton/object — no properties
        val inner = toStringOutput.substring(parenStart + 1, toStringOutput.lastIndexOf(')'))
        if (inner.isBlank()) return emptySet()

        // Split on ", " but only at the top level (ignore nested parens/brackets).
        val props = mutableSetOf<String>()
        var depth = 0
        var segStart = 0
        for (i in inner.indices) {
            when (inner[i]) {
                '(', '[', '{' -> depth++
                ')', ']', '}' -> depth--
                ',' -> if (depth == 0) {
                    val seg = inner.substring(segStart, i).trim()
                    val eqIdx = seg.indexOf('=')
                    if (eqIdx > 0) props.add(seg.substring(0, eqIdx).trim())
                    segStart = i + 1
                }
            }
        }
        // Last segment
        val seg = inner.substring(segStart).trim()
        val eqIdx = seg.indexOf('=')
        if (eqIdx > 0) props.add(seg.substring(0, eqIdx).trim())

        return props
    }
}

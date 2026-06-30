package com.openwarden.child

import android.content.pm.ApplicationInfo
import org.junit.Test
import kotlin.test.assertEquals

/**
 * Unit tests for [InstalledAppsHelper.categoryToken].
 *
 * [categoryToken] is a pure function (no Android context, no Robolectric) so every branch is
 * covered here as a plain JVM test. The table mirrors the exact mapping specified in the
 * /apps endpoint design: each Android [ApplicationInfo] CATEGORY_* constant maps to the
 * UPPERCASE token string used in the wire shape and expected by the parent's AppCategory enum.
 */
class InstalledAppsHelperCategoryTest {
    // Table-driven: Pair(androidCategory int, expected token string)
    private val mappings =
        listOf(
            ApplicationInfo.CATEGORY_GAME to "GAMING",
            ApplicationInfo.CATEGORY_AUDIO to "ENTERTAINMENT",
            ApplicationInfo.CATEGORY_VIDEO to "ENTERTAINMENT",
            ApplicationInfo.CATEGORY_IMAGE to "ENTERTAINMENT",
            ApplicationInfo.CATEGORY_SOCIAL to "SOCIAL",
            ApplicationInfo.CATEGORY_NEWS to "NEWS",
            ApplicationInfo.CATEGORY_MAPS to "UTILITIES",
            ApplicationInfo.CATEGORY_PRODUCTIVITY to "PRODUCTIVITY",
            ApplicationInfo.CATEGORY_ACCESSIBILITY to "UTILITIES",
            ApplicationInfo.CATEGORY_UNDEFINED to "UNKNOWN",
        )

    @Test
    fun `categoryToken maps every defined Android category constant to the correct token`() {
        for ((androidCategory, expectedToken) in mappings) {
            val actual = InstalledAppsHelper.categoryToken(androidCategory)
            assertEquals(
                expectedToken,
                actual,
                "categoryToken($androidCategory) expected \"$expectedToken\" but got \"$actual\"",
            )
        }
    }

    @Test
    fun `categoryToken maps CATEGORY_UNDEFINED (-1) to UNKNOWN`() {
        assertEquals("UNKNOWN", InstalledAppsHelper.categoryToken(ApplicationInfo.CATEGORY_UNDEFINED))
    }

    @Test
    fun `categoryToken maps any other unknown int to UNKNOWN`() {
        // Future Android versions may add new CATEGORY_* constants. Any unrecognised value must
        // fall through to UNKNOWN, never throw or silently produce a wrong token.
        listOf(-99, 9, 100, Int.MAX_VALUE, Int.MIN_VALUE).forEach { unknown ->
            assertEquals(
                "UNKNOWN",
                InstalledAppsHelper.categoryToken(unknown),
                "Unrecognised category $unknown must map to UNKNOWN",
            )
        }
    }

    @Test
    fun `categoryToken GAMING — CATEGORY_GAME (0)`() {
        assertEquals("GAMING", InstalledAppsHelper.categoryToken(ApplicationInfo.CATEGORY_GAME))
    }

    @Test
    fun `categoryToken ENTERTAINMENT — CATEGORY_AUDIO (1)`() {
        assertEquals("ENTERTAINMENT", InstalledAppsHelper.categoryToken(ApplicationInfo.CATEGORY_AUDIO))
    }

    @Test
    fun `categoryToken ENTERTAINMENT — CATEGORY_VIDEO (2)`() {
        assertEquals("ENTERTAINMENT", InstalledAppsHelper.categoryToken(ApplicationInfo.CATEGORY_VIDEO))
    }

    @Test
    fun `categoryToken ENTERTAINMENT — CATEGORY_IMAGE (3)`() {
        assertEquals("ENTERTAINMENT", InstalledAppsHelper.categoryToken(ApplicationInfo.CATEGORY_IMAGE))
    }

    @Test
    fun `categoryToken SOCIAL — CATEGORY_SOCIAL (4)`() {
        assertEquals("SOCIAL", InstalledAppsHelper.categoryToken(ApplicationInfo.CATEGORY_SOCIAL))
    }

    @Test
    fun `categoryToken NEWS — CATEGORY_NEWS (5)`() {
        assertEquals("NEWS", InstalledAppsHelper.categoryToken(ApplicationInfo.CATEGORY_NEWS))
    }

    @Test
    fun `categoryToken UTILITIES — CATEGORY_MAPS (6)`() {
        assertEquals("UTILITIES", InstalledAppsHelper.categoryToken(ApplicationInfo.CATEGORY_MAPS))
    }

    @Test
    fun `categoryToken PRODUCTIVITY — CATEGORY_PRODUCTIVITY (7)`() {
        assertEquals("PRODUCTIVITY", InstalledAppsHelper.categoryToken(ApplicationInfo.CATEGORY_PRODUCTIVITY))
    }

    @Test
    fun `categoryToken UTILITIES — CATEGORY_ACCESSIBILITY (8)`() {
        assertEquals("UTILITIES", InstalledAppsHelper.categoryToken(ApplicationInfo.CATEGORY_ACCESSIBILITY))
    }
}

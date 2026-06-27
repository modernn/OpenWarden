package com.openwarden.child

import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertTrue

/**
 * TX1 guard: the transparency screen must NOT omit any monitored category.
 *
 * These tests verify:
 *  1. [MonitoredCategory] is non-empty (there must be at least one disclosed signal).
 *  2. The DNS/web-query category is present by name (the specific omission TX1 guards against).
 *  3. Every [MonitoredCategory.title] is rendered somewhere in the view tree of
 *     [TransparencyActivity]. Adding a new enum entry without rendering it will
 *     cause test 3 to fail.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TransparencyTest {
    /** Collect all text strings rendered in a view hierarchy (recursive). */
    private fun collectText(root: View): List<String> {
        val texts = mutableListOf<String>()
        if (root is TextView) {
            val t = root.text?.toString()
            if (!t.isNullOrBlank()) texts.add(t)
        }
        if (root is ViewGroup) {
            for (i in 0 until root.childCount) {
                texts += collectText(root.getChildAt(i))
            }
        }
        return texts
    }

    @Test
    fun enumIsNonEmpty() {
        assertTrue(
            MonitoredCategory.values().isNotEmpty(),
            "MonitoredCategory must declare at least one monitored signal",
        )
    }

    @Test
    fun dnsWebQueryCategoryExists() {
        // TX1: DNS/web-query log must be explicitly listed. This is the category
        // most likely to be forgotten because it is inferred from permissions
        // rather than a user-visible feature.
        val dnsEntry =
            MonitoredCategory.values().find { category ->
                category.title.contains("website", ignoreCase = true) ||
                    category.title.contains("web", ignoreCase = true) ||
                    category.title.contains("dns", ignoreCase = true) ||
                    category.name == "DNS_WEB_QUERIES"
            }
        assertTrue(
            dnsEntry != null,
            "MonitoredCategory must include a DNS/web-query entry. " +
                "The app intercepts DNS lookups; the transparency screen must say so.",
        )
    }

    @Test
    fun allCategoryTitlesRenderedInActivity() {
        // Build the activity through the full onCreate lifecycle.
        val activity =
            Robolectric
                .buildActivity(TransparencyActivity::class.java)
                .setup()
                .get()

        val rootView = activity.window.decorView
        val renderedTexts = collectText(rootView)

        // Every enum title must appear verbatim in the rendered view tree.
        for (category in MonitoredCategory.values()) {
            val found = renderedTexts.any { it.contains(category.title, ignoreCase = false) }
            assertTrue(
                found,
                "TransparencyActivity is missing a row for MonitoredCategory.${category.name} " +
                    "(expected title: \"${category.title}\"). " +
                    "Every monitored category must be disclosed to the child.",
            )
        }
    }

    @Test
    fun activityHeaderTextRendered() {
        val activity =
            Robolectric
                .buildActivity(TransparencyActivity::class.java)
                .setup()
                .get()

        val rootView = activity.window.decorView
        val renderedTexts = collectText(rootView)

        assertTrue(
            renderedTexts.any { it.contains("OpenWarden", ignoreCase = true) },
            "TransparencyActivity must render a header identifying OpenWarden",
        )
    }
}

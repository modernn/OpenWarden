package com.openwarden.child

import android.os.Bundle
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.ComponentActivity

/**
 * Kid Transparency screen — "What does OpenWarden see?"
 *
 * This screen is required to be honest and complete. It is driven entirely by
 * [MonitoredCategory.values()] — the single source of truth for monitored signals.
 * Adding a new signal to [MonitoredCategory] automatically makes it appear here;
 * removing or skipping an entry would fail [TransparencyTest].
 *
 * Design goals (per docs/KID_TRANSPARENCY.md):
 *  - A 9-to-12-year-old can understand every item.
 *  - No surveillance verbs ("watch", "track", "monitor"). Use "sees", "counts", "checks".
 *  - Matter-of-fact tone. Not punitive, not marketing.
 */
class TransparencyActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = ScrollView(this)
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 72, 48, 72)
        }

        // ── Header ─────────────────────────────────────────────────────────────
        column.addView(
            TextView(this).apply {
                text = "What does OpenWarden see?"
                textSize = 24f
                setPadding(0, 0, 0, 8)
            },
        )

        column.addView(
            TextView(this).apply {
                text = "OpenWarden helps keep your phone simple. Here is exactly what it does."
                textSize = 15f
                setPadding(0, 0, 0, 40)
            },
        )

        // ── One row per monitored category ─────────────────────────────────────
        MonitoredCategory.values().forEach { category ->
            column.addView(buildCategoryRow(category))
        }

        root.addView(column)
        setContentView(root)
    }

    /**
     * Builds a single disclosure row for [category].
     * The row contains the [MonitoredCategory.title] as a bold label and
     * [MonitoredCategory.plainLanguage] as explanatory body text.
     *
     * Both text values carry the category name as a content description so
     * TalkBack reads them correctly (accessibility requirement from §11).
     */
    private fun buildCategoryRow(category: MonitoredCategory): LinearLayout {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 0, 0, 32)
        }

        val titleView = TextView(this).apply {
            text = category.title
            textSize = 17f
            contentDescription = category.title
            // Minimum tap target ≥ 48dp per §11.
            minHeight = (48 * resources.displayMetrics.density).toInt()
        }

        val bodyView = TextView(this).apply {
            text = category.plainLanguage
            textSize = 14f
            contentDescription = category.plainLanguage
            setPadding(0, 4, 0, 0)
        }

        row.addView(titleView)
        row.addView(bodyView)
        return row
    }
}

package com.openwarden.parent.android.ui.dashboard

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.openwarden.parent.dashboard.AppUsageSummary
import com.openwarden.parent.dashboard.BlockedAttempt
import com.openwarden.parent.dashboard.ChildOnlineStatus
import com.openwarden.parent.dashboard.ChildDashboardSnapshot
import com.openwarden.parent.dashboard.DashboardUiState
import com.openwarden.parent.dashboard.TodayUsage
import kotlinx.datetime.Instant

// ---------------------------------------------------------------------------
// Entry point
// ---------------------------------------------------------------------------

/**
 * Parent dashboard screen.
 *
 * Non-negotiables enforced here:
 *  - Only metadata is shown: online/offline badge, total usage time, blocked
 *    attempt metadata (app name / category / timestamp / count).
 *  - No message text, photo, URL content, or any content-layer field is
 *    rendered — the domain types do not carry such fields.
 *  - Fail-closed: Loading / Error states → child displayed as offline/unknown,
 *    never optimistically online.
 */
@Composable
fun DashboardScreen(
    viewModel: DashboardAndroidViewModel,
    modifier: Modifier = Modifier,
) {
    val uiState by viewModel.uiState.collectAsState()

    // Kick off the first load on composition.
    LaunchedEffect(Unit) { viewModel.refresh() }

    Scaffold(modifier = modifier) { innerPadding ->
        Surface(modifier = Modifier.fillMaxSize()) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
            ) {
                // Fixed title bar — always visible.
                DashboardTitleBar(
                    onRefresh = { viewModel.refresh() },
                )
                when (val s = uiState) {
                    is DashboardUiState.Loading -> LoadingState()
                    is DashboardUiState.Error -> ErrorState(s.message)
                    is DashboardUiState.Success -> SuccessContent(s.snapshot)
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Title / action bar
// ---------------------------------------------------------------------------

@Composable
private fun DashboardTitleBar(onRefresh: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "OpenWarden",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
        )
        Button(onClick = onRefresh) {
            Text("Refresh")
        }
    }
}

// ---------------------------------------------------------------------------
// State-level composables
// ---------------------------------------------------------------------------

@Composable
private fun LoadingState() {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .semantics { contentDescription = "Loading dashboard" },
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        CircularProgressIndicator()
        Spacer(modifier = Modifier.height(12.dp))
        Text(
            text = "Loading...",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * Error state.
 * Fail-closed: child is displayed as offline/unknown, never as online.
 * The error message is shown for diagnostic purposes.
 */
@Composable
private fun ErrorState(message: String) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
            .semantics { contentDescription = "Child offline or unknown — error loading data" },
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        OnlineBadge(status = ChildOnlineStatus.OFFLINE_OR_UNKNOWN)
        Spacer(modifier = Modifier.height(16.dp))
        Card(
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.errorContainer,
            ),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(
                text = "Could not reach child device: $message",
                modifier = Modifier.padding(12.dp),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onErrorContainer,
            )
        }
    }
}

// ---------------------------------------------------------------------------
// Success content
// ---------------------------------------------------------------------------

@Composable
private fun SuccessContent(snapshot: ChildDashboardSnapshot) {
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item { Spacer(modifier = Modifier.height(4.dp)) }

        // ---- Online / offline badge ----
        item { OnlineBadge(snapshot.onlineStatus) }

        item { HorizontalDivider() }

        // ---- Today's usage ----
        item {
            Text(
                "Today's Usage",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
        }
        item { UsageSummaryCard(snapshot.todayUsage) }

        item { HorizontalDivider() }

        // ---- Recent blocked attempts ----
        item {
            Text(
                "Recent Blocked Attempts",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
        }
        if (snapshot.recentBlocks.isEmpty()) {
            item {
                Text(
                    "No blocked attempts today.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            items(snapshot.recentBlocks) { block ->
                BlockedAttemptRow(block)
            }
        }

        item { Spacer(modifier = Modifier.height(24.dp)) }
    }
}

// ---------------------------------------------------------------------------
// Online / offline badge
// ---------------------------------------------------------------------------

@Composable
private fun OnlineBadge(status: ChildOnlineStatus) {
    val isOnline = status == ChildOnlineStatus.ONLINE
    val label = if (isOnline) "Child online" else "Child offline or unknown"
    val tint = if (isOnline) Color(0xFF2E7D32) else MaterialTheme.colorScheme.onSurfaceVariant

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .semantics { contentDescription = label },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        // Dot indicator — color-blind-safe: paired with text label (TESTING.md §11)
        Text(
            text = if (isOnline) "●" else "○", // filled / hollow circle
            style = MaterialTheme.typography.bodyLarge,
            color = tint,
        )
        Text(
            text = label,
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = FontWeight.Medium,
            color = tint,
        )
    }
}

// ---------------------------------------------------------------------------
// Today's usage card
// ---------------------------------------------------------------------------

@Composable
private fun UsageSummaryCard(usage: TodayUsage) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
        ),
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            // Total
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    "Total screen time today",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    formatDuration(usage.totalForegroundMs),
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                )
            }

            if (usage.perApp.isNotEmpty()) {
                HorizontalDivider()
                usage.perApp.forEach { entry ->
                    AppUsageRow(entry)
                }
            } else {
                Text(
                    "No usage data yet.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun AppUsageRow(entry: AppUsageSummary) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            // Show label if available; fall back to packageName (still metadata, not content).
            text = entry.label.ifBlank { entry.packageName },
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = formatDuration(entry.foregroundMs),
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.Medium,
        )
    }
}

// ---------------------------------------------------------------------------
// Blocked attempts
// ---------------------------------------------------------------------------

@Composable
private fun BlockedAttemptRow(block: BlockedAttempt) {
    // Metadata only: app label/package, category, timestamp, count.
    // No URL, no message content, no search query rendered here or carried
    // by the BlockedAttempt domain type.
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
        ),
    ) {
        Column(
            modifier = Modifier.padding(10.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = block.appLabel ?: block.packageName,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = formatInstant(block.blockedAt),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            val category = block.category
            if (category != null) {
                Text(
                    text = category,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                text = "${block.countToday}x today",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

// ---------------------------------------------------------------------------
// Formatting helpers
// ---------------------------------------------------------------------------

/** Formats milliseconds into "Xh Ym" or "Zm" string (no content, pure metadata). */
internal fun formatDuration(ms: Long): String {
    val totalSeconds = ms / 1000L
    val hours = totalSeconds / 3600L
    val minutes = (totalSeconds % 3600L) / 60L
    return when {
        hours > 0 -> "${hours}h ${minutes}m"
        else -> "${minutes}m"
    }
}

/** Formats an [Instant] to a short HH:MM time string for display (UTC). */
internal fun formatInstant(instant: Instant): String {
    val epochSeconds: Long = instant.epochSeconds
    // Derive UTC hours + minutes from epoch seconds.
    val secondsInDay: Long = ((epochSeconds % 86400L) + 86400L) % 86400L
    val hours: Long = secondsInDay / 3600L
    val minutes: Long = (secondsInDay % 3600L) / 60L
    return "${hours.toString().padStart(2, '0')}:${minutes.toString().padStart(2, '0')}"
}

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
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.openwarden.parent.android.command.DemoLockCommandSender
import com.openwarden.parent.command.LockPresenter
import com.openwarden.parent.dashboard.AppUsageSummary
import com.openwarden.parent.dashboard.BlockedAttempt
import com.openwarden.parent.dashboard.BlocksData
import com.openwarden.parent.dashboard.ChildOnlineStatus
import com.openwarden.parent.dashboard.DashboardUiState
import com.openwarden.parent.dashboard.TodayUsage
import com.openwarden.parent.state.AppState
import com.openwarden.parent.state.LockState
import kotlinx.coroutines.launch
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
 *  - Honest offline (H3): when the child is OFFLINE_OR_UNKNOWN, usage and blocks
 *    display as "unavailable" (—) rather than "0m" / "No blocked attempts today."
 *    A genuine online-with-zero reads as "0m" / "No blocked attempts today."
 */
@Composable
fun DashboardScreen(
    viewModel: DashboardAndroidViewModel,
    modifier: Modifier = Modifier,
    onOpenAllowlist: () -> Unit = {},
) {
    val uiState by viewModel.uiState.collectAsState()

    // Kick off the first load on composition.
    LaunchedEffect(Unit) { viewModel.refresh() }

    // sender and presenter are remembered for the lifetime of this composition.
    // DisposableEffect closes the HttpClient when the composable leaves composition,
    // preventing an OkHttp connection-pool / thread leak.
    val sender = remember { DemoLockCommandSender() }
    val appState = remember { AppState() }
    val presenter = remember(sender, appState) { LockPresenter(appState, sender) }
    DisposableEffect(sender) {
        onDispose { sender.close() }
    }

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
                    is DashboardUiState.Success -> SuccessContent(s, presenter, onOpenAllowlist)
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
private fun SuccessContent(
    state: DashboardUiState.Success,
    presenter: LockPresenter,
    onOpenAllowlist: () -> Unit = {},
) {
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item { Spacer(modifier = Modifier.height(4.dp)) }

        // ---- Online / offline badge ----
        item { OnlineBadge(state.onlineStatus) }

        item { HorizontalDivider() }

        // ---- Lock / Unlock controls (issue #28) ----
        item { LockUnlockSection(presenter = presenter) }

        item { HorizontalDivider() }

        // ---- App allowlist editor nav (issue #26) ----
        item {
            Button(
                onClick = onOpenAllowlist,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Edit app allowlist")
            }
        }

        item { HorizontalDivider() }

        // ---- Today's usage ----
        item {
            Text(
                "Today's Usage",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
        }
        item { UsageSummaryCard(state.todayUsage) }

        item { HorizontalDivider() }

        // ---- Recent blocked attempts ----
        item {
            Text(
                "Recent Blocked Attempts",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
        }
        when (val blocks = state.blocksData) {
            is BlocksData.Unknown -> {
                // H3: Do NOT render "No blocked attempts today." — data is unavailable.
                item {
                    Text(
                        "Blocked-attempt data unavailable while offline.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            is BlocksData.Known -> {
                if (blocks.attempts.isEmpty()) {
                    item {
                        Text(
                            "No blocked attempts today.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                } else {
                    items(blocks.attempts) { block ->
                        BlockedAttemptRow(block)
                    }
                }
            }
        }

        item { Spacer(modifier = Modifier.height(24.dp)) }
    }
}

// ---------------------------------------------------------------------------
// Lock / Unlock control surface (issue #28)
// ---------------------------------------------------------------------------

/**
 * Lock Now / Unlock Now control surface (issue #28).
 *
 * Shows the current child lock state as a text label, a Lock Now button, and an
 * Unlock Now button.  Both buttons are disabled while a command is in-flight.
 * Any command error is surfaced as a text label below the buttons.
 *
 * Fail-closed: on error the lock state resets to UNKNOWN so this section never
 * falsely displays "Unlocked" after a failed unlock attempt.
 */
@Composable
internal fun LockUnlockSection(presenter: LockPresenter) {
    val uiState by presenter.uiState.collectAsState()
    val scope = rememberCoroutineScope()

    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = when (uiState.lockState) {
                LockState.LOCKED -> "Child device: LOCKED"
                LockState.UNLOCKED -> "Child device: UNLOCKED"
                LockState.UNKNOWN -> "Child device: unknown"
            },
            style = MaterialTheme.typography.bodyLarge,
        )

        if (uiState.isBusy) {
            CircularProgressIndicator()
        }

        Row(
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Button(
                onClick = { scope.launch { presenter.lockNow() } },
                enabled = !uiState.isBusy,
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.error,
                ),
            ) {
                Text("Lock Now")
            }

            Button(
                onClick = { scope.launch { presenter.unlockNow() } },
                enabled = !uiState.isBusy,
            ) {
                Text("Unlock Now")
            }
        }

        uiState.lastError?.let { error ->
            Text(
                text = "Error: $error",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
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

/**
 * Renders today's usage.
 *
 * H3 enforcement: [TodayUsage.Unknown] renders "—" / "Usage unavailable while offline"
 * rather than "0m".  A [TodayUsage.Known] with totalForegroundMs == 0 renders "0m"
 * (genuine zero on an idle device — that is an honest reading).
 */
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
            when (usage) {
                is TodayUsage.Unknown -> {
                    // H3: child is offline/unreachable — do NOT show "0m".
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text(
                            "Total screen time today",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Text(
                            "—",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                    Text(
                        "Usage unavailable while offline.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                is TodayUsage.Known -> {
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
    // Metadata only: app label/package, category (enum — never free-form), timestamp, count.
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
            Text(
                text = block.category.displayName,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
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

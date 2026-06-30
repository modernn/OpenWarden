package com.openwarden.parent.android.ui.policy

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.openwarden.parent.policy.AllowlistEditorState
import com.openwarden.parent.policy.AllowlistEditorViewModel
import com.openwarden.parent.policy.AllowlistRepository
import com.openwarden.parent.policy.AppCategoryGroup
import com.openwarden.parent.policy.AppInfo
import com.openwarden.parent.policy.ApplyState
import com.openwarden.parent.policy.PolicySender
import com.openwarden.parent.policy.SendResult
import com.openwarden.proto.Policy
import kotlinx.coroutines.launch

/**
 * Allowlist editor screen — shows the list of apps installed on the child device
 * and lets the parent toggle each one allowed / denied.
 *
 * Fail-closed: if the child cannot be reached, an error banner is shown and the
 * previous allowlist is preserved. The toggle controls are disabled while loading.
 *
 * Apps are grouped under [AppCategory] section headers (enum order; OTHER / UNKNOWN
 * last) and sorted alphabetically by label within each group.
 *
 * The "Apply to child" button sends the current allowlist as a signed policy bundle
 * via [AllowlistEditorViewModel.apply]. Every [ApplyState] variant is surfaced as
 * explicit UI — only [ApplyState.Applied] is treated as success (fail-closed).
 *
 * [AllowlistEditorViewModel] lives in :shared (commonMain) so it is not an
 * AndroidX ViewModel; it is created with [remember] and its lifecycle is tied
 * to this screen's composition. A full DI/ViewModel layer is deferred (#27).
 *
 * @param repo [AllowlistRepository] satisfying the transport seam. Pass a real
 *   implementation (e.g. [com.openwarden.parent.android.policy.DemoAllowlistRepository])
 *   from the calling Activity or nav-graph.
 * @param sender Optional [PolicySender] for the "Apply to child" push path. When
 *   null the button is still rendered but immediately maps to [ApplyState.NotProvisioned]
 *   (recovery-key setup not complete). The demo wiring passes a real sender; tests inject
 *   a fake.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AllowlistEditorScreen(
    repo: AllowlistRepository,
    sender: PolicySender? = null,
    onBack: () -> Unit = {},
) {
    // Keyed on repo + sender so a genuinely different instance rebuilds the VM (and its captured
    // send lambda) instead of retaining a stale closure (cavecrew review #149). Both are
    // Activity-scoped/stable today, so in practice this builds exactly once per screen entry.
    val viewModel =
        remember(repo, sender) {
            val sendFn: (suspend (Policy) -> SendResult)? =
                if (sender != null) {
                    { policy: Policy -> sender.send(policy) }
                } else {
                    null
                }
            AllowlistEditorViewModel(repo, sendFn)
        }
    val state by viewModel.state.collectAsState()
    val scope = rememberCoroutineScope()

    // Trigger the initial load once when the screen enters composition.
    LaunchedEffect(Unit) { viewModel.load() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("App Allowlist") },
                navigationIcon = {
                    Button(
                        onClick = onBack,
                        contentPadding = PaddingValues(horizontal = 8.dp),
                    ) {
                        Text("Back")
                    }
                },
            )
        },
    ) { innerPadding ->
        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
        ) {
            AllowlistEditorContent(
                state = state,
                groupedApps = viewModel.groupedApps(),
                onToggle = { pkg -> viewModel.toggle(pkg) },
                onRetry = { scope.launch { viewModel.load() } },
                onApply = { scope.launch { viewModel.apply() } },
                onDismissApplyState = { viewModel.clearApplyState() },
            )
        }
    }
}

@Composable
internal fun AllowlistEditorContent(
    state: AllowlistEditorState,
    groupedApps: List<AppCategoryGroup>,
    onToggle: (String) -> Unit,
    onRetry: () -> Unit,
    onApply: () -> Unit,
    onDismissApplyState: () -> Unit,
) {
    when {
        state.loading -> {
            LoadingIndicator()
        }

        state.errorMessage != null -> {
            ErrorWithRetry(checkNotNull(state.errorMessage), onRetry)
        }

        state.apps.isEmpty() -> {
            EmptyApps(onRetry)
        }

        else -> {
            Column(modifier = Modifier.fillMaxSize()) {
                // Apply banner lives above the list so it's always visible.
                state.applyState?.let { applyState ->
                    ApplyStateBanner(
                        applyState = applyState,
                        onDismiss = onDismissApplyState,
                    )
                }
                GroupedAppList(
                    groups = groupedApps,
                    allowlist = state.allowlist,
                    onToggle = onToggle,
                    modifier = Modifier.weight(1f),
                )
                // "Apply to child" button pinned at the bottom of the list.
                ApplyButton(
                    sending = state.applyState is ApplyState.Sending,
                    onApply = onApply,
                )
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Apply state banner
// ---------------------------------------------------------------------------

@Composable
private fun ApplyStateBanner(
    applyState: ApplyState,
    onDismiss: () -> Unit,
) {
    val (containerColor, label) =
        when (applyState) {
            is ApplyState.Sending -> {
                MaterialTheme.colorScheme.surfaceVariant to "Sending policy to child…"
            }

            is ApplyState.Applied -> {
                MaterialTheme.colorScheme.primaryContainer to
                    "Policy applied (seq ${applyState.policySeq})"
            }

            is ApplyState.Rejected -> {
                MaterialTheme.colorScheme.errorContainer to
                    "Child rejected policy: ${applyState.reason}"
            }

            is ApplyState.TransportFailed -> {
                MaterialTheme.colorScheme.errorContainer to
                    "Send failed: ${applyState.message}"
            }

            ApplyState.NotProvisioned -> {
                MaterialTheme.colorScheme.errorContainer to
                    "Set up your recovery key first before sending policy."
            }

            ApplyState.NotPaired -> {
                MaterialTheme.colorScheme.errorContainer to
                    "No child paired yet. Pair a device before sending policy."
            }
        }

    Card(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        colors = CardDefaults.cardColors(containerColor = containerColor),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.weight(1f),
            )
            if (applyState !is ApplyState.Sending) {
                Button(
                    onClick = onDismiss,
                    contentPadding = PaddingValues(horizontal = 8.dp),
                ) {
                    Text("OK")
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Apply button
// ---------------------------------------------------------------------------

@Composable
private fun ApplyButton(
    sending: Boolean,
    onApply: () -> Unit,
) {
    Box(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        Button(
            onClick = onApply,
            enabled = !sending,
            modifier =
                Modifier
                    .fillMaxWidth()
                    .semantics { contentDescription = "Apply allowlist to child device" },
        ) {
            Text(if (sending) "Sending…" else "Apply to child")
        }
    }
}

// ---------------------------------------------------------------------------
// Grouped app list
// ---------------------------------------------------------------------------

@Composable
private fun GroupedAppList(
    groups: List<AppCategoryGroup>,
    allowlist: Set<String>,
    onToggle: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyColumn(
        modifier = modifier.fillMaxWidth(),
        contentPadding = PaddingValues(vertical = 8.dp),
    ) {
        groups.forEach { group ->
            // Category section header.
            item(key = "header-${group.category.name}") {
                CategoryHeader(group.category.displayName)
            }
            // Apps within this group.
            items(group.apps, key = { it.packageName }) { app ->
                AppRow(
                    app = app,
                    allowed = app.packageName in allowlist,
                    onToggle = { onToggle(app.packageName) },
                )
                HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
            }
        }
    }
}

@Composable
private fun CategoryHeader(displayName: String) {
    Text(
        text = displayName,
        style = MaterialTheme.typography.labelLarge,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.primary,
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 6.dp),
    )
}

// ---------------------------------------------------------------------------
// Loading / error / empty states (unchanged behaviour)
// ---------------------------------------------------------------------------

@Composable
private fun LoadingIndicator() {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        CircularProgressIndicator(
            modifier = Modifier.semantics { contentDescription = "Loading installed apps" },
        )
    }
}

@Composable
private fun ErrorWithRetry(
    message: String,
    onRetry: () -> Unit,
) {
    Column(
        modifier =
            Modifier
                .fillMaxSize()
                .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
        ) {
            Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "Could not reach child device",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                )
                Text(
                    message,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                )
                Text(
                    "Your previous allowlist is preserved. No apps are unblocked by this error.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                )
            }
        }
        Button(onClick = onRetry) { Text("Retry") }
    }
}

@Composable
private fun EmptyApps(onRetry: () -> Unit) {
    Column(
        modifier =
            Modifier
                .fillMaxSize()
                .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
        ) {
            Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "No apps reported by child device",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                )
                Text(
                    "The child device returned an empty app list. " +
                        "This means no apps are currently restricted by the allowlist — " +
                        "the child can open any app until you receive and toggle the app list.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                )
            }
        }
        Button(onClick = onRetry) { Text("Retry") }
    }
}

@Composable
private fun AppRow(
    app: AppInfo,
    allowed: Boolean,
    onToggle: () -> Unit,
) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = app.label.ifBlank { app.packageName },
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                text = app.packageName,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(
            checked = allowed,
            onCheckedChange = { onToggle() },
            modifier =
                Modifier.semantics {
                    contentDescription =
                        "${app.label.ifBlank { app.packageName }}: ${if (allowed) "allowed" else "blocked"}"
                },
        )
    }
}

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
import androidx.compose.ui.unit.dp
import com.openwarden.parent.policy.AllowlistEditorState
import com.openwarden.parent.policy.AllowlistEditorViewModel
import com.openwarden.parent.policy.AllowlistRepository
import com.openwarden.parent.policy.AppInfo
import kotlinx.coroutines.launch

/**
 * Allowlist editor screen — shows the list of apps installed on the child device
 * and lets the parent toggle each one allowed / denied.
 *
 * Fail-closed: if the child cannot be reached, an error banner is shown and the
 * previous allowlist is preserved. The toggle controls are disabled while loading.
 *
 * [AllowlistEditorViewModel] lives in :shared (commonMain) so it is not an
 * AndroidX ViewModel; it is created with [remember] and its lifecycle is tied
 * to this screen's composition. A full DI/ViewModel layer is deferred (#27).
 *
 * @param repo [AllowlistRepository] satisfying the transport seam. Pass a real
 *   implementation (e.g. [com.openwarden.parent.android.policy.DemoAllowlistRepository])
 *   from the calling Activity or nav-graph.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AllowlistEditorScreen(
    repo: AllowlistRepository,
    onBack: () -> Unit = {},
) {
    val viewModel = remember { AllowlistEditorViewModel(repo) }
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
                onToggle = { pkg -> viewModel.toggle(pkg) },
                onRetry = { scope.launch { viewModel.load() } },
            )
        }
    }
}

@Composable
internal fun AllowlistEditorContent(
    state: AllowlistEditorState,
    onToggle: (String) -> Unit,
    onRetry: () -> Unit,
) {
    when {
        state.loading -> LoadingIndicator()
        state.errorMessage != null -> ErrorWithRetry(checkNotNull(state.errorMessage), onRetry)
        state.apps.isEmpty() -> EmptyApps(onRetry)
        else -> AppList(state.apps, state.allowlist, onToggle)
    }
}

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
private fun AppList(
    apps: List<AppInfo>,
    allowlist: Set<String>,
    onToggle: (String) -> Unit,
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(vertical = 8.dp),
    ) {
        items(apps, key = { it.packageName }) { app ->
            AppRow(
                app = app,
                allowed = app.packageName in allowlist,
                onToggle = { onToggle(app.packageName) },
            )
            HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
        }
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

package com.openwarden.parent.android

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.openwarden.parent.android.policy.DemoAllowlistRepository
import com.openwarden.parent.android.ui.policy.AllowlistEditorScreen
import com.openwarden.parent.state.AppState
import com.openwarden.parent.state.PairingStatus

class MainActivity : ComponentActivity() {
    private val appState = AppState()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme { AppRoot(appState) }
        }
    }
}

@Composable
private fun AppRoot(appState: AppState) {
    var showAllowlist by remember { mutableStateOf(false) }
    // Repo is created once here; a real DI layer would inject it.
    val allowlistRepo = remember { DemoAllowlistRepository() }

    if (showAllowlist) {
        AllowlistEditorScreen(
            repo = allowlistRepo,
            onBack = { showAllowlist = false },
        )
    } else {
        DashboardScreen(appState, onOpenAllowlist = { showAllowlist = true })
    }
}

@Composable
private fun DashboardScreen(appState: AppState, onOpenAllowlist: () -> Unit) {
    val pairing by appState.pairing.collectAsState()
    Surface(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text("OpenWarden", style = MaterialTheme.typography.headlineMedium)
            Text("Parent app — scaffold", style = MaterialTheme.typography.bodyMedium)
            AssistChip(
                onClick = {},
                label = { Text(if (pairing == PairingStatus.PAIRED) "Paired" else "Not paired") },
            )
            HorizontalDivider()
            Button(onClick = onOpenAllowlist) {
                Text("Edit app allowlist")
            }
        }
    }
}

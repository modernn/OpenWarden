package com.openwarden.parent.android

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AssistChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.openwarden.parent.state.AppState
import com.openwarden.parent.state.PairingStatus

class MainActivity : ComponentActivity() {
    private val appState = AppState()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme { DashboardScreen(appState) }
        }
    }
}

@Composable
private fun DashboardScreen(appState: AppState) {
    val pairing by appState.pairing.collectAsState()
    Surface(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier.fillMaxSize().padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text("OpenWarden", style = MaterialTheme.typography.headlineMedium)
            Text("Parent app — scaffold", style = MaterialTheme.typography.bodyMedium)
            AssistChip(
                onClick = {},
                label = { Text(if (pairing == PairingStatus.PAIRED) "Paired" else "Not paired") },
            )
        }
    }
}

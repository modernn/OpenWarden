package com.openwarden.parent.android

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.openwarden.parent.android.command.DemoLockCommandSender
import com.openwarden.parent.command.LockPresenter
import com.openwarden.parent.state.AppState
import com.openwarden.parent.state.LockState
import com.openwarden.parent.state.PairingStatus
import kotlinx.coroutines.launch

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

    // sender and presenter are remembered for the lifetime of this composition.
    // DisposableEffect closes the HttpClient when the composable leaves composition,
    // preventing an OkHttp connection-pool / thread leak.
    val sender = remember { DemoLockCommandSender() }
    val presenter = remember(sender) { LockPresenter(appState, sender) }
    DisposableEffect(sender) {
        onDispose { sender.close() }
    }

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

            androidx.compose.material3.AssistChip(
                onClick = {},
                label = {
                    Text(if (pairing == PairingStatus.PAIRED) "Paired" else "Not paired")
                },
            )

            Spacer(modifier = Modifier.height(8.dp))

            LockUnlockSection(presenter = presenter)
        }
    }
}

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

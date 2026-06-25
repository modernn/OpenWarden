package com.openwarden.parent.android.ui.pair

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.openwarden.parent.pairing.PairingAbortReason
import com.openwarden.parent.pairing.PairingController
import com.openwarden.parent.pairing.PairingPhase

/**
 * The parent "Pair a child device" flow (ADR-043 slice f) — the Compose face of [PairingController].
 *
 * It renders the controller's `phase` state machine: show the §7.1 QR for the child to scan → show the
 * six §7.4 emojis once the child passes attestation → capture the human's Match/Mismatch tap (the H3
 * defense) → land on a terminal success / fail-closed abort. The screen holds no crypto and no key
 * material — it only collects `phase` and calls `begin()/confirm()/cancel()`; the controller owns the
 * whole handshake.
 *
 * Lifecycle: [LaunchedEffect] starts an attempt on entry; [DisposableEffect] cancels it on exit so the
 * `/pair` listener never outlives the screen (ADR-043 D4).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PairingFlowScreen(
    controller: PairingController,
    onBack: () -> Unit = {},
    onPaired: () -> Unit = {},
) {
    val phase by controller.phase.collectAsState()

    LaunchedEffect(controller) { controller.begin() }
    DisposableEffect(controller) {
        onDispose {
            // Stop the listener + burn any live attempt when the parent leaves the screen.
            controller.cancel()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Pair a child device") },
                navigationIcon = {
                    Button(
                        onClick = onBack,
                        contentPadding =
                            androidx.compose.foundation.layout
                                .PaddingValues(horizontal = 8.dp),
                    ) { Text("Back") }
                },
            )
        },
    ) { innerPadding ->
        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            when (val p = phase) {
                is PairingPhase.Idle -> {
                    StatusText("Starting…")
                }

                is PairingPhase.ShowingQr -> {
                    ShowQrStep(
                        qrPayloadJson = p.qrPayloadJson,
                        onCancel = onBack,
                    )
                }

                is PairingPhase.AwaitingSas -> {
                    AwaitSasStep(
                        emojis = p.emojis,
                        onMatch = { controller.confirm(matched = true) },
                        onMismatch = { controller.confirm(matched = false) },
                    )
                }

                is PairingPhase.Pinned -> {
                    PinnedStep(onDone = onPaired)
                }

                is PairingPhase.NotProvisioned -> {
                    TerminalMessage(
                        title = "Set up the parent key first",
                        body =
                            "This phone has no recovery key yet, so it can't pair. " +
                                "Create the parent recovery phrase, then try again.",
                        isError = true,
                        primaryLabel = "Back",
                        onPrimary = onBack,
                    )
                }

                is PairingPhase.Aborted -> {
                    AbortedStep(
                        reason = p.reason,
                        onRetry = { controller.begin() },
                        onBack = onBack,
                    )
                }
            }
        }
    }
}

@Composable
private fun ShowQrStep(
    qrPayloadJson: String,
    onCancel: () -> Unit,
) {
    Text(
        "Scan this code with the child device",
        style = MaterialTheme.typography.titleMedium,
        textAlign = TextAlign.Center,
    )
    QrCodeImage(
        content = qrPayloadJson,
        modifier =
            Modifier
                .size(DEFAULT_QR_SIZE)
                .semantics { contentDescription = "Pairing QR code" },
    )
    Text(
        "Open OpenWarden on the child phone and point its camera here. " +
            "Once it scans, you'll both see six emojis to compare.",
        style = MaterialTheme.typography.bodyMedium,
        textAlign = TextAlign.Center,
    )
    OutlinedButton(onClick = onCancel) { Text("Cancel") }
}

@Composable
private fun AwaitSasStep(
    emojis: List<String>,
    onMatch: () -> Unit,
    onMismatch: () -> Unit,
) {
    Text(
        "Do these emojis match the child's screen?",
        style = MaterialTheme.typography.titleMedium,
        textAlign = TextAlign.Center,
    )
    Card(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = emojis.joinToString("  "),
            fontSize = 40.sp,
            textAlign = TextAlign.Center,
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(vertical = 20.dp)
                    .semantics { contentDescription = "Confirmation emojis: ${emojis.joinToString(", ")}" },
        )
    }
    Text(
        "They must be identical and in the same order on both phones. " +
            "If they differ, someone may be intercepting the connection — tap \"They don't match\".",
        style = MaterialTheme.typography.bodySmall,
        textAlign = TextAlign.Center,
    )
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        OutlinedButton(
            onClick = onMismatch,
            modifier = Modifier.weight(1f),
        ) { Text("They don't match") }
        Button(
            onClick = onMatch,
            modifier = Modifier.weight(1f),
        ) { Text("They match") }
    }
}

@Composable
private fun PinnedStep(onDone: () -> Unit) {
    TerminalMessage(
        title = "Child device paired ✓",
        body = "The child device is now linked. You can manage it from the dashboard.",
        isError = false,
        primaryLabel = "Done",
        onPrimary = onDone,
    )
}

@Composable
private fun AbortedStep(
    reason: PairingAbortReason,
    onRetry: () -> Unit,
    onBack: () -> Unit,
) {
    val (title, body) =
        when (reason) {
            PairingAbortReason.ATTESTATION_FAILED -> {
                "Device failed hardware check" to
                    "The child device couldn't prove it's a supported, locked-down phone. " +
                    "Pairing is blocked to keep things safe."
            }

            PairingAbortReason.SAS_MISMATCH -> {
                "Emojis didn't match" to
                    "The codes were different, which can mean someone is intercepting the connection. " +
                    "Pairing was stopped. Start over to try again with a fresh code."
            }

            PairingAbortReason.STALE -> {
                "Pairing timed out" to
                    "That pairing attempt is no longer active. Start over to get a fresh code."
            }

            PairingAbortReason.ALREADY_PAIRED -> {
                "A child is already paired" to
                    "This phone is already linked to a child device. To link a different one, " +
                    "use the recovery phrase to reset first."
            }

            PairingAbortReason.NO_LIVE_ATTEMPT -> {
                "Nothing to confirm" to
                    "There was no active pairing to confirm. Start over to try again."
            }
        }
    // ALREADY_PAIRED is not retryable from here (rotation is recovery-gated, §7.5/D8 — ADR-039 D3).
    val retryable = reason != PairingAbortReason.ALREADY_PAIRED
    TerminalMessage(
        title = title,
        body = body,
        isError = true,
        primaryLabel = if (retryable) "Start over" else "Back",
        onPrimary = if (retryable) onRetry else onBack,
        secondaryLabel = if (retryable) "Back" else null,
        onSecondary = if (retryable) onBack else null,
    )
}

@Composable
private fun StatusText(text: String) {
    Text(text, style = MaterialTheme.typography.bodyLarge, textAlign = TextAlign.Center)
}

@Composable
private fun TerminalMessage(
    title: String,
    body: String,
    isError: Boolean,
    primaryLabel: String,
    onPrimary: () -> Unit,
    secondaryLabel: String? = null,
    onSecondary: (() -> Unit)? = null,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors =
            if (isError) {
                CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)
            } else {
                CardDefaults.cardColors()
            },
    ) {
        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                title,
                style = MaterialTheme.typography.titleMedium,
                color =
                    if (isError) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onSurface,
            )
            Text(
                body,
                style = MaterialTheme.typography.bodyMedium,
                color =
                    if (isError) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onSurface,
            )
        }
    }
    Button(
        onClick = onPrimary,
        modifier = Modifier.fillMaxWidth(),
    ) { Text(primaryLabel) }
    if (secondaryLabel != null && onSecondary != null) {
        OutlinedButton(
            onClick = onSecondary,
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.outlinedButtonColors(),
        ) { Text(secondaryLabel) }
    }
}

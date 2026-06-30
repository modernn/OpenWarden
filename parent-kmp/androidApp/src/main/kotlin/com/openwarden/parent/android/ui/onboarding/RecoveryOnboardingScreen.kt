package com.openwarden.parent.android.ui.onboarding

import android.app.Activity
import android.view.WindowManager
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.openwarden.parent.onboarding.OnboardingUiState
import com.openwarden.parent.onboarding.RecoveryOnboardingViewModel

/**
 * Recovery-phrase onboarding screen (ADR-046 D2).
 *
 * Flow:
 * 1. [OnboardingUiState.ShowPhrase] — display 24 words under FLAG_SECURE.
 * 2. [OnboardingUiState.Challenge] — parent re-types challenged positions.
 * 3. [OnboardingUiState.Provisioned] → calls [onProvisioned].
 * 4. [OnboardingUiState.WrongAnswers] — error banner; retry same challenge.
 * 5. [OnboardingUiState.StorageError] — "set a screen lock first" banner.
 *
 * FLAG_SECURE is applied via [DisposableEffect] so it is set exactly while this
 * screen is in composition and removed on dispose (e.g. navigate away).
 *
 * @param viewModel  Created by the caller with a real [RecoveryOnboarding.Session].
 * @param onProvisioned  Navigate to the dashboard after key is persisted.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RecoveryOnboardingScreen(
    viewModel: RecoveryOnboardingViewModel,
    onProvisioned: () -> Unit,
) {
    val state by viewModel.state.collectAsState()

    // Apply FLAG_SECURE so the phrase can never appear in screen captures or the recent-apps thumbnail.
    val activity = LocalContext.current as? Activity
    DisposableEffect(Unit) {
        activity?.window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        onDispose {
            activity?.window?.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(title = { Text("Set up recovery key") })
        },
    ) { innerPadding ->
        when (val s = state) {
            is OnboardingUiState.ShowPhrase -> {
                PhraseDisplay(
                    words = s.words,
                    onContinue = { viewModel.proceedToChallenge() },
                    modifier = Modifier.padding(innerPadding),
                )
            }

            is OnboardingUiState.Challenge -> {
                ChallengeEntry(
                    positions = s.positions,
                    answers = s.answers,
                    errorMessage = null,
                    onAnswer = { pos, word -> viewModel.updateAnswer(pos, word) },
                    onConfirm = { viewModel.confirm(s.answers) },
                    modifier = Modifier.padding(innerPadding),
                )
            }

            is OnboardingUiState.WrongAnswers -> {
                ChallengeEntry(
                    positions = s.positions,
                    answers = s.answers,
                    errorMessage = "Those words didn't match — check your phrase and try again.",
                    onAnswer = { pos, word -> viewModel.updateAnswer(pos, word) },
                    onConfirm = { viewModel.confirm(s.answers) },
                    modifier = Modifier.padding(innerPadding),
                )
            }

            is OnboardingUiState.Submitting -> {
                // The slow Argon2id derivation is running (confirm is single-flight, #151 review).
                Column(
                    modifier = Modifier.fillMaxSize().padding(innerPadding),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    CircularProgressIndicator()
                    Text("Deriving your recovery key…", style = MaterialTheme.typography.bodyMedium)
                }
            }

            is OnboardingUiState.StorageError -> {
                StorageErrorScreen(
                    modifier = Modifier.padding(innerPadding),
                )
            }

            is OnboardingUiState.Provisioned -> {
                // Side-effect: navigate once; the composable rebuilds immediately so a
                // LaunchedEffect is avoided (navigating in the composition callback is safe here
                // because we transition away before a second recompose fires).
                onProvisioned()
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Phrase display
// ---------------------------------------------------------------------------

@Composable
private fun PhraseDisplay(
    words: List<String>,
    onContinue: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val pairs = remember(words) { words.chunked(2) }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        item {
            Text(
                text = "Write down your 24-word recovery phrase",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text =
                    "This phrase is the ONLY way to recover access to OpenWarden. " +
                        "Write it on paper, keep it safe, and never share it. " +
                        "This screen is blocked from screenshots (FLAG_SECURE).",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(12.dp))
        }

        itemsIndexed(pairs) { pairIdx, pair ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                pair.forEachIndexed { i, word ->
                    val number = pairIdx * 2 + i + 1
                    WordChip(number = number, word = word, modifier = Modifier.weight(1f))
                }
                // Pad last row if the list has an odd count (shouldn't happen for 24 words).
                if (pair.size < 2) Spacer(Modifier.weight(1f))
            }
        }

        item {
            Spacer(Modifier.height(16.dp))
            Button(
                onClick = onContinue,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("I've written it down — verify now")
            }
        }
    }
}

@Composable
private fun WordChip(
    number: Int,
    word: String,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Row(
            modifier = Modifier.padding(8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                text = "$number.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier,
            )
            Text(
                text = word,
                style = MaterialTheme.typography.bodyMedium,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Medium,
            )
        }
    }
}

// ---------------------------------------------------------------------------
// Challenge entry
// ---------------------------------------------------------------------------

@Composable
private fun ChallengeEntry(
    positions: List<Int>,
    answers: Map<Int, String>,
    errorMessage: String?,
    onAnswer: (Int, String) -> Unit,
    onConfirm: () -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item {
            Text(
                text = "Verify your recovery phrase",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = "Type the requested words from your written phrase.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        errorMessage?.let { msg ->
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
                ) {
                    Text(
                        text = msg,
                        modifier = Modifier.padding(12.dp),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                    )
                }
            }
        }

        itemsIndexed(positions) { i, pos ->
            val isLast = i == positions.lastIndex
            OutlinedTextField(
                value = answers[pos] ?: "",
                onValueChange = { onAnswer(pos, it.trim()) },
                label = { Text("Word #$pos") },
                singleLine = true,
                keyboardOptions =
                    KeyboardOptions(
                        imeAction = if (isLast) ImeAction.Done else ImeAction.Next,
                    ),
                modifier = Modifier.fillMaxWidth(),
            )
        }

        item {
            Spacer(Modifier.height(8.dp))
            Button(
                onClick = onConfirm,
                modifier = Modifier.fillMaxWidth(),
                enabled = positions.all { pos -> answers[pos]?.isNotBlank() == true },
            ) {
                Text("Confirm")
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Storage error
// ---------------------------------------------------------------------------

@Composable
private fun StorageErrorScreen(modifier: Modifier = Modifier) {
    Column(
        modifier =
            modifier
                .fillMaxSize()
                .padding(16.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    "Screen lock required",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                )
                Text(
                    "Set a device screen lock (PIN, pattern, or password) first, then retry." +
                        " OpenWarden cannot store the recovery key without a secure lock screen.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                )
            }
        }
    }
}

package com.openwarden.parent.onboarding

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext

/**
 * UI state for the recovery-phrase onboarding screen (ADR-046 D2).
 *
 * States:
 * - [ShowPhrase]   — display the 24 words under FLAG_SECURE; present Continue action.
 * - [Challenge]    — prompt the parent to re-type the challenged positions.
 * - [Provisioned]  — key persisted; navigate to dashboard.
 * - [WrongAnswers] — challenge failed; show error + allow retry.
 * - [StorageError] — SecureStorageUnavailableException caught; show screen-lock hint.
 */
sealed interface OnboardingUiState {
    /** The 24-word phrase; zero-indexed. Display under FLAG_SECURE. */
    data class ShowPhrase(
        val words: List<String>,
    ) : OnboardingUiState

    /** One-based positions the parent must re-type. */
    data class Challenge(
        val positions: List<Int>,
        val answers: Map<Int, String>,
    ) : OnboardingUiState

    /** Key derived and persisted — navigate away. */
    data object Provisioned : OnboardingUiState

    /** Wrong words — let the parent try again (same challenge). */
    data class WrongAnswers(
        val positions: List<Int>,
        val answers: Map<Int, String>,
    ) : OnboardingUiState

    /**
     * A confirm is in flight (the slow Argon2id derivation runs inside [confirm]). Disables the
     * Confirm action so a double-click can't start a second derivation (#151 review).
     */
    data object Submitting : OnboardingUiState

    /** Secure store unavailable — tell parent to set a screen lock. */
    data object StorageError : OnboardingUiState
}

/**
 * Platform-agnostic presenter for [RecoveryOnboardingScreen].
 *
 * Inject [confirmSession] as a lambda so the class is testable without a real
 * [com.openwarden.parent.crypto.RecoveryOnboarding.Session]:
 *
 * ```kotlin
 * // production:
 * val session = RecoveryOnboarding(AndroidSecureKeyStorage(ctx)).start()
 * val vm = RecoveryOnboardingViewModel(
 *     mnemonic = session.mnemonic,
 *     challengePositions = session.challengePositions,
 *     confirmSession = { answers -> session.confirm(answers) },
 * )
 *
 * // test:
 * val vm = RecoveryOnboardingViewModel(
 *     mnemonic = listOf("word1", …),
 *     challengePositions = listOf(3, 17),
 *     confirmSession = { _ -> true },
 * )
 * ```
 *
 * [confirmSession] MUST mirror [com.openwarden.parent.crypto.RecoveryOnboarding.Session.confirm]:
 * - Returns `true`  → key persisted; VM transitions to [OnboardingUiState.Provisioned].
 * - Returns `false` → wrong answers; VM transitions to [OnboardingUiState.WrongAnswers].
 * - Throws [com.openwarden.parent.crypto.SecureStorageUnavailableException]
 *   → VM transitions to [OnboardingUiState.StorageError].
 *
 * CRYPTO NOTE: this class never touches key material. All signing, derivation, and storage
 * live inside [confirmSession] (which wraps [Session.confirm] in production).
 */
class RecoveryOnboardingViewModel(
    val mnemonic: List<String>,
    val challengePositions: List<Int>,
    private val confirmSession: (Map<Int, String>) -> Boolean,
    // #155: the ~256 MiB / ~2 s Argon2id derivation inside [confirmSession] runs on this dispatcher,
    // NEVER the caller's (main) thread. Injectable so tests can pass a deterministic test dispatcher.
    private val derivationDispatcher: CoroutineDispatcher = Dispatchers.Default,
) {
    private val _state: MutableStateFlow<OnboardingUiState> =
        MutableStateFlow(OnboardingUiState.ShowPhrase(mnemonic))

    val state: StateFlow<OnboardingUiState> = _state.asStateFlow()

    /** Parent has read the phrase and is ready to be challenged. */
    fun proceedToChallenge() {
        _state.update {
            OnboardingUiState.Challenge(
                positions = challengePositions,
                answers = emptyMap(),
            )
        }
    }

    /** Update a single challenge answer. */
    fun updateAnswer(
        position: Int,
        word: String,
    ) {
        val current = _state.value
        if (current !is OnboardingUiState.Challenge && current !is OnboardingUiState.WrongAnswers) return
        val currentAnswers =
            when (current) {
                is OnboardingUiState.Challenge -> current.answers
                is OnboardingUiState.WrongAnswers -> current.answers
                else -> emptyMap()
            }
        val updated = currentAnswers + (position to word)
        _state.update {
            OnboardingUiState.Challenge(
                positions = challengePositions,
                answers = updated,
            )
        }
    }

    /**
     * Submit challenge answers.
     *
     * Calls [confirmSession] which in production is [Session.confirm] — key derivation +
     * storage happen inside that call, never here.
     *
     * Catches [com.openwarden.parent.crypto.SecureStorageUnavailableException] (an
     * `IllegalStateException`, hence an [Exception]) so this class stays in commonMain without an
     * Android import; the screen surfaces [OnboardingUiState.StorageError] with a "set a screen lock
     * first" message. We catch [Exception], NOT [Throwable], so JVM [Error]s (OOM, StackOverflow) are
     * not masked as a storage problem and propagate (#151 review).
     *
     * Double-submit safe: an atomic [kotlinx.coroutines.flow.MutableStateFlow.compareAndSet] claims the
     * `Challenge`/`WrongAnswers` → [OnboardingUiState.Submitting] transition, so a concurrent
     * double-click loses the CAS and returns — the slow Argon2id derivation runs at most once. The CAS
     * runs on the caller thread BEFORE dispatching, so single-flight holds even under a rapid double tap.
     *
     * Threading (#155): the derivation ([confirmSession]) runs via [withContext] on [derivationDispatcher]
     * (default [Dispatchers.Default]), NEVER the caller's (main) thread — a ~256 MiB / ~2 s Argon2id on
     * main would ANR (and its OOM would land as a main-thread crash). `suspend` so the caller drives it
     * from a coroutine scope; the `Submitting` spinner is visible for the whole derivation.
     */
    suspend fun confirm(answers: Map<Int, String>) {
        val current = _state.value
        // Only a pending challenge can start a confirm — a terminal/Submitting state is ignored.
        if (current !is OnboardingUiState.Challenge && current !is OnboardingUiState.WrongAnswers) return
        // Atomically claim the submit on the caller thread; a concurrent re-entry loses this CAS and
        // returns, so the slow derivation is dispatched at most once.
        if (!_state.compareAndSet(current, OnboardingUiState.Submitting)) return
        val result =
            try {
                // The ratified 256 MiB Argon2id (RootKeyDerivation) runs OFF the main thread.
                withContext(derivationDispatcher) { confirmSession(answers) }
            } catch (e: Exception) {
                _state.update { OnboardingUiState.StorageError }
                return
            }
        _state.update {
            if (result) {
                OnboardingUiState.Provisioned
            } else {
                OnboardingUiState.WrongAnswers(
                    positions = challengePositions,
                    answers = answers,
                )
            }
        }
    }
}

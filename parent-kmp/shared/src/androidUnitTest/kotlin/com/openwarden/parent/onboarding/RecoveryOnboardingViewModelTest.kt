package com.openwarden.parent.onboarding

import com.openwarden.parent.crypto.SecureStorageUnavailableException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Unit tests for [RecoveryOnboardingViewModel].
 *
 * All crypto is replaced by a fake [confirmSession] lambda — no key material, no Argon2id,
 * no Android context required. Tests run in milliseconds.
 *
 * Contract under test (ADR-046 D2):
 *  1. Correct answers → [OnboardingUiState.Provisioned].
 *  2. Wrong answers → [OnboardingUiState.WrongAnswers] (no side effect on storage).
 *  3. [confirmSession] throwing [SecureStorageUnavailableException] → [OnboardingUiState.StorageError].
 *  4. [proceedToChallenge] transitions ShowPhrase → Challenge.
 *  5. [updateAnswer] accumulates answers in [OnboardingUiState.Challenge].
 */
class RecoveryOnboardingViewModelTest {
    private val fakeMnemonic = (1..24).map { "word$it" }
    private val fakePositions = listOf(3, 7, 15, 22)

    private fun vm(confirmResult: (Map<Int, String>) -> Boolean): RecoveryOnboardingViewModel =
        RecoveryOnboardingViewModel(
            mnemonic = fakeMnemonic,
            challengePositions = fakePositions,
            confirmSession = confirmResult,
        )

    // ---- initial state ----

    @Test
    fun initialState_isShowPhrase() {
        val model = vm { true }
        assertIs<OnboardingUiState.ShowPhrase>(model.state.value)
        assertEquals(fakeMnemonic, (model.state.value as OnboardingUiState.ShowPhrase).words)
    }

    // ---- proceedToChallenge ----

    @Test
    fun proceedToChallenge_transitionsToChallenge() {
        val model = vm { true }
        model.proceedToChallenge()
        val state = model.state.value
        assertIs<OnboardingUiState.Challenge>(state)
        assertEquals(fakePositions, state.positions)
        assertTrue(state.answers.isEmpty())
    }

    // ---- updateAnswer ----

    @Test
    fun updateAnswer_accumulatesAnswers() {
        val model = vm { true }
        model.proceedToChallenge()
        model.updateAnswer(3, "hello")
        model.updateAnswer(7, "world")
        val state = model.state.value as OnboardingUiState.Challenge
        assertEquals("hello", state.answers[3])
        assertEquals("world", state.answers[7])
    }

    @Test
    fun updateAnswer_ignored_whenNotInChallengeState() {
        val model = vm { true }
        // State is ShowPhrase — updateAnswer should no-op.
        model.updateAnswer(3, "ignored")
        assertIs<OnboardingUiState.ShowPhrase>(model.state.value)
    }

    // ---- confirm: correct answers ----

    @Test
    fun confirm_correctAnswers_transitionsToProvisioned() {
        val model = vm { true }
        model.proceedToChallenge()
        model.confirm(fakePositions.associateWith { "correct" })
        assertIs<OnboardingUiState.Provisioned>(model.state.value)
    }

    // ---- confirm: wrong answers ----

    @Test
    fun confirm_wrongAnswers_transitionsToWrongAnswers() {
        val model = vm { false }
        model.proceedToChallenge()
        val badAnswers = fakePositions.associateWith { "wrong" }
        model.confirm(badAnswers)
        val state = model.state.value
        assertIs<OnboardingUiState.WrongAnswers>(state)
        assertEquals(fakePositions, state.positions)
        assertEquals(badAnswers, state.answers)
    }

    @Test
    fun confirm_wrongAnswers_doesNotCallStorage() {
        // confirmSession returns false → no side effect. We verify by counting calls.
        var calls = 0
        val model =
            RecoveryOnboardingViewModel(
                mnemonic = fakeMnemonic,
                challengePositions = fakePositions,
                confirmSession = { _ ->
                    calls++
                    false
                },
            )
        model.proceedToChallenge()
        model.confirm(fakePositions.associateWith { "wrong" })
        // The lambda was called exactly once (confirmSession was invoked, returned false).
        assertEquals(1, calls)
        assertIs<OnboardingUiState.WrongAnswers>(model.state.value)
    }

    // ---- confirm: storage unavailable (screen lock not set) ----

    @Test
    fun confirm_storageUnavailable_transitionsToStorageError() {
        val model =
            RecoveryOnboardingViewModel(
                mnemonic = fakeMnemonic,
                challengePositions = fakePositions,
                confirmSession = { _ ->
                    throw SecureStorageUnavailableException(
                        "Secure key storage is unavailable — set a device screen lock.",
                    )
                },
            )
        model.proceedToChallenge()
        model.confirm(fakePositions.associateWith { "any" })
        assertIs<OnboardingUiState.StorageError>(model.state.value)
    }

    @Test
    fun confirm_anyUnexpectedThrow_transitionsToStorageError() {
        val model =
            RecoveryOnboardingViewModel(
                mnemonic = fakeMnemonic,
                challengePositions = fakePositions,
                confirmSession = { _ -> throw RuntimeException("unexpected") },
            )
        model.proceedToChallenge()
        model.confirm(fakePositions.associateWith { "any" })
        // Fail-closed: any throw → StorageError (not a crash).
        assertIs<OnboardingUiState.StorageError>(model.state.value)
    }

    // ---- retry after wrong answers ----

    @Test
    fun retryAfterWrongAnswers_canUpdateAnswerAndConfirmAgain() {
        var callCount = 0
        val model =
            RecoveryOnboardingViewModel(
                mnemonic = fakeMnemonic,
                challengePositions = fakePositions,
                confirmSession = { _ ->
                    callCount++
                    callCount >= 2 // first call fails, second succeeds
                },
            )
        model.proceedToChallenge()
        model.confirm(fakePositions.associateWith { "wrong" })
        assertIs<OnboardingUiState.WrongAnswers>(model.state.value)

        // After first wrong attempt, update an answer.
        // updateAnswer transitions WrongAnswers → Challenge.
        model.updateAnswer(3, "corrected")
        val challengeState = model.state.value
        assertIs<OnboardingUiState.Challenge>(challengeState)

        // Confirm with the updated answers.
        model.confirm(challengeState.answers)
        assertIs<OnboardingUiState.Provisioned>(model.state.value)
    }
}

package com.openwarden.parent.crypto

import com.openwarden.parent.crypto.bip39.Bip39

/**
 * The confirm-back gate (ADR-033 D7 / RECOVERY.md §2): after the 24-word phrase is shown once, the
 * parent must re-type a set of randomly-chosen word positions. The gate stays [State.Unconfirmed]
 * until EVERY challenged position matches; only then does it reach [State.Confirmed]. Fail-closed:
 * any wrong/missing answer keeps it closed, and the caller MUST NOT persist the root key until
 * [State.Confirmed]. Pure + deterministic — the random index choice is injected, so it is fully
 * testable.
 */
class ConfirmGate(
    private val mnemonic: List<String>,
    /** 1-based positions the parent must re-type (RECOVERY §2: 6 random indices). */
    val challengePositions: List<Int>,
) {
    init {
        require(mnemonic.size == Bip39.WORD_COUNT) { "expected ${Bip39.WORD_COUNT}-word mnemonic" }
        require(challengePositions.isNotEmpty()) { "need at least one challenge position" }
        require(challengePositions.all { it in 1..mnemonic.size }) { "challenge position out of range" }
    }

    /**
     * Check the parent's [answers] (keyed by 1-based position). Returns [State.Confirmed] iff every
     * challenged position is answered with the exact word (trimmed, case-insensitive); else
     * [State.Unconfirmed]. Fail-closed: a missing or wrong answer stays unconfirmed.
     */
    fun check(answers: Map<Int, String>): State {
        val allMatch = challengePositions.all { pos ->
            answers[pos]?.trim()?.lowercase() == mnemonic[pos - 1]
        }
        return if (allMatch) State.Confirmed else State.Unconfirmed
    }

    enum class State { Unconfirmed, Confirmed }

    companion object {
        const val DEFAULT_CHALLENGE_COUNT = 6
    }
}

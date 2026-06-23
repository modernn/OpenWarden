package com.openwarden.parent.crypto

import kotlin.test.Test
import kotlin.test.assertEquals

class ConfirmGateTest {
    // ConfirmGate only checks length + positions, not BIP-39 validity, so synthetic words suffice.
    private val mnemonic = (1..24).map { "word$it" }

    private fun gate(vararg positions: Int) = ConfirmGate(mnemonic, positions.toList())

    @Test
    fun allCorrectConfirms() {
        val g = gate(3, 7, 11, 14, 19, 22)
        val answers = g.challengePositions.associateWith { mnemonic[it - 1] }
        assertEquals(ConfirmGate.State.Confirmed, g.check(answers))
    }

    @Test
    fun oneWrongStaysUnconfirmed() {
        val g = gate(3, 7, 11, 14, 19, 22)
        val answers = g.challengePositions.associateWith { mnemonic[it - 1] }.toMutableMap()
        answers[11] = "wrong"
        assertEquals(ConfirmGate.State.Unconfirmed, g.check(answers))
    }

    @Test
    fun missingAnswerStaysUnconfirmed() {
        val g = gate(3, 7, 11)
        val answers = mapOf(3 to mnemonic[2], 7 to mnemonic[6]) // 11 omitted
        assertEquals(ConfirmGate.State.Unconfirmed, g.check(answers))
    }

    @Test
    fun trimAndCaseInsensitive() {
        val g = gate(5)
        assertEquals(ConfirmGate.State.Confirmed, g.check(mapOf(5 to "  WORD5  ")))
    }

    @Test
    fun extraAnswersIgnored() {
        val g = gate(2, 4)
        val answers = mapOf(2 to mnemonic[1], 4 to mnemonic[3], 9 to "irrelevant")
        assertEquals(ConfirmGate.State.Confirmed, g.check(answers))
    }
}

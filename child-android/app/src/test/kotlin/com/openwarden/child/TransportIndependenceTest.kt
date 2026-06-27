package com.openwarden.child

import org.junit.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * ADR-031 D1: authentication is app-layer and transport-independent. [CommandAdmission.decide] is a
 * pure function of the SIGNED object + the pinned key — it takes **no** socket/transport parameter — so
 * a tampered payload is rejected and a genuine one admitted REGARDLESS of how (or how "trustedly") it
 * arrived. This is the invariant that makes adding confidentiality-only TLS (issue #21) safe: TLS never
 * becomes an authentication shortcut, and a MITM on a "trusted" channel still cannot forge a command.
 */
class TransportIndependenceTest {
    private val myId = "child-abcd"
    private val now = 10_000_000L

    private fun cmd(
        type: String = SignedCommand.TYPE_LOCK,
        issuedAt: Long = now,
    ) = SignedCommand(v = 1, type = type, child_device_id = myId, issued_at = issuedAt, sig = "")

    private fun accepts(o: CommandAdmission.Outcome) = o is CommandAdmission.Outcome.Accept

    @Test
    fun `a tampered command is rejected even imagining a fully trusted socket`() {
        val kp = CommandTestSigner.newKeypair()
        // Tamper the wire bytes AFTER signing — exactly what a MITM on a "trusted" channel would do.
        val tampered =
            CommandTestSigner
                .sign(cmd(type = SignedCommand.TYPE_UNLOCK), kp)
                .copy(type = SignedCommand.TYPE_LOCK)
        // decide() has no transport/socket argument; its only inputs are the signed object + pinned key.
        assertFalse(
            accepts(
                CommandAdmission.decide(
                    tampered,
                    SignedCommand.TYPE_LOCK,
                    myId,
                    kp.pubRaw,
                    commandFloor = null,
                    nowMs = now,
                ),
            ),
        )
    }

    @Test
    fun `a genuine command is admitted independent of transport`() {
        val kp = CommandTestSigner.newKeypair()
        val genuine = CommandTestSigner.sign(cmd(type = SignedCommand.TYPE_LOCK), kp)
        assertTrue(
            accepts(
                CommandAdmission.decide(
                    genuine,
                    SignedCommand.TYPE_LOCK,
                    myId,
                    kp.pubRaw,
                    commandFloor = null,
                    nowMs = now,
                ),
            ),
        )
    }
}

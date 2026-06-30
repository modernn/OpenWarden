package com.openwarden.child

import org.junit.Test
import java.util.Base64
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * Host tests for [PairingAdmission.decide] — the pure decision behind the v0.x demo-grade `POST /pair`
 * (ADR-046 D1). No storage / network: the handler supplies `alreadyPaired` and performs the pin.
 */
class PairingAdmissionTest {
    private fun b64(bytes: ByteArray): String = Base64.getEncoder().encodeToString(bytes)

    private val validKey = ByteArray(PairingAdmission.ED25519_PUBKEY_LEN) { it.toByte() }

    @Test
    fun `well-formed 32-byte key on an unpaired child is accepted with the exact bytes`() {
        val outcome = PairingAdmission.decide(b64(validKey), alreadyPaired = false)
        val accept = assertIs<PairingAdmission.Outcome.Accept>(outcome)
        assertContentEquals(validKey, accept.rawPubkey, "Accept must carry the decoded 32 bytes verbatim")
    }

    @Test
    fun `already-paired child refuses without overwriting (first-pairing-only)`() {
        // Even a perfectly valid key must be refused once a parent key is pinned — no re-pin (ADR-046).
        assertIs<PairingAdmission.Outcome.AlreadyPaired>(
            PairingAdmission.decide(b64(validKey), alreadyPaired = true),
        )
    }

    @Test
    fun `already-paired takes precedence over a malformed key`() {
        // The refusal happens before parsing, so a re-pair attempt never even reaches validation.
        assertIs<PairingAdmission.Outcome.AlreadyPaired>(
            PairingAdmission.decide("not-base64!!!", alreadyPaired = true),
        )
    }

    @Test
    fun `missing key is malformed`() {
        assertIs<PairingAdmission.Outcome.Malformed>(PairingAdmission.decide(null, alreadyPaired = false))
        assertIs<PairingAdmission.Outcome.Malformed>(PairingAdmission.decide("   ", alreadyPaired = false))
    }

    @Test
    fun `non-base64 key is malformed`() {
        assertIs<PairingAdmission.Outcome.Malformed>(
            PairingAdmission.decide("!!! not base64 !!!", alreadyPaired = false),
        )
    }

    @Test
    fun `wrong-length key is malformed`() {
        // A valid base64 string that decodes to the wrong number of bytes must be rejected — an
        // Ed25519 public key is exactly 32 bytes.
        val short =
            assertIs<PairingAdmission.Outcome.Malformed>(
                PairingAdmission.decide(b64(ByteArray(16)), alreadyPaired = false),
            )
        assertEquals(true, short.reason.contains("32 bytes"), "reason should name the expected length")
        assertIs<PairingAdmission.Outcome.Malformed>(
            PairingAdmission.decide(b64(ByteArray(33)), alreadyPaired = false),
        )
    }
}

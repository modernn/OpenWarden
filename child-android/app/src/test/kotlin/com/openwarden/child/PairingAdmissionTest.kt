package com.openwarden.child

import org.junit.Test
import java.util.Base64
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

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
    fun `key with high-bit bytes round-trips via the standard base64 alphabet`() {
        // Wire-contract guard (#151 crypto review): the parent encodes the pubkey with
        // java.util.Base64.getEncoder() (STANDARD, padded). A 32-byte key full of 0xFB/0xFF bytes maps
        // to the '+' and '/' chars that ONLY the standard alphabet uses — a url-safe decoder would
        // reject/mis-decode them and pin the wrong key (silent fail-closed-to-broken). This proves the
        // child's PairingAdmission decode uses the matching standard decoder, for the exact byte
        // patterns the all-zero-to-0x1F test keys never exercise.
        val highBitKey = ByteArray(PairingAdmission.ED25519_PUBKEY_LEN) { 0xFB.toByte() }.also { it[0] = 0xFF.toByte() }
        val encoded = b64(highBitKey)
        assertTrue(encoded.contains('+') || encoded.contains('/'), "fixture must exercise the +/ standard-alphabet chars")
        val accept = assertIs<PairingAdmission.Outcome.Accept>(PairingAdmission.decide(encoded, alreadyPaired = false))
        assertContentEquals(highBitKey, accept.rawPubkey, "standard-encoded +// key must decode to the same 32 bytes")
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

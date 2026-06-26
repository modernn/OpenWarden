package com.openwarden.parent.crypto

import com.ionspin.kotlin.crypto.box.Box
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Sealed-box event-encryption conformance (#3, ADR-015 / ADR-044, CRYPTO.md §4,
 * PROTOCOL.md §6). Verifies [SealedBox] (ion-spin libsodium `crypto_box_seal`)
 * against three things the issue requires:
 *
 *  1. **Round-trip + byte-for-byte libsodium interop.** Golden vectors in
 *     `docs/test-vectors/event-log/sealed_envelope.json` were produced by PyNaCl
 *     (real libsodium) with a fixed ephemeral key. Our open() MUST recover the
 *     pinned plaintext from the pinned sealed bytes — proving ion-spin ≡ libsodium.
 *  2. **Child cannot read own log.** A box sealed to the parent's X25519 pubkey
 *     MUST NOT open under any other (child) keypair — the load-bearing property.
 *  3. **Fail-closed.** Tampered / truncated / wrong-key inputs yield
 *     [SealedBox.OpenResult.Failure] — never a thrown exception, never a silent
 *     success (no fail-open monitoring gap).
 *
 * **Why `jvmTest`, not `commonTest` (ADR-044 D2).** Native libsodium loads only on
 * the Kotlin **`jvm()`** target — ion-spin bundles a desktop native there. It is
 * NOT present on the Android host unit-test target (`testDebugUnitTest` fails in the
 * native resource loader) or the iOS target without a simulator, so a `commonTest`
 * here would break `./gradlew check`. The `jvm()` round-trip is itself a complete
 * byte-for-byte interop proof; Android round-trips, if ever needed, are on-device
 * (`androidInstrumentedTest`). No device required for this suite.
 */
@OptIn(ExperimentalUnsignedTypes::class)
class SealedBoxTest {
    // ---- pinned KAT from docs/test-vectors/event-log/sealed_envelope.json ----
    private val recipientPubHex = "a4e09292b651c278b9772c569f5fa9bb13d906b46ab68c9df9dc2b4409f8a209"
    private val recipientPrivHex = "0101010101010101010101010101010101010101010101010101010101010101"
    private val childPubHex = "ce8d3ad1ccb633ec7b70c17814a5c76ecd029685050d344745ba05870e587d59"
    private val childPrivHex = "0202020202020202020202020202020202020202020202020202020202020202"
    private val plaintextUtf8 = "{\"at_ms\":1734307200000,\"data\":{\"pkg\":\"com.example.game\"},\"kind\":\"APP_LAUNCH\"}"
    private val sealedHex =
        "5dfedd3b6bd47f6fa28ee15d969d5bb0ea53774d488bdaf9df1c6e0124b3ef22" +
            "47bc43c8b0ea76ce73afb04fae4124cb56871f67dc2abf9d84b59664a5349ddc" +
            "5cbb9bbd4d7f264e146ac1c5c9a30dd9f2f916592ddb9db62c63e1c6db354c35" +
            "da05446421a64ba6cf3f6f35600c7ce5c73fdf905e59b0df694e86efdb"

    private fun hex(s: String): UByteArray {
        require(s.length % 2 == 0)
        return UByteArray(s.length / 2) { s.substring(it * 2, it * 2 + 2).toInt(16).toUByte() }
    }

    @Test
    fun interopOpensLibsodiumReferenceVector() {
        runBlocking {
            bootstrapCrypto()
            val result = SealedBox.open(hex(recipientPubHex), hex(recipientPrivHex), hex(sealedHex))
            assertTrue(result is SealedBox.OpenResult.Success, "ion-spin must open a real libsodium sealed box")
            assertEquals(
                plaintextUtf8,
                (result as SealedBox.OpenResult.Success).plaintext.decodeToString(),
                "byte-for-byte interop: recovered plaintext must equal the pinned PyNaCl input",
            )
        }
    }

    @Test
    fun sealedLengthIsPlaintextPlusOverhead() {
        assertEquals(plaintextUtf8.encodeToByteArray().size + SealedBox.SEAL_OVERHEAD_BYTES, hex(sealedHex).size)
    }

    @Test
    fun childKeypairCannotDecryptParentSealedBox() {
        runBlocking {
            bootstrapCrypto()
            // The box is sealed to the parent (recipient) pubkey. A child holding only its
            // OWN keypair derives a different shared secret -> Poly1305 fails. This models
            // "child cannot read its own writes": the child never holds the parent priv key.
            val result = SealedBox.open(hex(childPubHex), hex(childPrivHex), hex(sealedHex))
            assertEquals(SealedBox.OpenResult.Failure, result, "child key must NOT decrypt the parent-sealed box")
        }
    }

    @Test
    fun randomRoundTripRecoversPlaintext() {
        runBlocking {
            bootstrapCrypto()
            val kp = Box.keypair()
            val msg = "metadata-only event, no content".encodeToByteArray()
            val sealed = SealedBox.seal(kp.publicKey, msg)
            assertEquals(msg.size + SealedBox.SEAL_OVERHEAD_BYTES, sealed.size)
            val opened = SealedBox.open(kp.publicKey, kp.secretKey, sealed)
            assertTrue(opened is SealedBox.OpenResult.Success)
            assertTrue(msg.contentEquals((opened as SealedBox.OpenResult.Success).plaintext))
        }
    }

    // Sealed layout: [0,32) ephemeral pub | [32,48) Poly1305 MAC tag | [48,N) ciphertext body.
    @Test
    fun tamperedMacTagFailsClosed() {
        runBlocking {
            bootstrapCrypto()
            val sealed = hex(sealedHex)
            // flip a byte INSIDE the 16-byte MAC/tag region (bytes 32-47) -> Poly1305 rejects
            val tampered = sealed.copyOf().also { it[40] = (it[40] xor 0x01u).toUByte() }
            assertEquals(SealedBox.OpenResult.Failure, SealedBox.open(hex(recipientPubHex), hex(recipientPrivHex), tampered))
        }
    }

    @Test
    fun tamperedCiphertextBodyFailsClosed() {
        runBlocking {
            bootstrapCrypto()
            val sealed = hex(sealedHex)
            // flip a byte INSIDE the encrypted plaintext body (>= index 48) -> MAC over ciphertext rejects
            val tampered = sealed.copyOf().also { it[60] = (it[60] xor 0x01u).toUByte() }
            assertEquals(SealedBox.OpenResult.Failure, SealedBox.open(hex(recipientPubHex), hex(recipientPrivHex), tampered))
        }
    }

    @Test
    fun tamperedEphemeralPrefixFailsClosed() {
        runBlocking {
            bootstrapCrypto()
            val sealed = hex(sealedHex)
            // flip a byte INSIDE the 32-byte ephemeral pubkey prefix -> wrong derived nonce/shared secret
            // (a different failure mechanism than the Poly1305-tag case above)
            val tampered = sealed.copyOf().also { it[5] = (it[5] xor 0x01u).toUByte() }
            assertEquals(SealedBox.OpenResult.Failure, SealedBox.open(hex(recipientPubHex), hex(recipientPrivHex), tampered))
        }
    }

    @Test
    fun truncatedBelowOverheadFailsClosed() {
        runBlocking {
            bootstrapCrypto()
            val tooShort = hex(sealedHex).copyOf(20) // < 32-byte ephemeral pub, structurally impossible
            val result = SealedBox.open(hex(recipientPubHex), hex(recipientPrivHex), tooShort)
            assertEquals(SealedBox.OpenResult.Failure, result, "structurally too-short input must fail closed, not throw")
        }
    }

    @Test
    fun truncatedByOneByteFailsClosed() {
        runBlocking {
            bootstrapCrypto()
            // drop the last ciphertext byte: structurally plausible length, but the MAC must reject
            val sealed = hex(sealedHex)
            val nearFull = sealed.copyOf(sealed.size - 1)
            val result = SealedBox.open(hex(recipientPubHex), hex(recipientPrivHex), nearFull)
            assertEquals(SealedBox.OpenResult.Failure, result, "one-byte truncation must fail the MAC, not partially decode")
        }
    }

    @Test
    fun wrongRecipientFailsClosed() {
        runBlocking {
            bootstrapCrypto()
            val kp = Box.keypair()
            val sealed = SealedBox.seal(kp.publicKey, "x".encodeToByteArray())
            // open with an unrelated keypair
            val other = Box.keypair()
            assertEquals(SealedBox.OpenResult.Failure, SealedBox.open(other.publicKey, other.secretKey, sealed))
        }
    }

    @Test
    fun failureIsNotSuccess() {
        // guards the sealed-class contract: Failure must never structurally equal a Success
        assertFalse(SealedBox.OpenResult.Failure == SealedBox.OpenResult.Success(ByteArray(0)))
    }
}

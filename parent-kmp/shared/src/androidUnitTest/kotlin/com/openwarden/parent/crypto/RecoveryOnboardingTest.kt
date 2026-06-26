package com.openwarden.parent.crypto

import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters
import org.bouncycastle.crypto.signers.Ed25519Signer
import java.security.SecureRandom
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The confirm-back onboarding contract (ADR-033 D7 / issue #24 acceptance): the gate blocks until
 * the parent re-types the challenged words; nothing is persisted until then (fail-closed).
 */
class RecoveryOnboardingTest {
    /** Deterministic entropy for a reproducible all-zeros mnemonic. */
    private fun zeroEntropyRandom() =
        object : SecureRandom() {
            override fun nextBytes(bytes: ByteArray) = bytes.fill(0)
        }

    private fun onboarding(storage: SecureKeyStorage) = RecoveryOnboarding(storage, zeroEntropyRandom(), Random(42))

    @Test
    fun wrongAnswersPersistNothing() {
        val storage = FakeSecureKeyStorage()
        val session = onboarding(storage).start()
        val wrong = session.challengePositions.associateWith { "wrongword" }

        assertFalse(session.confirm(wrong))
        assertNull(storage.read())
        assertFalse(StoredRootKeyProvider(storage).isProvisioned())
    }

    @Test
    fun partialAnswersFailClosed() {
        val storage = FakeSecureKeyStorage()
        val session = onboarding(storage).start()
        val partial = session.challengePositions.drop(1).associateWith { session.mnemonic[it - 1] }

        assertFalse(session.confirm(partial))
        assertNull(storage.read())
    }

    @Test
    fun correctAnswersDeriveAndPersist() {
        val storage = FakeSecureKeyStorage()
        val session = onboarding(storage).start()
        // All-zero entropy ⇒ the canonical all-zero mnemonic.
        assertEquals("art", session.mnemonic[23])
        val correct = session.challengePositions.associateWith { session.mnemonic[it - 1] }

        assertTrue(session.confirm(correct))

        val provider = StoredRootKeyProvider(storage)
        assertTrue(provider.isProvisioned())
        val expected = RootKeyDerivation.deriveRootKeys(session.mnemonic)
        assertContentEquals(expected.ed25519Public, provider.rootPublicKey())

        // The persisted key can sign and the signature verifies under the derived public key.
        val msg = "m".encodeToByteArray()
        val sig = provider.sign(msg)!!
        val verifier =
            Ed25519Signer().apply {
                init(false, Ed25519PublicKeyParameters(expected.ed25519Public, 0))
                update(msg, 0, msg.size)
            }
        assertTrue(verifier.verifySignature(sig))
    }

    @Test
    fun challengeCountMatchesDefault() {
        val session = onboarding(FakeSecureKeyStorage()).start()
        assertEquals(ConfirmGate.DEFAULT_CHALLENGE_COUNT, session.challengePositions.size)
        assertTrue(session.challengePositions.all { it in 1..24 })
        assertEquals(session.challengePositions.distinct(), session.challengePositions)
    }
}

package com.openwarden.parent.crypto

import com.openwarden.parent.crypto.bip39.Bip39
import java.security.SecureRandom
import kotlin.random.Random

/**
 * Orchestrates the parent root-key onboarding (ADR-033 D7 / issue #24): generate the recovery
 * phrase, present it once for display, challenge the parent to re-type random positions, and — only
 * once the confirm-back gate passes — derive and persist the root key. **Nothing is persisted before
 * [Session.confirm] returns true**, so a parent who cannot prove they read the phrase never gets a
 * provisioned key (fail-closed).
 *
 * Pure orchestration over [RootKeyDerivation] + [SecureKeyStorage]; the randomness is injected so the
 * whole flow is host-testable. The Compose display/entry screen (FLAG_SECURE) binds to this and is a
 * deferred follow-up (UI scope cut for #24).
 */
class RecoveryOnboarding(
    private val storage: SecureKeyStorage,
    private val entropyRandom: SecureRandom = SecureRandom(),
    private val challengeRandom: Random = Random.Default,
) {
    /** Begin onboarding: a fresh phrase + a fixed set of challenge positions. Persists nothing yet. */
    fun start(challengeCount: Int = ConfirmGate.DEFAULT_CHALLENGE_COUNT): Session {
        val mnemonic = RootKeyDerivation.generateMnemonic(entropyRandom)
        val positions = (1..Bip39.WORD_COUNT).shuffled(challengeRandom).take(challengeCount).sorted()
        return Session(mnemonic, ConfirmGate(mnemonic, positions))
    }

    inner class Session internal constructor(
        /** The 24 words to display once, under FLAG_SECURE (RECOVERY §2). */
        val mnemonic: List<String>,
        private val gate: ConfirmGate,
    ) {
        /** 1-based positions the parent must re-type. */
        val challengePositions: List<Int> get() = gate.challengePositions

        /**
         * Submit the parent's re-typed [answers]. On [ConfirmGate.State.Confirmed], derive the root
         * key and persist it (returns true). Otherwise persist nothing and return false (fail-closed).
         */
        fun confirm(answers: Map<Int, String>): Boolean {
            if (gate.check(answers) != ConfirmGate.State.Confirmed) return false
            val keys = RootKeyDerivation.deriveRootKeys(mnemonic)
            StoredRootKeyProvider.provision(storage, keys)
            keys.wipe()
            return true
        }
    }
}

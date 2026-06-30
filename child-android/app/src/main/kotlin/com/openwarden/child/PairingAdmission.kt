package com.openwarden.child

import java.util.Base64

/**
 * Pure, host-testable admission for the v0.x demo-grade `POST /pair` endpoint (ADR-046 D1).
 *
 * Decides whether to pin a presented parent Ed25519 public key **without** touching storage or the
 * network: the [ApiServer] handler supplies `alreadyPaired` (from `PolicyStore.parentPubkey()`) and,
 * on [Outcome.Accept], performs the actual pin and responds with the real `child_device_id`.
 *
 * Fail-closed:
 *  - **already paired → refuse** ([Outcome.AlreadyPaired]). First-pairing-only: the demo branches'
 *    unconditional overwrite let any LAN host re-pin over an established pairing and then push signed
 *    policy (ADR-046 security delta). Re-pairing is recovery-gated (ADR-043 §7.5), not exposed here.
 *  - **missing / non-base64 / wrong-length key → [Outcome.Malformed]** — never a partial pin.
 */
object PairingAdmission {
    /** Ed25519 public keys are exactly 32 bytes. */
    const val ED25519_PUBKEY_LEN = 32

    sealed interface Outcome {
        /** Well-formed 32-byte key on a not-yet-paired child — the caller should pin [rawPubkey]. */
        class Accept(
            val rawPubkey: ByteArray,
        ) : Outcome

        /** A parent key is already pinned — refuse without overwriting (HTTP 409). */
        data object AlreadyPaired : Outcome

        /** Missing / non-base64 / wrong-length key (HTTP 400). */
        data class Malformed(
            val reason: String,
        ) : Outcome
    }

    /**
     * @param parentPubkeyBase64 the request's `parent_pubkey` (null if the body was missing/unparseable)
     * @param alreadyPaired whether a parent key is already pinned (`PolicyStore.parentPubkey() != null`)
     */
    fun decide(
        parentPubkeyBase64: String?,
        alreadyPaired: Boolean,
    ): Outcome {
        // First-pairing-only: refuse before even parsing the key, so a re-pair attempt can never
        // overwrite an established pairing regardless of the body.
        if (alreadyPaired) return Outcome.AlreadyPaired
        if (parentPubkeyBase64.isNullOrBlank()) {
            return Outcome.Malformed("missing parent_pubkey")
        }
        val raw =
            try {
                Base64.getDecoder().decode(parentPubkeyBase64.trim())
            } catch (e: IllegalArgumentException) {
                return Outcome.Malformed("parent_pubkey is not valid base64")
            }
        if (raw.size != ED25519_PUBKEY_LEN) {
            return Outcome.Malformed("parent_pubkey must be $ED25519_PUBKEY_LEN bytes, got ${raw.size}")
        }
        return Outcome.Accept(raw)
    }
}

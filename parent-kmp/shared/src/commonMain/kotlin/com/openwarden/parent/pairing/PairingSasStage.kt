package com.openwarden.parent.pairing

/**
 * Slice (d) of the parent pairing flow (ADR-025 D5d, ADR-038): the §7.4 six-emoji SAS stage that runs
 * **after** [Section73AttestationVerifier] returns [AttestationOutcome.Accepted]. It derives the
 * confirmation emojis for both sides to display, then captures the human's Match/Mismatch verdict.
 *
 *  - **derive**: pull the parent pubkey snapshot from *this attempt's* QR session (the authoritative
 *    parent keys for the SAS — not the live root key, which could differ if rotated mid-attempt),
 *    combine with the endpoint-decoded child keys + the session nonce, and derive the six emojis.
 *  - **confirm(matched = true)**: the human saw identical emojis — hand the child keys to slice (e)
 *    for pinning. **This slice pins nothing** (ADR-038 D4); pinning is the separate slice (e) issue.
 *  - **confirm(matched = false)**: a MITM (or genuine error) — abort, surface the warning upstream,
 *    and **burn the single-use nonce** (ADR-036 D4 HARD criterion) so a fresh QR is required; no
 *    silent retry on the same nonce.
 *
 * Fail-closed (ADR-038 D5): the SAS is only derivable after Accepted; a malformed parent-key snapshot
 * in the session (our own QR JSON — a programming error, not attacker input) throws rather than
 * deriving a SAS against an unknown key. Not thread-safe: the Android coordinator drives derive →
 * display → confirm under the same `sessionLock` as the endpoint (ADR-036 D5).
 */
class PairingSasStage(
    private val sas: SixEmojiSas,
    private val burner: PairingNonceBurner,
) {
    /**
     * Derive the §7.4 SAS for [post] (which the endpoint already shape-validated and the verifier
     * already Accepted). Returns a [SasChallenge] carrying the emojis to display and the child keys to
     * carry into [confirm].
     */
    fun derive(post: ValidatedPairingPost): SasChallenge {
        val parent =
            parentKeysFromSession(post.session)
                // The QR JSON is parent-authored; a parse failure is a build invariant break, not
                // attacker input. Fail-closed: refuse rather than derive against an unknown key.
                ?: error("pairing session QR payload is malformed — cannot derive SAS (fail-closed)")

        val emojis =
            sas.derive(
                parentEd25519Pub = parent.first,
                parentX25519Pub = parent.second,
                childEd25519Pub = post.childEd25519Pub,
                childX25519Pub = post.childX25519Pub,
                nonce = post.session.nonce(),
            )
        return SasChallenge(emojis, post.childEd25519Pub.copyOf(), post.childX25519Pub.copyOf())
    }

    /**
     * Record the human's verdict for [challenge]. `matched = false` burns the nonce and aborts;
     * `matched = true` yields the child keys for slice (e) to pin. Idempotent burns are the burner's
     * concern; this method calls [PairingNonceBurner.burn] exactly once on a mismatch.
     */
    fun confirm(
        challenge: SasChallenge,
        matched: Boolean,
    ): SasOutcome {
        if (!matched) {
            burner.burn() // single-use nonce: a SAS mismatch burns it (no reuse on retry).
            return SasOutcome.Mismatch
        }
        return SasOutcome.Match(challenge.childEd25519Pub.copyOf(), challenge.childX25519Pub.copyOf())
    }

    /** Parse the parent (ed, x) pubkeys advertised in this attempt's §7.1 QR; `null` if malformed. */
    private fun parentKeysFromSession(session: PairingSession): Pair<ByteArray, ByteArray>? {
        val payload =
            try {
                pairingJson.decodeFromString(PairingQrPayload.serializer(), session.payloadJson)
            } catch (_: Exception) {
                return null
            }
        val ed = Base64Url.decode32(payload.parentEd25519Pub) ?: return null
        val x = Base64Url.decode32(payload.parentX25519Pub) ?: return null
        return ed to x
    }
}

/**
 * A derived, not-yet-confirmed SAS (ADR-038 D4). [emojis] are the six §7.4 emojis both sides display;
 * [childEd25519Pub] / [childX25519Pub] are the exactly-32-byte keys carried into the pin handoff on
 * Match. Defensive copies — the carrier never hands out mutable internals.
 */
class SasChallenge(
    val emojis: List<String>,
    childEd25519Pub: ByteArray,
    childX25519Pub: ByteArray,
) {
    private val ed = childEd25519Pub.copyOf()
    private val x = childX25519Pub.copyOf()

    val childEd25519Pub: ByteArray get() = ed.copyOf()
    val childX25519Pub: ByteArray get() = x.copyOf()
}

/** Outcome of the human's §7.4 compare (ADR-038 D4). */
sealed interface SasOutcome {
    /**
     * The human confirmed identical emojis. [childEd25519Pub] / [childX25519Pub] are ready for slice
     * (e) to pin (ADR-025 D5e). **No pin happens here.**
     */
    class Match(
        childEd25519Pub: ByteArray,
        childX25519Pub: ByteArray,
    ) : SasOutcome {
        private val ed = childEd25519Pub.copyOf()
        private val x = childX25519Pub.copyOf()

        val childEd25519Pub: ByteArray get() = ed.copyOf()
        val childX25519Pub: ByteArray get() = x.copyOf()
    }

    /** The emojis differed — pair aborted, MITM warning surfaced upstream, nonce burned. Nothing pins. */
    data object Mismatch : SasOutcome
}

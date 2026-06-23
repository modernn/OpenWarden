package com.openwarden.parent.pairing

/**
 * Slice (d) of the parent pairing flow (ADR-025 D5d, ADR-038): the §7.4 six-emoji SAS stage that runs
 * **after** [Section73AttestationVerifier] returns [AttestationOutcome.Accepted]. It derives the
 * confirmation emojis for both sides to display, then captures the human's Match/Mismatch verdict.
 *
 *  - **derive**: pull the parent pubkey snapshot from *this attempt's* QR session (the authoritative
 *    parent keys for the SAS — not the live root key, which could differ if rotated mid-attempt),
 *    combine with the endpoint-decoded child keys + the session nonce, and derive the six emojis. The
 *    returned [SasChallenge] is bound to that exact session.
 *  - **confirm(matched = true)**: the human saw identical emojis — hand the child keys to slice (e)
 *    for pinning. **This slice pins nothing** (ADR-038 D4); pinning is the separate slice (e) issue.
 *  - **confirm(matched = false)**: a MITM (or genuine error) — abort, surface the warning upstream,
 *    and **burn the single-use nonce** (ADR-036 D4 HARD criterion) so a fresh QR is required; no
 *    silent retry on the same nonce.
 *
 * **Session-bound confirm (crypto/Codex review HIGH-1/#4):** `confirm` only acts when the challenge's
 * originating session is still the live one. A *stale* challenge — one whose attempt was already
 * replaced (a new QR/nonce) or expired — resolves to [SasOutcome.Stale] and **burns nothing**, so a
 * late Mismatch tap on an abandoned challenge can never burn a *different* attempt's fresh nonce. The
 * challenge's own (already-replaced) nonce is dead by definition, so there is nothing of its to burn.
 *
 * Fail-closed (ADR-038 D5): the SAS is only derivable after Accepted; a malformed parent-key snapshot
 * in the session (our own QR JSON — a programming error, not attacker input) throws rather than
 * deriving a SAS against an unknown key. Stale and Mismatch both refuse to pin.
 *
 * Wiring (ADR-038 D4a, disclosed residual): no production caller drives this stage yet — the
 * coordinator that joins endpoint-`Accepted` → derive → human-compare → [confirm], and the parent UI
 * that surfaces the emojis and captures the tap, land with the pairing-coordinator / slice (e) work.
 * `PairingSasBurnIntegrationTest` proves the mismatch burn reaches the live [SessionAccess] (and that a
 * stale challenge does not). The coordinator that owns the lock (the same `sessionLock` as the
 * endpoint, ADR-036 D5) is future work; drive derive → display → confirm under that single monitor.
 */
class PairingSasStage(
    private val sessions: SessionAccess,
    private val sas: SixEmojiSas,
    private val burner: PairingNonceBurner,
) {
    /**
     * Derive the §7.4 SAS for [post] (which the endpoint already shape-validated and the verifier
     * already Accepted). Returns a [SasChallenge] bound to `post.session`, carrying the emojis to
     * display and the child keys to carry into [confirm].
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
        return SasChallenge(post.session, emojis, post.childEd25519Pub, post.childX25519Pub)
    }

    /**
     * Record the human's verdict for [challenge]. Only acts if the challenge's session is still live
     * (else [SasOutcome.Stale], burning nothing). `matched = false` burns the live nonce and aborts;
     * `matched = true` yields the child keys for slice (e) to pin (no pin here).
     */
    fun confirm(
        challenge: SasChallenge,
        matched: Boolean,
    ): SasOutcome {
        // Session-bound: a stale challenge (its attempt already replaced/expired) must not touch the
        // current live session. Identity match against the live session, exactly like the endpoint's
        // trackedSession identity check.
        if (sessions.active() !== challenge.session) return SasOutcome.Stale

        if (!matched) {
            burner.burn() // single-use nonce: a SAS mismatch burns it (no reuse on retry).
            return SasOutcome.Mismatch
        }
        return SasOutcome.Match(challenge.childEd25519Pub, challenge.childX25519Pub)
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
 * A derived, not-yet-confirmed SAS (ADR-038 D4), bound to the [session] that produced it (so a stale
 * confirm cannot burn a different attempt). [emojis] are the six §7.4 emojis both sides display;
 * [childEd25519Pub] / [childX25519Pub] are the exactly-32-byte keys carried into the pin handoff on
 * Match. Defensive copies — the carrier never hands out mutable internals.
 */
class SasChallenge internal constructor(
    internal val session: PairingSession,
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

    /** The emojis differed — pair aborted, MITM warning surfaced upstream, the live nonce burned. Nothing pins. */
    data object Mismatch : SasOutcome

    /**
     * The challenge's originating session is no longer live (the attempt was replaced or expired). A
     * no-op: nothing pins and **nothing is burned** — the challenge's own nonce is already dead, and
     * the current live attempt (if any) must not be burned by a stale tap.
     */
    data object Stale : SasOutcome
}

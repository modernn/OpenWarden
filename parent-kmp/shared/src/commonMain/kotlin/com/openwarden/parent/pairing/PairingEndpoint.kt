package com.openwarden.parent.pairing

/**
 * Slice (b) of the parent pairing flow (ADR-025 D5b, ADR-036): the pre-pin, mDNS-discovered LAN
 * endpoint that receives the child's §7.2 POST. It is a hardened, fail-closed front door — it does
 * **only** what is safe before any trust anchor exists:
 *
 *  1. per-source rate limit (cheap reject before any work);
 *  2. a single live session must be active (ADR-035 — none/expired ⇒ refuse);
 *  3. body size cap;
 *  4. parse + `v` + **D6 byte-level pubkey validation** (decode → exactly 32 bytes) + light shape
 *     checks on the attestation material;
 *  5. a **per-session attempt cap** counted only over *well-formed* POSTs (so a malformed flood can
 *     never burn the legit session) — exhaustion burns the session, forcing a fresh QR;
 *  6. hand the validated, session-bound post to the slice-(c) [AttestationVerifier] seam.
 *
 * It verifies no attestation, derives no SAS, and **pins nothing**. The verifier's verdict is
 * returned verbatim; slice (b) does not burn the session on a verifier refusal — burning the nonce
 * on a failed attestation / SAS mismatch is slice (c)/(d)'s lifecycle decision (ADR-036 D4).
 *
 * Not thread-safe: [handle] mutates per-session attempt state and touches the (single-thread-confined)
 * session manager via [SessionAccess]. The Android transport adapter serializes every [handle] call
 * and every session touch under one shared monitor (ADR-036 D5); host tests drive it single-threaded.
 */
class PairingEndpoint(
    private val sessions: SessionAccess,
    private val verifier: AttestationVerifier,
    private val rateLimiter: PairingRateLimiter,
    private val maxAttemptsPerSession: Int = DEFAULT_MAX_ATTEMPTS_PER_SESSION,
    private val maxBodyBytes: Int = DEFAULT_MAX_BODY_BYTES,
    private val maxCertChainLen: Int = DEFAULT_MAX_CERT_CHAIN_LEN,
) {
    // Attempt accounting is keyed to the live session by identity; a new session (new nonce) resets it.
    private var trackedSession: PairingSession? = null
    private var attempts = 0

    fun handle(request: PairingRequest): PairingPostResult {
        // (1) Throttle the source before doing any parsing work.
        if (!rateLimiter.tryAcquire(request.sourceId)) {
            return PairingPostResult.Refused(RefusalReason.RATE_LIMITED)
        }

        // (2) A POST is only meaningful while a pairing attempt is live (an expired session self-burns).
        val session = sessions.active() ?: return PairingPostResult.Refused(RefusalReason.NO_SESSION)

        // (3) Bound the body before parsing (defense-in-depth; the adapter also caps the socket read).
        if (request.body.encodeToByteArray().size > maxBodyBytes) {
            return PairingPostResult.Refused(RefusalReason.TOO_LARGE)
        }

        // (4) Parse + version + D6 byte-level pubkey validation + light attestation-shape checks.
        //     Full DER/ECDSA verification is slice (c); here we only reject malformed *shape*.
        val parsed =
            ChildPairingResponse.parse(request.body)
                ?: return PairingPostResult.Refused(RefusalReason.MALFORMED)
        if (parsed.v != WIRE_VERSION) return PairingPostResult.Refused(RefusalReason.BAD_VERSION)

        val edPub =
            Base64Url.decode32(parsed.childEd25519Pub)
                ?: return PairingPostResult.Refused(RefusalReason.BAD_PUBKEY)
        val xPub =
            Base64Url.decode32(parsed.childX25519Pub)
                ?: return PairingPostResult.Refused(RefusalReason.BAD_PUBKEY)

        if (parsed.childAttestationCertChain.isEmpty() ||
            parsed.childAttestationCertChain.size > maxCertChainLen ||
            parsed.childAttestationCertChain.any { it.isEmpty() }
        ) {
            return PairingPostResult.Refused(RefusalReason.BAD_CERT_CHAIN)
        }
        // Even-length hex, and a sane upper bound so a 16 KiB hex blob can't reach the verifier seam.
        // A real ECDSA-P-256 DER sig is ~70-72 bytes (~144 hex); full parse is slice (c).
        if (!isHex(parsed.childBindingSig) || parsed.childBindingSig.length > MAX_BINDING_SIG_HEX) {
            return PairingPostResult.Refused(RefusalReason.BAD_SIG_ENCODING)
        }

        // (5) Per-session attempt cap — counted ONLY here, over well-formed POSTs, so malformed garbage
        //     (rejected above) can never exhaust it and burn the legit session. Exhaustion burns.
        if (session !== trackedSession) {
            trackedSession = session
            attempts = 0
        }
        if (attempts >= maxAttemptsPerSession) {
            sessions.cancel() // burn → a fresh QR is required to retry
            trackedSession = null
            attempts = 0
            return PairingPostResult.Refused(RefusalReason.TOO_MANY_ATTEMPTS)
        }
        attempts += 1

        // (6) Hand off to slice (c). The verdict is returned verbatim; slice (b) pins nothing.
        val outcome = verifier.verify(ValidatedPairingPost(session, parsed, edPub, xPub))
        return PairingPostResult.HandedOff(outcome)
    }

    private fun isHex(s: String): Boolean =
        s.isNotEmpty() && s.length % 2 == 0 &&
            s.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }

    companion object {
        const val WIRE_VERSION = 1

        /** Genuine (well-formed) verify attempts per session before the session is burned (ADR-036 D2). */
        const val DEFAULT_MAX_ATTEMPTS_PER_SESSION = 5

        /** 16 KiB: a ~3-cert StrongBox DER chain is >2 KiB but well under this (ADR-036 D2). */
        const val DEFAULT_MAX_BODY_BYTES = 16 * 1024

        /** Upper bound on cert-chain entries (a real chain is ~3); a longer list is refused. */
        const val DEFAULT_MAX_CERT_CHAIN_LEN = 10

        /** Upper bound on `child_binding_sig` hex length (~144 for P-256 DER); shrinks what reaches (c). */
        const val MAX_BINDING_SIG_HEX = 256
    }
}

/** A raw inbound pairing POST: the source identity (for rate-limiting) and the (already-read) body. */
data class PairingRequest(
    val sourceId: String,
    val body: String,
)

/** Outcome of [PairingEndpoint.handle]; the transport adapter maps it to an HTTP status (ADR-036 D6). */
sealed interface PairingPostResult {
    /**
     * The POST was shape-valid and session-bound and was handed to the verifier seam; [outcome] is the
     * verifier's verdict (slice (c)). With the shipped [RefuseAllAttestationVerifier] this is always a
     * refusal until #96 lands (fail-closed).
     */
    data class HandedOff(
        val outcome: AttestationOutcome,
    ) : PairingPostResult

    /** Refused before handoff (fail-closed). [reason] is a coarse internal code — never echoed to the wire. */
    data class Refused(
        val reason: RefusalReason,
    ) : PairingPostResult
}

/** Coarse refusal codes (internal/testing). The wire response stays generic to avoid a probing oracle. */
enum class RefusalReason {
    RATE_LIMITED,
    NO_SESSION,
    TOO_MANY_ATTEMPTS,
    TOO_LARGE,
    MALFORMED,
    BAD_VERSION,
    BAD_PUBKEY,
    BAD_CERT_CHAIN,
    BAD_SIG_ENCODING,
}

/**
 * A shape-validated, session-bound §7.2 post handed from slice (b) to slice (c). [childEd25519Pub] and
 * [childX25519Pub] are the **already-decoded, exactly-32-byte** keys (D6); [session] carries the live
 * `provisioning_nonce` that (c) binds the attestation challenge + `child_binding_sig` against.
 */
class ValidatedPairingPost(
    val session: PairingSession,
    val response: ChildPairingResponse,
    val childEd25519Pub: ByteArray,
    val childX25519Pub: ByteArray,
)

/**
 * Slice (c) (#96) seam: run §7.3 attestation (ADR-025 D2 checks 1–4b) and drive the §7.4 SAS, deciding
 * whether the pair may proceed toward pinning. Slice (b) only ever hands a validated post here.
 */
interface AttestationVerifier {
    fun verify(post: ValidatedPairingPost): AttestationOutcome
}

sealed interface AttestationOutcome {
    /** Attestation (and, downstream, SAS) may proceed toward pinning (slices (c)/(d)/(e)). */
    data object Accepted : AttestationOutcome

    /** The pair is refused; [reason] is the verifier's. Fail-closed: nothing pins. */
    data class Refused(
        val reason: String,
    ) : AttestationOutcome
}

/**
 * The slice-(b)-shipped default verifier: slice (c) is not implemented yet (#96), so every pair is
 * **refused** (fail-closed — a stub must never let a key pin). #96 replaces this with the real
 * attestation + SAS verifier.
 */
object RefuseAllAttestationVerifier : AttestationVerifier {
    override fun verify(post: ValidatedPairingPost): AttestationOutcome =
        AttestationOutcome.Refused("attestation verification not yet implemented (#96)")
}

/**
 * Serialized access to the single pending pairing session (ADR-035's deferred cross-thread item,
 * ADR-036 D5). The Android adapter implements this under the shared pairing-coordinator monitor; host
 * tests use [DirectSessionAccess] and drive the endpoint single-threaded.
 */
interface SessionAccess {
    /** The live session, or `null` if none / expired (expiry self-burns). */
    fun active(): PairingSession?

    /** Burn the pending session (on attempt-cap exhaustion). */
    fun cancel()
}

/** Direct, **non-thread-safe** [SessionAccess] over a [PairingSessionManager] (host tests; the Android adapter wraps this in a lock). */
class DirectSessionAccess(
    private val manager: PairingSessionManager,
) : SessionAccess {
    override fun active(): PairingSession? = manager.active()

    override fun cancel() = manager.cancel()
}

/**
 * Fixed-window per-source rate limiter (ADR-036 D2). Deterministic via the injected [nowMs] clock so it
 * is host-testable. Throttles a hostile LAN peer before any parsing work; stale source windows are
 * pruned to bound memory.
 */
class PairingRateLimiter(
    private val nowMs: () -> Long,
    private val maxPerWindow: Int = DEFAULT_MAX_PER_WINDOW,
    private val windowMs: Long = DEFAULT_WINDOW_MS,
) {
    private class Window(
        var startMs: Long,
        var count: Int,
    )

    private val bySource = HashMap<String, Window>()

    /** `true` if the request is within the source's budget (and counts it); `false` = throttled. */
    fun tryAcquire(source: String): Boolean {
        val now = nowMs()
        val w = bySource[source]
        if (w == null || now - w.startMs >= windowMs) {
            pruneStale(now)
            bySource[source] = Window(now, 1)
            return true
        }
        if (w.count >= maxPerWindow) return false
        w.count += 1
        return true
    }

    private fun pruneStale(now: Long) {
        val it = bySource.iterator()
        while (it.hasNext()) {
            if (now - it.next().value.startMs >= windowMs) it.remove()
        }
    }

    companion object {
        const val DEFAULT_MAX_PER_WINDOW = 10
        const val DEFAULT_WINDOW_MS = 10_000L
    }
}

package com.openwarden.parent.pairing

import com.openwarden.parent.crypto.RootKeyProvider

/**
 * Owns the single in-memory pending pairing session (ADR-035, implementing ADR-025 D5a). It mints
 * the CSPRNG nonce, assembles the §7.1 QR payload from the parent root pubkeys, and drives the
 * lifecycle (single-active, TTL expiry, single-use burn) that slices (b)–(e) consume.
 *
 * Fail-closed: no parent root key ⇒ no session and no QR (D7); every terminal path — a new start,
 * expiry, cancel, or consume — burns the pending nonce (D5/D6).
 *
 * Threading: confined to the pairing coordinator (single-threaded UI use for slice (a)). The (b)
 * endpoint, which calls [consume] off a network thread, MUST marshal that call onto the same context
 * — cross-thread synchronization lands with that slice (there is no `synchronized` in commonMain).
 */
class PairingSessionManager(
    private val rootKeys: RootKeyProvider,
    private val nonceSource: PairingNonceSource,
    private val nowMs: () -> Long,
    private val ttlMs: Long = DEFAULT_TTL_MS,
    private val transportHints: TransportHints = TransportHints(mdns = DEFAULT_MDNS),
) {
    private var session: PairingSession? = null

    /**
     * Begin a pairing attempt. Burns any prior pending session first (D5). Returns
     * [PairingStartResult.NotProvisioned] — and creates no session — if the parent has no confirmed
     * recovery phrase yet, or its key material is malformed (D7, fail-closed).
     */
    fun start(): PairingStartResult {
        cancel() // D5: a fresh attempt invalidates the prior nonce.

        val edPub = rootKeys.rootPublicKey() ?: return PairingStartResult.NotProvisioned
        val xPub = rootKeys.encryptionPublicKey() ?: return PairingStartResult.NotProvisioned
        // D7: refuse malformed key material rather than emit a bad QR.
        if (edPub.size != 32 || xPub.size != 32) return PairingStartResult.NotProvisioned

        val nonce = nonceSource.freshNonce()
        require(nonce.size == 32) { "PairingNonceSource must return 32 bytes, got ${nonce.size}" }

        val payload =
            PairingQrPayload(
                v = WIRE_VERSION,
                parentEd25519Pub = Base64Url.encode(edPub),
                parentX25519Pub = Base64Url.encode(xPub),
                provisioningNonce = Base64Url.encode(nonce),
                transportHints = transportHints,
            )
        val newSession = PairingSession(payload.toJson(), nonce, nowMs(), ttlMs)
        session = newSession
        return PairingStartResult.Started(newSession)
    }

    /** The live pending session, or `null` if none or expired. Expiry burns it (D3/D6). */
    fun active(): PairingSession? {
        val s = session ?: return null
        if (s.isExpired(nowMs())) {
            cancel()
            return null
        }
        return s
    }

    /**
     * Take and burn the live session for the success handoff (driven by slice (e)). Returns `null`
     * if there is no live session (none or expired) — single-use (D6).
     */
    fun consume(): PairingSession? {
        val s = active() ?: return null
        session = null
        return s
    }

    /**
     * Burn any pending session (D6). Called on a new start, an explicit abort, an attestation
     * failure (slice c), or a SAS mismatch (slice d).
     */
    fun cancel() {
        session = null
    }

    companion object {
        const val WIRE_VERSION = 1

        /** 5 min: covers QR scan + StrongBox K_bind keygen + child POST, bounds the live window (D3). */
        const val DEFAULT_TTL_MS = 300_000L

        const val DEFAULT_MDNS = "_openwarden._tcp.local"
    }
}

/** Result of [PairingSessionManager.start]. */
sealed class PairingStartResult {
    /** A pairing session was created; [session] carries the §7.1 QR payload to display. */
    data class Started(
        val session: PairingSession,
    ) : PairingStartResult()

    /** Parent has no confirmed recovery phrase yet (ADR-033) — no QR (ADR-035 D7, fail-closed). */
    object NotProvisioned : PairingStartResult()
}

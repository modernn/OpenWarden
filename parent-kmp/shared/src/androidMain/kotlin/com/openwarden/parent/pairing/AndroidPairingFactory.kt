package com.openwarden.parent.pairing

import android.content.Context
import com.openwarden.parent.crypto.RootKeyProvider
import com.openwarden.parent.policy.AndroidPairedChildStore

/**
 * Assembles the whole parent §7 pairing flow (ADR-043 slice f) from the Android seams and hands back a
 * ready [PairingController] the UI drives with `begin()` / `confirm()` / `cancel()` while collecting its
 * `phase`. This is the one place the (a)–(e) pieces + the slice-(f) controller + the Ktor listener are
 * wired together; nothing else in androidApp needs to know the seam graph.
 *
 * **One lock (ADR-043 D3 / ADR-036 D5).** A single [sessionLock] object is shared by the [PairingServer]
 * (which holds it around every `endpoint.handle`) **and** the [PairingMonitor] the controller uses for
 * `begin`/`confirm`/`cancel`. So a network POST (`verify`) and a UI tap (`confirm`) are mutually
 * exclusive across every session/stage/store touch — there is exactly one monitor for the flow.
 *
 * **Listener lifecycle (ADR-043 D4).** The controller's [PairingTransport] wraps `PairingServer`
 * start/stop, so the `/pair` port is bound only during a live attempt and torn down on every terminal
 * phase / cancel.
 *
 * **Fail-closed attestation (ADR-037 D3).** [googleRootSpkiDer] is the pinned Tier-1 trust anchor; an
 * empty / wrong pin means no allow-listed root, so every attestation **refuses** (the committed real
 * Google-root SPKI + the on-device StrongBox chain remain the inherited HARD pre-prod gate — until then,
 * and on any emulator, the flow runs end-to-end up to a fail-closed `ATTESTATION_FAILED`).
 */
object AndroidPairingFactory {
    fun create(
        context: Context,
        rootKeys: RootKeyProvider,
        googleRootSpkiDer: ByteArray,
        port: Int = PairingServer.DEFAULT_PORT,
    ): PairingController {
        // The single monitor object shared by the server and the controller (ADR-043 D3).
        val sessionLock = Any()

        val sessionManager =
            PairingSessionManager(
                rootKeys = rootKeys,
                nonceSource = AndroidPairingNonceSource(),
                nowMs = { System.currentTimeMillis() },
            )

        // Non-thread-safe access over the manager; every call is made under sessionLock by its callers
        // (the server for the endpoint path, the monitor for begin/confirm/cancel).
        val access = DirectSessionAccess(sessionManager)

        // The single-use-nonce burner the verifier (on refuse) and the SAS stage (on mismatch) share; both
        // are invoked under sessionLock, so the burn can never race a concurrent POST (ADR-036 D5).
        val burner = PairingNonceBurner { sessionManager.cancel() }

        // Disabled-gate hardening (crypto review MED-1): when no Tier-1 root is pinned yet, refuse EVERY
        // attestation **explicitly** (with the same burn-on-refuse as the real verifier, ADR-037 D2) rather
        // than relying on the empty-SPKI-vs-empty-pin coincidence inside AttestationPolicy. Fail-closed:
        // nothing can pin until the real Google-root SPKI is committed (ADR-043 D6, ADR-037 D3).
        val realVerifier =
            if (googleRootSpkiDer.isEmpty()) {
                object : AttestationVerifier {
                    override fun verify(post: ValidatedPairingPost): AttestationOutcome {
                        burner.burn()
                        return AttestationOutcome.Refused("attestation disabled: no pinned root (fail-closed)")
                    }
                }
            } else {
                AndroidAttestationVerifierFactory.create(googleRootSpkiDer, burner)
            }
        val sasStage = PairingSasStage(access, SixEmojiSas(BouncyCastleSasKdf()), burner)
        val store = AndroidPairedChildStore(context)
        val coordinator = PairingPinCoordinator(sasStage, store, access)
        val rateLimiter = PairingRateLimiter(nowMs = { System.currentTimeMillis() })
        val monitor = PairingMonitor { block -> synchronized(sessionLock) { block() } }

        // Break the controller <-> server cycle: the endpoint needs a verifier, the controller is that
        // verifier, the controller needs the transport, the transport needs the server, the server needs
        // the endpoint. A thin forwarder lets the controller be assigned last; verify() is only ever
        // called at request time, well after assignment.
        lateinit var controller: PairingController
        val forwardingVerifier =
            object : AttestationVerifier {
                override fun verify(post: ValidatedPairingPost): AttestationOutcome = controller.verify(post)
            }
        val endpoint =
            PairingEndpoint(
                sessions = access,
                verifier = forwardingVerifier,
                rateLimiter = rateLimiter,
            )
        val server = PairingServer(endpoint, sessionLock, port)
        val transport =
            object : PairingTransport {
                override fun start() = server.start()

                override fun stop() = server.stop()
            }

        controller =
            PairingController(
                sessions = sessionManager,
                sasStage = sasStage,
                coordinator = coordinator,
                attestation = realVerifier,
                transport = transport,
                monitor = monitor,
            )
        return controller
    }
}

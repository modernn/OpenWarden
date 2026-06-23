package com.openwarden.parent.pairing

import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.cio.CIO
import io.ktor.server.cio.CIOApplicationEngine
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.request.header
import io.ktor.server.request.receiveText
import io.ktor.server.response.respondText
import io.ktor.server.routing.post
import io.ktor.server.routing.routing

/**
 * Thin Android transport adapter for the slice-(b) pairing endpoint (ADR-036 D6). It owns no
 * validation logic — it reads the §7.2 body off the LAN, calls the pure [PairingEndpoint.handle], and
 * maps the result to a coarse HTTP status (no reason is echoed, so the endpoint is not a probing
 * oracle, ADR-036 D3).
 *
 * Thread marshaling (ADR-036 D5, resolving ADR-035's deferred item): Ktor serves requests
 * concurrently, but [PairingEndpoint] and the [PairingSessionManager] are single-thread-confined, so
 * every [PairingEndpoint.handle] call runs under [sessionLock]. **The pairing coordinator MUST hold
 * the same [sessionLock]** around its own `PairingSessionManager.start()/cancel()/consume()` calls;
 * that one shared monitor is the whole cross-thread contract.
 *
 * Lifecycle: [start] binds `0.0.0.0:`[port] (mirroring the child `ApiServer`); the caller starts it
 * when a pairing attempt begins and [stop]s it when the attempt ends. (mDNS *advertising* of this
 * endpoint rides on the parent-side mDNS work — ADR-036 residual — until then the child reaches it by
 * address.)
 */
class PairingServer(
    private val endpoint: PairingEndpoint,
    private val sessionLock: Any,
    private val port: Int = DEFAULT_PORT,
    private val maxBodyBytes: Int = PairingEndpoint.DEFAULT_MAX_BODY_BYTES,
) {
    private var engine: EmbeddedServer<CIOApplicationEngine, CIOApplicationEngine.Configuration>? = null

    fun start() {
        stop() // idempotent: tear down any prior engine before rebinding the port (no orphaned listener).
        engine =
            embeddedServer(CIO, port = port, host = "0.0.0.0") {
                routing {
                    post(PAIR_PATH) {
                        // Hard ingress bound (fail-closed): the §7.2 body is a small fixed-length JSON, so
                        // REQUIRE a Content-Length within the cap and reject anything else BEFORE reading.
                        // A chunked / no-Content-Length / over-cap request is refused without buffering its
                        // body — closing the pre-auth unbounded-read DoS (a lying low length only bounds the
                        // read further). The pure handler re-checks the actual bytes as belt-and-suspenders.
                        val declaredLen = call.request.header(HttpHeaders.ContentLength)?.toLongOrNull()
                        if (declaredLen == null || declaredLen > maxBodyBytes) {
                            call.respondText(REFUSED_BODY, status = HttpStatusCode.PayloadTooLarge)
                            return@post
                        }
                        val source = call.request.local.remoteHost
                        val body = call.receiveText()
                        val result =
                            synchronized(sessionLock) {
                                endpoint.handle(PairingRequest(sourceId = source, body = body))
                            }
                        val (status, payload) = statusFor(result)
                        call.respondText(payload, status = status)
                    }
                }
            }.start(wait = false)
    }

    fun stop() {
        engine?.stop(GRACE_MS, TIMEOUT_MS)
        engine = null
    }

    private fun statusFor(result: PairingPostResult): Pair<HttpStatusCode, String> =
        when (result) {
            is PairingPostResult.HandedOff -> {
                when (result.outcome) {
                    // Shape-valid + handed to (c); the pin/SAS happens downstream. Not "OK" — only "received".
                    is AttestationOutcome.Accepted -> HttpStatusCode.Accepted to RECEIVED_BODY

                    // Attestation/SAS refused the pair (fail-closed) — incl. the #95 RefuseAll default.
                    is AttestationOutcome.Refused -> HttpStatusCode.Forbidden to REFUSED_BODY
                }
            }

            is PairingPostResult.Refused -> {
                when (result.reason) {
                    RefusalReason.RATE_LIMITED,
                    RefusalReason.TOO_MANY_ATTEMPTS,
                    -> HttpStatusCode.TooManyRequests to REFUSED_BODY

                    RefusalReason.TOO_LARGE -> HttpStatusCode.PayloadTooLarge to REFUSED_BODY

                    // NO_SESSION / MALFORMED / BAD_* collapse to a single generic 400 (no oracle).
                    else -> HttpStatusCode.BadRequest to REFUSED_BODY
                }
            }
        }

    companion object {
        /** §7.2 pairing endpoint path the child POSTs to. */
        const val PAIR_PATH = "/pair"

        /** Distinct from the child `ApiServer` port (7180); the parent listens for the pairing POST here. */
        const val DEFAULT_PORT = 7181

        private const val GRACE_MS = 1000L
        private const val TIMEOUT_MS = 2000L

        // Generic bodies — never leak which check failed.
        private const val REFUSED_BODY = "{\"error\":\"REFUSED\"}"
        private const val RECEIVED_BODY = "{\"status\":\"received\"}"
    }
}

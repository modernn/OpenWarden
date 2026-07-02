package com.openwarden.child

import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get

/**
 * `GET /spki-assertion` — the child serves a [SpkiAssertion] vouching (via its Ed25519 **identity** key)
 * for the SPKI of the live TLS leaf the parent just negotiated (ADR-031 D2/D8). The parent pinned that
 * identity key at pairing and accepts the channel only if this assertion matches the SPKI of the cert
 * it *actually* saw on the wire **and** verifies against the pinned key ([SpkiBinding.verify]) — so
 * serving the assertion over the same channel is safe: an MITM presenting its own cert cannot forge a
 * matching, identity-signed assertion.
 *
 * **Fail-closed (ADR-031 D4, no trust-on-first-use):** with no TLS leaf ([leafSpkiDer] returns null) or
 * no identity key ([identityKeyProvider] pre-pairing / an emulator without StrongBox), the signer
 * produces no assertion, so the endpoint returns **204 No Content** and the parent's verifier rejects.
 * There is no path that serves an unsigned or best-effort assertion.
 */
fun Route.spkiAssertionRoute(
    leafSpkiDer: () -> ByteArray?,
    identityKeyProvider: IdentityKeyProvider,
) {
    get("/spki-assertion") {
        val assertion = leafSpkiDer()?.let { SpkiBindingSigner.assertFor(it, identityKeyProvider) }
        if (assertion == null) {
            call.respond(HttpStatusCode.NoContent)
        } else {
            call.respond(assertion)
        }
    }
}

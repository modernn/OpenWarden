package com.openwarden.child

import kotlinx.serialization.Serializable

/**
 * A child-signed vouch that a TLS leaf certificate's SPKI belongs to this child (ADR-031 D2) — the
 * "signed note" that gives the otherwise-self-signed LAN TLS cert a provenance tied to the child
 * Ed25519 **identity** key the parent pinned at pairing (ADR-025). It closes red-team TR1: adding TLS
 * without this would leave a trust-on-first-use window a LAN MITM (ARP/mDNS spoof of
 * `_openwarden._tcp`) wins on first connect.
 *
 * Signed by the **child identity** key over the RFC 8785 JCS canonical bytes of the object minus
 * `sig` — the identical rule as [SignedBundle]/[SignedCommand]/[SignedHeartbeat] (ADR-015/019,
 * one signing rule). Note those three are signed by the **parent** key; this is signed by the
 * **child** key — a second, independent domain separation on top of the disjoint object shape.
 *
 * Wire keys are the property names (snake_case). Defaults exist only so a partial object still
 * parses; an empty `spki_sha256` or `sig` can never verify (fail-closed) — see [SpkiBinding.verify].
 *
 * `spki_sha256` is base64url(SHA-256(DER SubjectPublicKeyInfo)) — RFC 7469 SPKI-pin semantics, so the
 * pin survives a cert renewal that keeps the same key.
 */
@Serializable
data class SpkiAssertion(
    val v: Int,
    val spki_sha256: String = "",
    val sig: String = "",
)

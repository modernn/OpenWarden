package com.openwarden.parent.transport

import com.openwarden.parent.pairing.SpkiAssertion
import com.openwarden.parent.pairing.SpkiBindingVerifier
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters
import org.bouncycastle.crypto.signers.Ed25519Signer
import org.junit.Test
import java.security.SecureRandom
import kotlin.test.assertTrue

/**
 * The parent-side live SPKI-pinning TLS connector (ADR-031 D9). MockWebServer + okhttp-tls stand up a
 * REAL HTTPS server with a HeldCertificate leaf, and a real Bouncy Castle Ed25519 twin signer produces
 * genuine/forged assertions — so the whole capture -> verify -> fail-closed path runs over an actual TLS
 * handshake with real cert capture, on the host JVM. This is the parent half of the #21 spoof-reject
 * acceptance, proven deterministically.
 */
class PinnedChildConnectorTest {
    private val json = Json { encodeDefaults = true }

    private data class Id(
        val priv: Ed25519PrivateKeyParameters,
        val pub: ByteArray,
    )

    private fun newId(): Id {
        val priv = Ed25519PrivateKeyParameters(SecureRandom())
        return Id(priv, priv.generatePublicKey().encoded)
    }

    /** Produce the child-side wire JSON of an assertion for [spkiDer], signed by [id] (BC Ed25519). */
    private fun assertionJson(
        spkiDer: ByteArray,
        id: Id,
    ): String {
        val unsigned = SpkiAssertion(v = 1, spkiSha256 = SpkiBindingVerifier.spkiSha256(spkiDer))
        val body = SpkiBindingVerifier.canonicalBody(unsigned)
        val sig =
            Ed25519Signer()
                .apply {
                    init(true, id.priv)
                    update(body, 0, body.size)
                }.generateSignature()
        val signed = unsigned.copy(sig = sig.joinToString("") { "%02x".format(it) })
        return json.encodeToString(SpkiAssertion.serializer(), signed)
    }

    /** An HTTPS MockWebServer presenting [leaf]; enqueues [response] for the connector's probe GET. */
    private fun httpsServer(
        leaf: HeldCertificate,
        response: MockResponse,
    ): MockWebServer {
        val certs = HandshakeCertificates.Builder().heldCertificate(leaf).build()
        return MockWebServer().apply {
            useHttps(certs.sslSocketFactory(), false)
            enqueue(response)
            start()
        }
    }

    private fun leafCert(): HeldCertificate = HeldCertificate.Builder().addSubjectAlternativeName("localhost").build()

    private fun okBody(body: String) = MockResponse().setBody(body).setHeader("Content-Type", "application/json")

    @Test
    fun `genuine assertion for the negotiated leaf verifies - accept`() {
        val leaf = leafCert()
        val child = newId()
        val leafSpki = leaf.certificate.publicKey.encoded
        val server = httpsServer(leaf, okBody(assertionJson(leafSpki, child)))
        try {
            val outcome = runBlocking { PinnedChildConnector().connect(server.hostName, server.port, child.pub) }
            assertTrue(outcome is PinnedChildConnector.Outcome.Verified, "expected Verified, got $outcome")
            (outcome as PinnedChildConnector.Outcome.Verified).client.close()
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun `assertion signed by a different identity key - reject (D2 c4)`() {
        val leaf = leafCert()
        val genuine = newId()
        val attacker = newId()
        val leafSpki = leaf.certificate.publicKey.encoded
        // Server serves an assertion the ATTACKER signed for the real leaf; parent pins the GENUINE key.
        val server = httpsServer(leaf, okBody(assertionJson(leafSpki, attacker)))
        try {
            val outcome = runBlocking { PinnedChildConnector().connect(server.hostName, server.port, genuine.pub) }
            assertTrue(outcome is PinnedChildConnector.Outcome.Rejected, "expected Rejected, got $outcome")
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun `spoofed responder presents its own cert - reject (D2 c3, the acceptance)`() {
        // The MITM presents its OWN leaf but replays an assertion the genuine child signed for a DIFFERENT
        // cert. Capture sees the MITM leaf; the assertion's spki_sha256 is for the other cert ⇒ c3 mismatch.
        val mitmLeaf = leafCert()
        val genuineLeaf = leafCert()
        val child = newId()
        val server = httpsServer(mitmLeaf, okBody(assertionJson(genuineLeaf.certificate.publicKey.encoded, child)))
        try {
            val outcome = runBlocking { PinnedChildConnector().connect(server.hostName, server.port, child.pub) }
            assertTrue(outcome is PinnedChildConnector.Outcome.Rejected, "expected Rejected, got $outcome")
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun `child serves 204 no assertion - reject (fail-closed, no TOFU)`() {
        val leaf = leafCert()
        val child = newId()
        val server = httpsServer(leaf, MockResponse().setResponseCode(204))
        try {
            val outcome = runBlocking { PinnedChildConnector().connect(server.hostName, server.port, child.pub) }
            assertTrue(outcome is PinnedChildConnector.Outcome.Rejected, "expected Rejected, got $outcome")
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun `malformed assertion body - reject (fail-closed)`() {
        val leaf = leafCert()
        val child = newId()
        val server = httpsServer(leaf, okBody("this is not json"))
        try {
            val outcome = runBlocking { PinnedChildConnector().connect(server.hostName, server.port, child.pub) }
            assertTrue(outcome is PinnedChildConnector.Outcome.Rejected, "expected Rejected, got $outcome")
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun `no pinned identity key - reject without trusting anything (pre-pairing)`() {
        // Fail-closed: with no trust anchor the connector must reject outright (never TOFU-accept).
        val outcome = runBlocking { PinnedChildConnector().connect("127.0.0.1", 1, pinnedIdentityPubkey = null) }
        assertTrue(outcome is PinnedChildConnector.Outcome.Rejected, "expected Rejected, got $outcome")
    }
}

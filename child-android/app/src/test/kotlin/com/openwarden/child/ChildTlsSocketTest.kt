package com.openwarden.child

import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.engine.ApplicationEngine
import io.ktor.server.engine.applicationEngineEnvironment
import io.ktor.server.engine.embeddedServer
import io.ktor.server.engine.sslConnector
import io.ktor.server.netty.Netty
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.routing.routing
import kotlinx.serialization.json.Json
import org.junit.Test
import java.net.ServerSocket
import java.net.URL
import java.security.SecureRandom
import java.security.cert.X509Certificate
import javax.net.ssl.HostnameVerifier
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.X509TrustManager
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The LIVE child TLS socket (ADR-031 D8): a real Netty + `sslConnector` server terminating TLS with a
 * [TlsLeaf], serving the child-identity-signed `/spki-assertion`. Because D8 chose a BC-software leaf,
 * the whole handshake + binding runs on the plain JVM — the live analog of the D2 verifier tests.
 *
 * Proves, over a completed handshake:
 *  - a genuine assertion for the **negotiated** leaf verifies against the pinned identity key (accept);
 *  - the assertion is bound to the cert on the wire (a substituted cert ⇒ reject);
 *  - authentication is transport-independent (D1): a trusted TLS socket does NOT make an assertion
 *    signed by the wrong identity key verify;
 *  - fail-closed (D4): with no identity key the endpoint serves 204 and there is no TOFU.
 */
class ChildTlsSocketTest {
    private fun freePort(): Int = ServerSocket(0).use { it.localPort }

    private fun serverWith(
        port: Int,
        leaf: TlsLeaf,
        provider: IdentityKeyProvider,
    ): ApplicationEngine =
        embeddedServer(
            Netty,
            applicationEngineEnvironment {
                sslConnector(
                    keyStore = leaf.keyStore,
                    keyAlias = leaf.alias,
                    keyStorePassword = { leaf.password },
                    privateKeyPassword = { leaf.password },
                ) {
                    this.port = port
                    host = "127.0.0.1"
                }
                module {
                    install(ContentNegotiation) { json() }
                    routing { spkiAssertionRoute({ leaf.spkiDer }, provider) }
                }
            },
        ).start(wait = false)

    /** Captures the leaf the server presents during the handshake — exactly the cert whose SPKI the real
     *  parent binds against the identity-signed assertion. Trusts everything: trust is app-layer (D1),
     *  never the TLS PKI. A fresh instance per request avoids cross-call contamination. */
    private class CapturingTrust : X509TrustManager {
        @Volatile var leaf: X509Certificate? = null

        override fun checkClientTrusted(
            chain: Array<out X509Certificate>?,
            authType: String?,
        ) {}

        override fun checkServerTrusted(
            chain: Array<out X509Certificate>?,
            authType: String?,
        ) {
            leaf = chain?.firstOrNull()
        }

        override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
    }

    /** GET over TLS with retry (Netty binds asynchronously); returns (status, body, negotiated leaf SPKI). */
    private fun fetch(
        port: Int,
        path: String,
    ): Triple<Int, String, ByteArray> {
        var last: Exception? = null
        repeat(50) {
            val tm = CapturingTrust()
            try {
                val ssl = SSLContext.getInstance("TLS").apply { init(null, arrayOf(tm), SecureRandom()) }
                val conn = URL("https://127.0.0.1:$port$path").openConnection() as HttpsURLConnection
                conn.sslSocketFactory = ssl.socketFactory
                conn.hostnameVerifier = HostnameVerifier { _, _ -> true }
                conn.connectTimeout = 2000
                conn.readTimeout = 2000
                val code = conn.responseCode
                val stream = if (code in 200..299) conn.inputStream else conn.errorStream
                val body = stream?.bufferedReader()?.use { r -> r.readText() } ?: ""
                conn.disconnect()
                // The leaf captured during the handshake — the SPKI the parent verifies the assertion against.
                val spki = tm.leaf?.publicKey?.encoded ?: ByteArray(0)
                return Triple(code, body, spki)
            } catch (e: Exception) {
                last = e
                Thread.sleep(100)
            }
        }
        throw last ?: IllegalStateException("no response")
    }

    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `genuine assertion for the negotiated leaf verifies over the live TLS socket`() {
        val leaf = TlsLeaf.generate()
        val child = FakeIdentityKeyProvider.withNewKey()
        val port = freePort()
        val engine = serverWith(port, leaf, child)
        try {
            val (code, body, presentedSpki) = fetch(port, "/spki-assertion")
            assertEquals(200, code)
            // The cert the client actually negotiated is the leaf we minted.
            assertTrue(presentedSpki.contentEquals(leaf.spkiDer))
            val assertion = json.decodeFromString(SpkiAssertion.serializer(), body)
            // The exact decision the parent runs on the SPKI of the completed handshake ⇒ accept.
            assertTrue(SpkiBinding.verify(assertion, presentedSpki, child.identityPublicKey()))
        } finally {
            engine.stop(100, 500)
        }
    }

    @Test
    fun `assertion is bound to the presented cert - a substituted cert is rejected`() {
        val leaf = TlsLeaf.generate()
        val other = TlsLeaf.generate() // an MITM's own cert
        val child = FakeIdentityKeyProvider.withNewKey()
        val port = freePort()
        val engine = serverWith(port, leaf, child)
        try {
            val (_, body, presentedSpki) = fetch(port, "/spki-assertion")
            val assertion = json.decodeFromString(SpkiAssertion.serializer(), body)
            assertTrue(SpkiBinding.verify(assertion, presentedSpki, child.identityPublicKey()))
            // Replay the genuine assertion but present a different leaf SPKI ⇒ reject (D2 check 3).
            assertFalse(SpkiBinding.verify(assertion, other.spkiDer, child.identityPublicKey()))
        } finally {
            engine.stop(100, 500)
        }
    }

    @Test
    fun `a trusted TLS socket does not authenticate an assertion signed by the wrong identity key (D1)`() {
        val leaf = TlsLeaf.generate()
        val child = FakeIdentityKeyProvider.withNewKey()
        val attacker = FakeIdentityKeyProvider.withNewKey()
        val port = freePort()
        val engine = serverWith(port, leaf, child)
        try {
            val (_, body, presentedSpki) = fetch(port, "/spki-assertion")
            val assertion = json.decodeFromString(SpkiAssertion.serializer(), body)
            // Even over a fully-completed TLS channel, an assertion is admitted only by the pinned
            // identity key — pin the attacker's key ⇒ reject (auth is transport-independent, D1/D2 c4).
            assertFalse(SpkiBinding.verify(assertion, presentedSpki, attacker.identityPublicKey()))
        } finally {
            engine.stop(100, 500)
        }
    }

    @Test
    fun `fail-closed - no identity key serves 204 and there is no trust-on-first-use`() {
        val leaf = TlsLeaf.generate()
        val port = freePort()
        val engine = serverWith(port, leaf, NotProvisionedIdentityKeyProvider)
        try {
            val (code, body, _) = fetch(port, "/spki-assertion")
            assertEquals(204, code)
            assertTrue(body.isEmpty())
        } finally {
            engine.stop(100, 500)
        }
    }
}

package com.openwarden.parent.transport

import com.openwarden.parent.pairing.BouncyCastleEd25519Verifier
import com.openwarden.parent.pairing.SpkiAssertion
import com.openwarden.parent.pairing.SpkiBindingVerifier
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.http.isSuccess
import java.security.SecureRandom
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import javax.net.ssl.SSLContext
import javax.net.ssl.X509TrustManager

/**
 * The parent side of ADR-031 D2 over a **live** TLS handshake (the half deferred at D5/D7, the socket it
 * connects to landed child-side at D8). It closes red-team TR1: connecting to the child's LAN HTTPS
 * server, it proves the presented TLS leaf is the genuine child's — with **no trust-on-first-use** —
 * before any real request is sent, and refuses (fail-closed) otherwise.
 *
 * Flow (ADR-031 D2/D4/D5 carry-forward):
 *  1. handshake with a **capturing** trust manager that records the negotiated leaf (it does not decide
 *     trust — trust is the pin, not the TLS PKI, D1);
 *  2. `GET /spki-assertion` over that channel and parse it (fail-closed on 204 / non-2xx / malformed);
 *  3. [SpkiBindingVerifier.verify] the assertion against the **SPKI of the negotiated leaf** (never a
 *     cert echoed in the body — the D5 carry-forward) and the **pinned child identity key**;
 *  4. on success hand back a client that **pins that exact leaf** so subsequent requests cannot be
 *     silently MITM'd mid-session (a changed leaf fails the handshake). On any failure ⇒ [Outcome.Rejected]
 *     and **no usable client** — the caller MUST NOT fall back to plaintext (D4).
 *
 * TLS is confidentiality-only (D1): this authenticates the *channel's provenance*, never a *message* —
 * every state-changing message is still independently admitted by its parent-signed app-layer check.
 */
class PinnedChildConnector(
    private val verifier: SpkiBindingVerifier = SpkiBindingVerifier(BouncyCastleEd25519Verifier()),
    private val timeoutMs: Long = 5_000,
) {
    sealed interface Outcome {
        /** The channel is proven genuine. [client] pins the verified leaf; the caller owns + closes it. */
        data class Verified(
            val baseUrl: String,
            val client: HttpClient,
        ) : Outcome

        /** Fail-closed: the channel is not trusted. [reason] is for logs/UI; there is NO usable client. */
        data class Rejected(
            val reason: String,
        ) : Outcome
    }

    /**
     * Connect to `https://[host]:[port]`, verify its TLS provenance against [pinnedIdentityPubkey] (the
     * child Ed25519 identity key pinned at pairing), and return a leaf-pinned client on success.
     * Fail-closed on every error path.
     */
    suspend fun connect(
        host: String,
        port: Int,
        pinnedIdentityPubkey: ByteArray?,
    ): Outcome {
        // No pinned identity key ⇒ no trust anchor (pre-pairing). Reject; never TOFU-accept (D4).
        if (pinnedIdentityPubkey == null) return Outcome.Rejected("not paired: no pinned child identity key")

        val baseUrl = "https://$host:$port"
        val capture = CapturingTrustManager()
        val probe = clientTrusting(capture)
        val assertion: SpkiAssertion
        val presentedSpki: ByteArray
        try {
            val resp = probe.get("$baseUrl/spki-assertion")
            if (!resp.status.isSuccess() || resp.status == HttpStatusCode.NoContent) {
                return Outcome.Rejected("child served no SPKI assertion (HTTP ${resp.status.value})")
            }
            assertion =
                SpkiAssertion.parse(resp.bodyAsText())
                    ?: return Outcome.Rejected("unparseable SPKI assertion")
            presentedSpki =
                capture.leafSpkiDer()
                    ?: return Outcome.Rejected("no TLS leaf captured from the handshake")
        } catch (e: Exception) {
            // Handshake / connect / read failure — fail-closed, no fallback.
            return Outcome.Rejected("connect or handshake failed: ${e.message}")
        } finally {
            probe.close()
        }

        // The verifier binds the assertion to the SPKI of the leaf actually negotiated (D2 c3) and to the
        // pinned identity key (D2 c4). A spoofed responder presenting its own cert fails c3; one lacking
        // the child identity key fails c4.
        if (!verifier.verify(assertion, presentedSpki, pinnedIdentityPubkey)) {
            return Outcome.Rejected("SPKI assertion did not verify against the pinned child identity (possible MITM)")
        }

        // Verified. Subsequent traffic goes over a client that accepts ONLY this exact leaf, so a
        // mid-session cert swap fails the handshake (fail-closed) rather than silently re-TOFUing.
        return Outcome.Verified(baseUrl, clientPinning(presentedSpki))
    }

    private fun clientTrusting(tm: X509TrustManager): HttpClient = client(tm)

    private fun clientPinning(spkiDer: ByteArray): HttpClient = client(PinningTrustManager(spkiDer))

    private fun client(tm: X509TrustManager): HttpClient {
        val ssl = SSLContext.getInstance("TLS").apply { init(null, arrayOf(tm), SecureRandom()) }
        return HttpClient(OkHttp) {
            engine {
                config {
                    sslSocketFactory(ssl.socketFactory, tm)
                    // Self-signed, IP-addressed LAN cert: the hostname/SAN is meaningless — trust is the
                    // SPKI pin (above), not the PKI. So accept any hostname; the pin does the gating.
                    hostnameVerifier { _, _ -> true }
                    connectTimeout(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS)
                    readTimeout(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS)
                }
            }
        }
    }
}

/** Trusts the handshake provisionally and records the negotiated leaf so its SPKI can be verified after
 *  (the app-layer decision, D1). It never decides trust — that is the identity-bound assertion's job. */
internal class CapturingTrustManager : X509TrustManager {
    @Volatile
    private var leaf: X509Certificate? = null

    fun leafSpkiDer(): ByteArray? = leaf?.publicKey?.encoded

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

/** After the pin is verified, accept ONLY the exact leaf SPKI that was verified; any other leaf throws,
 *  failing the handshake (fail-closed) so a mid-session MITM swap cannot slip through. */
internal class PinningTrustManager(
    private val expectedSpkiDer: ByteArray,
) : X509TrustManager {
    override fun checkClientTrusted(
        chain: Array<out X509Certificate>?,
        authType: String?,
    ) {}

    override fun checkServerTrusted(
        chain: Array<out X509Certificate>?,
        authType: String?,
    ) {
        val leaf = chain?.firstOrNull() ?: throw CertificateException("no leaf presented")
        if (!leaf.publicKey.encoded.contentEquals(expectedSpkiDer)) {
            throw CertificateException("presented leaf SPKI does not match the verified pin (fail-closed)")
        }
    }

    override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
}

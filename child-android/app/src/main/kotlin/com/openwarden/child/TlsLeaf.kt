package com.openwarden.child

import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import java.math.BigInteger
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.security.spec.ECGenParameterSpec
import java.util.Date

/**
 * An ephemeral, self-signed EC P-256 TLS leaf for the child's LAN HTTPS control channel (ADR-031 D8).
 * Minted **per process** into an in-memory PKCS12 keystore with BouncyCastle — the JVM-portable lib the
 * parent-side D7 verifier already trusts — so the whole socket + handshake is host/Robolectric-testable
 * rather than device-only (an AndroidKeyStore leaf would not be).
 *
 * The leaf key is **software**: TLS here is confidentiality-only (ADR-031 D1) and the channel's trust is
 * the identity-bound [SpkiAssertion] (D2), so the leaf is never an authentication anchor — a leaf-key
 * compromise costs at most LAN-**metadata** confidentiality, never an auth bypass (ATTACKS §1: kid with
 * adb, no JTAG). The pin's anchor is the child identity key, never this leaf, so regenerating the leaf
 * each start is transparent to the parent (which verifies the assertion live on every connect).
 *
 * [spkiDer] is the DER `SubjectPublicKeyInfo` of the leaf — what [SpkiBindingSigner] signs an assertion
 * over and the parent pins (RFC 7469 semantics, [SpkiBinding.spkiSha256]). It is exactly what a parent
 * extracts from the negotiated leaf certificate of a completed TLS handshake (ADR-031 D5 carry-forward).
 */
class TlsLeaf private constructor(
    val keyStore: KeyStore,
    val alias: String,
    val password: CharArray,
    val spkiDer: ByteArray,
) {
    companion object {
        const val ALIAS = "openwarden-tls-leaf"

        /**
         * Mint a fresh self-signed P-256 leaf. Throws on any crypto/keystore failure — the caller
         * ([ApiServer.start]) treats a throw as **fail-closed**: the HTTPS socket does not come up and
         * there is no plaintext fallback (ADR-031 D8 / D4).
         */
        fun generate(): TlsLeaf {
            // A BouncyCastle provider INSTANCE (not globally registered) — avoids the Android system
            // "BC" provider name collision while giving us a portable EC + ECDSA implementation.
            val bc = BouncyCastleProvider()

            val kpg = KeyPairGenerator.getInstance("EC", bc)
            kpg.initialize(ECGenParameterSpec("secp256r1"), SecureRandom())
            val kp = kpg.generateKeyPair()

            val now = System.currentTimeMillis()
            val notBefore = Date(now - 5 * 60_000L) // small backdate for peer clock skew
            val notAfter = Date(now + 3650L * 24 * 60 * 60 * 1000) // ~10y; the leaf is ephemeral anyway
            val dn = X500Name("CN=openwarden-child")
            val cert: X509Certificate =
                JcaX509CertificateConverter().setProvider(bc).getCertificate(
                    JcaX509v3CertificateBuilder(
                        dn,
                        BigInteger.valueOf(now),
                        notBefore,
                        notAfter,
                        dn,
                        kp.public,
                    ).build(JcaContentSignerBuilder("SHA256withECDSA").setProvider(bc).build(kp.private)),
                )

            // In-memory only — never persisted, so a fixed keystore password is adequate protection.
            val pw = "openwarden".toCharArray()
            val ks =
                KeyStore.getInstance("PKCS12").apply {
                    load(null, pw)
                    setKeyEntry(ALIAS, kp.private, pw, arrayOf(cert))
                }
            return TlsLeaf(keyStore = ks, alias = ALIAS, password = pw, spkiDer = cert.publicKey.encoded)
        }
    }
}

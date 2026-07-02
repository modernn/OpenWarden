package com.openwarden.parent.pairing

import org.junit.Test
import java.io.ByteArrayInputStream
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import kotlin.test.assertEquals

/**
 * Golden real-cert SPKI vector (ADR-031 D9, the D8 carry-forward). Unlike [SpkiBindingVerifierTest]'s
 * opaque-byte inputs, this hashes the DER `SubjectPublicKeyInfo` of a REAL EC P-256 X.509 leaf and
 * asserts it equals the fixed golden — catching any regression in the encoding (base64url vs standard,
 * padding) or digest, and proving the parent's `spkiSha256` byte-agrees with the child's over an actual
 * certificate (the value the cert-substitution defense, D2 check 3, binds against).
 *
 * Vector source of truth: docs/test-vectors/spki-real-cert.json (same PEM + golden).
 */
class SpkiRealCertVectorTest {
    private val certPem =
        """
        -----BEGIN CERTIFICATE-----
        MIIBlzCCAT2gAwIBAgIUXsB6TfyK1cCcku0DIWTdqoSpriUwCgYIKoZIzj0EAwIw
        ITEfMB0GA1UEAwwWb3BlbndhcmRlbi1zcGtpLXZlY3RvcjAeFw0yNjA3MDIwNzA0
        MDZaFw0zNjA2MjkwNzA0MDZaMCExHzAdBgNVBAMMFm9wZW53YXJkZW4tc3BraS12
        ZWN0b3IwWTATBgcqhkjOPQIBBggqhkjOPQMBBwNCAARJWFmYHbLT6onP6oAdW1V/
        aK2VMiCW6n8bAa1nvAtg4z1yKsRqPAUMFfDG3TbvWldvaNL8Gc1CvWJ66MSwaJkD
        o1MwUTAdBgNVHQ4EFgQUJAh8tY1wW1jF8Khwv+Kwid9jAEQwHwYDVR0jBBgwFoAU
        JAh8tY1wW1jF8Khwv+Kwid9jAEQwDwYDVR0TAQH/BAUwAwEB/zAKBggqhkjOPQQD
        AgNIADBFAiEA0+lyq2T+AYzwMNAbbOLSOvBdHxPu6PBQeSP5ypYjFlUCIBOr/PkH
        5NcxwKwoYiOc6KdaczLL24WPGM0Cx0nx/+N4
        -----END CERTIFICATE-----
        """.trimIndent()

    private val goldenSpkiSha256 = "XqmLYxj82cRm_KhN4g08ojqhiKbJeuMBM9YH4vI5lDk"

    @Test
    fun `spkiSha256 over a real X509 leaf matches the golden vector`() {
        val cert =
            CertificateFactory
                .getInstance("X.509")
                .generateCertificate(ByteArrayInputStream(certPem.toByteArray())) as X509Certificate
        // cert.publicKey.encoded is the DER SubjectPublicKeyInfo — exactly what the connector captures
        // from the negotiated leaf and feeds to the verifier.
        val spkiDer = cert.publicKey.encoded
        assertEquals(91, spkiDer.size)
        assertEquals(goldenSpkiSha256, SpkiBindingVerifier.spkiSha256(spkiDer))
    }
}

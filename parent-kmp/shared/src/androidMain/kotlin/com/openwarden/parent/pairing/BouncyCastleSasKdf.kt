package com.openwarden.parent.pairing

import org.bouncycastle.crypto.digests.SHA256Digest
import org.bouncycastle.crypto.generators.HKDFBytesGenerator
import org.bouncycastle.crypto.params.HKDFParameters

/**
 * The real §7.4 SAS HKDF-SHA256 (ADR-038 D6), backed by Bouncy Castle — the same pure-JVM primitive
 * that backs the parent root-key derivation (ADR-033), so the whole SAS derivation is host-testable in
 * `androidUnitTest` with no device and no libsodium native lib.
 *
 * RFC 5869: Extract(salt, ikm) then Expand(info) → length. NOTE (as in `RootKeyDerivation`): Bouncy
 * Castle's `HKDFParameters(ikm, salt, info)` takes **IKM first**, not salt first; with a non-null salt
 * `HKDFBytesGenerator` runs Extract-then-Expand in one pass. The RFC 5869 KAT in `androidUnitTest`
 * pins this ordering so a future refactor can't silently swap salt/ikm.
 */
class BouncyCastleSasKdf : SasKdf {
    override fun hkdfSha256(
        salt: ByteArray,
        ikm: ByteArray,
        info: ByteArray,
        length: Int,
    ): ByteArray {
        val generator = HKDFBytesGenerator(SHA256Digest())
        generator.init(HKDFParameters(ikm, salt, info))
        val out = ByteArray(length)
        generator.generateBytes(out, 0, length)
        return out
    }
}

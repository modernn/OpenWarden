package com.openwarden.parent.crypto

import org.bouncycastle.crypto.digests.SHA256Digest

internal actual fun openwardenSha256(data: ByteArray): ByteArray {
    val digest = SHA256Digest()
    digest.update(data, 0, data.size)
    val out = ByteArray(digest.digestSize)
    digest.doFinal(out, 0)
    return out
}

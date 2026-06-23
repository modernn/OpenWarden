package com.openwarden.parent.policy

import com.openwarden.parent.crypto.RootKeyProvider

/** Host-side test doubles for the #27 send path (ADR-034 D7). */

class FakeRootKeyProvider(
    private val provisioned: Boolean,
    private val sigByte: Byte = 0x07,
) : RootKeyProvider {
    override fun rootPublicKey(): ByteArray? = if (provisioned) ByteArray(32) { 1 } else null
    override fun encryptionPublicKey(): ByteArray? = if (provisioned) ByteArray(32) { 2 } else null
    override fun sign(message: ByteArray): ByteArray? = if (provisioned) ByteArray(64) { sigByte } else null
}

class FakePolicySeqStore(start: Long = 0) : PolicySeqStore {
    var reserveCalls = 0
        private set
    private var last = start
    override fun reserveNext(): Long {
        reserveCalls++
        last += 1
        return last
    }
}

class FakePairedChildStore(private val id: String?) : PairedChildStore {
    override fun pairedChildId(): String? = id
}

class FixedNonceGenerator(private val hex: String) : NonceGenerator {
    override fun newNonceHex(): String = hex
}

class FakePolicyTransport(var result: PolicyPostResult) : PolicyTransport {
    var lastJson: String? = null
        private set
    var calls = 0
        private set
    override suspend fun postPolicy(bundleJson: String): PolicyPostResult {
        calls++
        lastJson = bundleJson
        return result
    }
}

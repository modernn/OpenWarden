package com.openwarden.parent.crypto

import com.openwarden.proto.Canonical
import com.openwarden.proto.Policy
import com.openwarden.proto.PolicyBundle
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Pure (no-libsodium) tests of the signing INPUT. Ed25519 sign/verify round-trips
 * run on-device / CI where the libsodium native lib is present.
 */
class PolicySignerTest {
    private fun bundle(seq: Long = 5) = PolicyBundle(
        policySeq = seq,
        childDeviceId = "dev-1",
        notBefore = 1,
        notAfter = 2,
        policy = Policy(allowlist = listOf("b.app", "a.app")),
    )

    @Test
    fun signingBytesExcludesSigAndIsDeterministic() {
        val b = bundle().copy(sig = "SHOULD_BE_IGNORED")
        val s = PolicySigner.signingBytes(b).decodeToString()
        assertFalse(s.contains("\"sig\""), "sig field must not be in the signing input")
        assertEquals(s, PolicySigner.signingBytes(b).decodeToString(), "must be deterministic")
        assertTrue(s.startsWith("{") && s.endsWith("}"))
    }

    @Test
    fun signingBytesAreCanonical() {
        // Object keys sorted; array (allowlist) order preserved as authored.
        val s = PolicySigner.signingBytes(bundle()).decodeToString()
        assertTrue(s.contains("\"allowlist\":[\"b.app\",\"a.app\"]"))
        val childIdx = s.indexOf("childDeviceId")
        val policyIdx = s.indexOf("\"policy\"")
        assertTrue(childIdx in 0 until policyIdx, "keys must be lexicographically ordered")
    }

    @Test
    fun rejectsPolicySeqAboveJcsBound() {
        assertFailsWith<IllegalArgumentException> {
            PolicySigner.signingBytes(bundle(seq = Canonical.MAX_JCS_SAFE_INTEGER + 1))
        }
    }
}

package com.openwarden.parent.policy

import com.openwarden.proto.Policy
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class PolicyBundleBuilderTest {

    @Test
    fun buildsAllFieldsPerProtocol() {
        val policy = Policy(allowlist = listOf("a.app", "b.app"))
        val bundle = PolicyBundleBuilder.build(
            policy = policy,
            childDeviceId = "child-1",
            policySeq = 7,
            nowMs = 1_000L,
            freshnessWindowMs = 5_000L,
            nonceHex = "0".repeat(32),
        )
        assertEquals(1, bundle.v)
        assertEquals(7, bundle.policySeq)
        assertEquals("child-1", bundle.childDeviceId)
        assertEquals(1_000L, bundle.issuedAt)
        assertEquals(1_000L, bundle.notBefore)
        assertEquals(6_000L, bundle.notAfter) // now + window
        assertEquals("0".repeat(32), bundle.nonce)
        assertEquals(policy, bundle.policy)
        assertNull(bundle.sig) // unsigned
    }

    @Test
    fun freshnessWindowIsExactlyConfigured() {
        val window = 24L * 60 * 60 * 1000
        val bundle = PolicyBundleBuilder.build(Policy(), "c", 1, 100L, window, "n")
        assertEquals(window, bundle.notAfter - bundle.notBefore)
    }
}

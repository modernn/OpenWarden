package com.openwarden.parent.policy

import com.openwarden.proto.Policy
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class PolicySenderTest {
    private val window = 24L * 60 * 60 * 1000

    private fun sender(
        provisioned: Boolean = true,
        childId: String? = "child-1",
        seq: FakePolicySeqStore = FakePolicySeqStore(),
        transport: FakePolicyTransport = FakePolicyTransport(PolicyPostResult.Applied(1)),
    ) = PolicySender(
        rootKeyProvider = FakeRootKeyProvider(provisioned),
        seqStore = seq,
        pairedChildStore = FakePairedChildStore(childId),
        transport = transport,
        nonceGenerator = FixedNonceGenerator("0".repeat(32)),
        clockMs = { 1_000L },
        freshnessWindowMs = window,
    )

    @Test
    fun notPairedDoesNotReserveSeq() =
        runTest {
            val seq = FakePolicySeqStore()
            assertEquals(SendResult.NotPaired, sender(childId = null, seq = seq).send(Policy()))
            assertEquals(0, seq.reserveCalls) // fail-closed BEFORE burning a seq
        }

    @Test
    fun notProvisionedDoesNotReserveSeq() =
        runTest {
            val seq = FakePolicySeqStore()
            assertEquals(SendResult.NotProvisioned, sender(provisioned = false, seq = seq).send(Policy()))
            assertEquals(0, seq.reserveCalls)
        }

    @Test
    fun sentOnAppliedCarriesSignedAddressedBundle() =
        runTest {
            val transport = FakePolicyTransport(PolicyPostResult.Applied(5))
            val result = sender(transport = transport).send(Policy(allowlist = listOf("a.app")))
            assertTrue(result is SendResult.Sent)
            result as SendResult.Sent
            // A 64-byte Ed25519 signature → 128 lowercase hex chars.
            val sig = assertNotNull(result.bundle.sig)
            assertEquals(128, sig.length)
            assertTrue(sig.all { it in '0'..'9' || it in 'a'..'f' })
            assertEquals("child-1", result.bundle.childDeviceId)
            assertTrue(transport.lastJson!!.contains("\"policy_seq\""))
            assertTrue(transport.lastJson!!.contains("\"sig\":\""), "signed sig must be on the wire")
        }

    @Test
    fun rejectedPassesChildReason() =
        runTest {
            val result = sender(transport = FakePolicyTransport(PolicyPostResult.Rejected("REGRESSION"))).send(Policy())
            assertTrue(result is SendResult.Rejected && result.reason == "REGRESSION")
        }

    @Test
    fun transportFailureReturnsSignedBundleForRetry() =
        runTest {
            val result = sender(transport = FakePolicyTransport(PolicyPostResult.TransportError("offline"))).send(Policy())
            assertTrue(result is SendResult.TransportFailed)
            assertNotNull((result as SendResult.TransportFailed).bundle.sig)
        }

    @Test
    fun consecutiveSendsUseDistinctStrictlyIncreasingSeqs() =
        runTest {
            val seq = FakePolicySeqStore()
            val s = sender(seq = seq)
            val r1 = s.send(Policy()) as SendResult.Sent
            val r2 = s.send(Policy()) as SendResult.Sent
            assertTrue(r1.bundle.policySeq < r2.bundle.policySeq)
            assertEquals(2, seq.reserveCalls)
        }

    @Test
    fun resendReusesBundleWithoutNewSeq() =
        runTest {
            val seq = FakePolicySeqStore()
            val transport = FakePolicyTransport(PolicyPostResult.Applied(1))
            val s = sender(seq = seq, transport = transport)
            val sent = s.send(Policy()) as SendResult.Sent
            val callsBefore = seq.reserveCalls
            s.resend(sent.bundle)
            assertEquals(callsBefore, seq.reserveCalls) // no new seq reserved on retry
            assertEquals(2, transport.calls)
        }

    @Test
    fun bundleCarriesConfiguredFreshnessWindow() =
        runTest {
            val sent = sender().send(Policy()) as SendResult.Sent
            assertEquals(window, sent.bundle.notAfter - sent.bundle.notBefore)
        }
}

package com.openwarden.parent.pairing

import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The concurrency test ADR-035 (forward hazard #2) required of slice (b): two-or-more concurrent child
 * POSTs must not be handed the same nonce or double-advance the attempt counter. `PairingServer`
 * serializes every `handle()` under one shared monitor (ADR-036 D5); this test reproduces that contract
 * (`synchronized(lock) { ep.handle(...) }`) under heavy multi-threaded load and asserts the outcome is
 * exact: exactly `cap` handoffs, the session burned exactly once, no lost updates. JVM-only (the shared
 * module's `jvmTest` set) because `kotlin.concurrent.thread` / `synchronized` are not in `commonTest`.
 */
class PairingEndpointConcurrencyTest {
    private class LockingSessionAccess(
        @Volatile var session: PairingSession?,
    ) : SessionAccess {
        val cancels = AtomicInteger(0)

        override fun active(): PairingSession? = session

        override fun cancel() {
            cancels.incrementAndGet()
            session = null
        }
    }

    private class CountingVerifier : AttestationVerifier {
        val calls = AtomicInteger(0)

        override fun verify(post: ValidatedPairingPost): AttestationOutcome {
            calls.incrementAndGet()
            return AttestationOutcome.Accepted
        }
    }

    @Test
    fun lockSerializedHandleIsRaceFreeOnAttemptCap() {
        val cap = 5
        val sessions = LockingSessionAccess(PairingSession("{}", ByteArray(32) { 9 }, 0L, 1_000_000L))
        val verifier = CountingVerifier()
        val ed = Base64Url.encode(ByteArray(32) { 3 })
        val x = Base64Url.encode(ByteArray(32) { 4 })
        val body =
            "{\"v\":1,\"child_ed25519_pub\":\"$ed\",\"child_x25519_pub\":\"$x\"," +
                "\"child_attestation_cert_chain\":[\"Y2VydA\"],\"child_binding_sig\":\"${"ab".repeat(16)}\"}"
        val ep =
            PairingEndpoint(
                sessions = sessions,
                verifier = verifier,
                rateLimiter = PairingRateLimiter({ 0L }, maxPerWindow = Int.MAX_VALUE),
                maxAttemptsPerSession = cap,
            )

        val lock = Any()
        val threadCount = 8
        val perThread = 50
        val handoffs = AtomicInteger(0)
        val refusals = AtomicInteger(0)
        val gate = CountDownLatch(1)

        val workers =
            (1..threadCount).map {
                thread {
                    gate.await()
                    repeat(perThread) {
                        // Mirror PairingServer: every handle() runs under the shared session lock.
                        val r = synchronized(lock) { ep.handle(PairingRequest("src", body)) }
                        when (r) {
                            is PairingPostResult.HandedOff -> handoffs.incrementAndGet()
                            is PairingPostResult.Refused -> refusals.incrementAndGet()
                        }
                    }
                }
            }
        gate.countDown()
        workers.forEach { it.join() }

        // Under the shared lock the attempt cap is exact regardless of interleaving:
        assertEquals(cap, handoffs.get(), "exactly cap handoffs")
        assertEquals(cap, verifier.calls.get(), "verifier reached exactly cap times")
        assertEquals(1, sessions.cancels.get(), "session burned exactly once")
        assertEquals(threadCount * perThread - cap, refusals.get(), "every other call refused")
    }
}

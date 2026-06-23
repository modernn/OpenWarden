package com.openwarden.parent.pairing

import com.openwarden.parent.policy.FakeRootKeyProvider
import kotlinx.serialization.decodeFromString
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** A deterministic [PairingNonceSource] yielding 32 copies of a fixed byte. */
private class FixedNonceSource(
    private val byte: Byte,
) : PairingNonceSource {
    override fun freshNonce(): ByteArray = ByteArray(32) { byte }
}

/** Yields a distinct 32-byte nonce per call (byte = call count), to prove freshness. */
private class CountingNonceSource : PairingNonceSource {
    var calls = 0
        private set

    override fun freshNonce(): ByteArray {
        calls += 1
        return ByteArray(32) { calls.toByte() }
    }
}

class PairingSessionManagerTest {
    // FakeRootKeyProvider(provisioned=true) returns ed=0x01*32, x=0x02*32.
    private val edPub = ByteArray(32) { 1 }
    private val xPub = ByteArray(32) { 2 }

    private fun manager(
        provisioned: Boolean = true,
        nonce: PairingNonceSource = FixedNonceSource(9),
        clock: () -> Long = { 0L },
        ttl: Long = 1_000L,
    ) = PairingSessionManager(
        rootKeys = FakeRootKeyProvider(provisioned),
        nonceSource = nonce,
        nowMs = clock,
        ttlMs = ttl,
    )

    @Test
    fun startProducesSpec71Payload() {
        val result = manager().start()
        assertTrue(result is PairingStartResult.Started)
        val payload = pairingJson.decodeFromString<PairingQrPayload>(result.session.payloadJson)

        assertEquals(1, payload.v)
        assertEquals(Base64Url.encode(edPub), payload.parentEd25519Pub)
        assertEquals(Base64Url.encode(xPub), payload.parentX25519Pub)
        assertEquals(Base64Url.encode(ByteArray(32) { 9 }), payload.provisioningNonce)
        assertEquals(43, payload.parentEd25519Pub.length)
        assertEquals(43, payload.parentX25519Pub.length)
        assertEquals(43, payload.provisioningNonce.length)
        assertEquals("_openwarden._tcp.local", payload.transportHints.mdns)
        assertNull(payload.transportHints.irohTicket)
    }

    /** ADR-035 D8 / ADR-025 D3: the invented `tls_spki` field never appears. */
    @Test
    fun payloadHasNoTlsSpkiField() {
        val result = manager().start() as PairingStartResult.Started
        assertFalse(result.session.payloadJson.contains("tls_spki"))
        assertFalse(result.session.payloadJson.contains("iroh_ticket"))
    }

    /** D7 fail-closed: no parent key ⇒ no session, no QR. */
    @Test
    fun notProvisionedYieldsNoSession() {
        val m = manager(provisioned = false)
        assertEquals(PairingStartResult.NotProvisioned, m.start())
        assertNull(m.active())
    }

    /** D2/D5: each start mints a fresh nonce, and only the newest session is live. */
    @Test
    fun newStartMintsFreshNonceAndBurnsPrior() {
        val m = manager(nonce = CountingNonceSource())
        val first = (m.start() as PairingStartResult.Started).session
        val second = (m.start() as PairingStartResult.Started).session
        assertFalse(first.nonce().contentEquals(second.nonce()))
        assertTrue(m.active()!!.nonce().contentEquals(second.nonce()))
    }

    /** D6: consume hands the session over once, then nothing is live. */
    @Test
    fun consumeIsSingleUse() {
        val m = manager()
        m.start()
        assertNotNull(m.consume())
        assertNull(m.active())
        assertNull(m.consume())
    }

    /** D3/D6: a session at/after its TTL is expired and burned. */
    @Test
    fun expiredSessionBurnsAndYieldsNull() {
        var now = 0L
        val m = manager(clock = { now }, ttl = 100L)
        m.start()
        now = 100L // == createdAt + ttl ⇒ expired
        assertNull(m.active())
        assertNull(m.consume())
    }

    /** Live right up to (but not at) the TTL boundary. */
    @Test
    fun liveJustBeforeTtl() {
        var now = 0L
        val m = manager(clock = { now }, ttl = 100L)
        m.start()
        now = 99L
        assertNotNull(m.active())
    }

    /** D6: explicit cancel burns the pending session. */
    @Test
    fun cancelBurnsPendingSession() {
        val m = manager()
        m.start()
        m.cancel()
        assertNull(m.active())
    }

    /** The handed-out nonce is a defensive copy — mutating it does not corrupt the session. */
    @Test
    fun nonceAccessorReturnsDefensiveCopy() {
        val session = (manager().start() as PairingStartResult.Started).session
        val first = session.nonce()
        first.fill(0)
        assertFalse(first.contentEquals(session.nonce()))
    }
}

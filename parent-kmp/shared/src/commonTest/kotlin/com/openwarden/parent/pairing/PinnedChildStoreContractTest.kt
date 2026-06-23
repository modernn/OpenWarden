package com.openwarden.parent.pairing

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Host model of the [PinnedChildStore] contract (ADR-039 D2/D3/D6) — the store-level invariants that
 * the coordinator's soft check does NOT exercise (it short-circuits before `pin()` on an already-pinned
 * store) and that the real `AndroidPairedChildStore` cannot prove host-side (no Robolectric;
 * `EncryptedSharedPreferences` needs the Android KeyStore — that round-trip is the ADR-039 instrumented
 * HARD gate). [InMemoryPinnedChildStore] mirrors the adapter faithfully: it stores the **base64url
 * strings** and **decodes on read** exactly like `AndroidPairedChildStore`, so the byte-validation read
 * path (D6) and the write-once hard-floor throw (D3, the backstop independent of the coordinator) are
 * proven deterministically here, not merely asserted by fiat.
 */
class PinnedChildStoreContractTest {
    private val childEd = ByteArray(32) { 3 }
    private val childX = ByteArray(32) { 4 }

    /** Faithful host twin of AndroidPairedChildStore: base64url-string storage + decode-on-read + write-once. */
    private class InMemoryPinnedChildStore : PinnedChildStore {
        private var edB64: String? = null
        private var xB64: String? = null

        override fun pinnedChild(): PinnedChild? {
            val ed = Base64Url.decode32(edB64 ?: return null) ?: return null
            val x = Base64Url.decode32(xB64 ?: return null) ?: return null
            return PinnedChild(ed, x)
        }

        override fun pin(child: PinnedChild) {
            check(edB64 == null && xB64 == null) { "child already pinned (write-once)" }
            // Atomic two-key set (the real adapter uses one EncryptedSharedPreferences commit()).
            edB64 = Base64Url.encode(child.ed25519Pub)
            xB64 = Base64Url.encode(child.x25519Pub)
        }

        /** Test-only: inject raw stored values to exercise the lone-key / malformed-key read paths (D6). */
        fun forceRaw(
            ed: String?,
            x: String?,
        ) {
            edB64 = ed
            xB64 = x
        }
    }

    @Test
    fun pinThenReadsBackBothKeys() {
        val store = InMemoryPinnedChildStore()
        store.pin(PinnedChild(childEd, childX))

        val pinned = store.pinnedChild()!!
        assertContentEquals(childEd, pinned.ed25519Pub)
        assertContentEquals(childX, pinned.x25519Pub)
    }

    @Test
    fun secondPinThrowsAndFirstPinStands() {
        val store = InMemoryPinnedChildStore()
        store.pin(PinnedChild(childEd, childX))

        // The store-level hard floor (ADR-039 D3) — independent of the coordinator's soft check.
        assertFailsWith<IllegalStateException>("a second pin is refused at the store") {
            store.pin(PinnedChild(ByteArray(32) { 7 }, ByteArray(32) { 8 }))
        }
        val pinned = store.pinnedChild()!!
        assertContentEquals(childEd, pinned.ed25519Pub, "the first pin is unchanged after a refused second pin")
        assertContentEquals(childX, pinned.x25519Pub)
    }

    @Test
    fun loneKeyReadsAsUnpaired() {
        val store = InMemoryPinnedChildStore()
        store.forceRaw(ed = Base64Url.encode(childEd), x = null) // only the Ed key present — a half-write

        assertNull(store.pinnedChild(), "a lone key reads as unpaired (no half-pin, ADR-039 D2)")
    }

    @Test
    fun malformedStoredKeyReadsAsUnpaired() {
        val store = InMemoryPinnedChildStore()
        // Non-base64url / wrong-length stored values must fail the D6 decode → unpaired, not a bad array.
        store.forceRaw(ed = "!!!not-base64url!!!", x = Base64Url.encode(childX))
        assertNull(store.pinnedChild(), "a malformed stored key reads as unpaired (ADR-039 D6)")

        store.forceRaw(ed = Base64Url.encode(childEd), x = Base64Url.encode(ByteArray(16))) // 16 bytes, not 32
        assertNull(store.pinnedChild(), "a wrong-length stored key reads as unpaired (ADR-039 D6)")
    }

    @Test
    fun freshStoreIsUnpaired() {
        assertTrue(InMemoryPinnedChildStore().pinnedChild() == null, "a never-pinned store is unpaired")
    }
}

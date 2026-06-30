package com.openwarden.child

import android.content.Context
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Tests for [ReplayFloorStore.seedGenesisProvisioning] — the storage method that closes the demo-pair
 * genesis-coupling gap (ADR-046 D1, #150 crypto review Finding 1). It must seed the at-rest floor to
 * [ReplayFloor.GENESIS_FLOOR] AND write the provisioning marker, never lower an established floor, and
 * be idempotent — so the first signed bundle (`policy_seq >= 1`) admits via the normal path.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ReplayFloorStoreGenesisTest {
    // Inject a plain (Robolectric in-memory) SharedPreferences via the internal test seam — the real
    // EncryptedSharedPreferences can't init under Robolectric (no AndroidKeyStore provider). A fresh
    // app per test isolates the named prefs file.
    private fun store(): ReplayFloorStore {
        val prefs = RuntimeEnvironment.getApplication().getSharedPreferences("rfs_genesis_test", Context.MODE_PRIVATE)
        return ReplayFloorStore { prefs }
    }

    @Test
    fun seedsFloorToGenesisAndMarksProvisioned() {
        val s = store()
        assertFalse(s.isProvisioned(), "precondition: not provisioned")
        assertNull(s.atRestFloor(), "precondition: no floor yet")

        s.seedGenesisProvisioning()

        assertTrue(s.isProvisioned(), "provisioning marker must be written")
        assertEquals(ReplayFloor.GENESIS_FLOOR, s.atRestFloor(), "floor must be seeded to GENESIS_FLOOR")
    }

    @Test
    fun isIdempotent() {
        val s = store()
        s.seedGenesisProvisioning()
        s.seedGenesisProvisioning() // no-op once provisioned

        assertTrue(s.isProvisioned())
        assertEquals(ReplayFloor.GENESIS_FLOOR, s.atRestFloor())
    }

    @Test
    fun doesNotLowerAnEstablishedFloor() {
        val s = store()
        s.advanceFloor(5L) // a real floor already exists (e.g. some prior admit)

        s.seedGenesisProvisioning()

        assertEquals(5L, s.atRestFloor(), "seed must NEVER lower an established floor to 0")
        assertTrue(s.isProvisioned(), "marker is still written")
    }
}

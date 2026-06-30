package com.openwarden.parent.crypto

import android.content.SharedPreferences
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

/**
 * Host tests for the fail-closed open/read contract of [AndroidSecureKeyStorage] (ADR-033 amendment,
 * #144). The store crashed the parent app on a device with no secure lock screen because the
 * `setUserAuthenticationRequired(true)` MasterKey build throws there. These drive that failure
 * deterministically by injecting a `prefsFactory` that throws the exact keystore exception — no real
 * keystore / Robolectric needed (the factory never returns a [SharedPreferences]). The success
 * round-trip is exercised on-device (androidInstrumentedTest).
 */
class AndroidSecureKeyStorageTest {
    /** Mimics the real no-secure-lock failure: building the user-auth MasterKey throws. */
    private val throwingFactory: () -> SharedPreferences = {
        throw IllegalStateException("Secure lock screen must be enabled to create keys requiring user authentication")
    }

    @Test
    fun `read fails closed to null when the secure store cannot be opened`() {
        // #144: read() MUST NOT throw — the pairing flow relies on null => NotProvisioned, not a crash.
        assertNull(
            AndroidSecureKeyStorage(throwingFactory).read(),
            "read() must return null (fail-closed) when the secure store can't be opened",
        )
    }

    @Test
    fun `clear does not throw when the secure store cannot be opened`() {
        // A store that can't be opened has nothing to clear — best-effort, never a crash.
        AndroidSecureKeyStorage(throwingFactory).clear()
    }

    @Test
    fun `write refuses loudly with a typed exception when the secure store cannot be opened`() {
        // Opposite direction from read: provisioning must NOT believe a key was stored when it wasn't.
        assertFailsWith<SecureStorageUnavailableException>("write must refuse loudly, not silently no-op") {
            AndroidSecureKeyStorage(throwingFactory).write(ByteArray(128) { 1 })
        }
    }

    @Test
    fun `a failed open is re-attempted, never cached, so the store recovers once available`() {
        var attempts = 0
        val countingFactory: () -> SharedPreferences = {
            attempts++
            throw IllegalStateException("no secure lock yet")
        }
        val storage = AndroidSecureKeyStorage(countingFactory)
        storage.read()
        storage.read()
        // Two reads ⇒ two open attempts: a failed open is never cached, so the store recovers once
        // the user sets a screen lock — without an app restart.
        assertEquals(2, attempts, "a failed open must be re-attempted on the next call, not cached")
    }
}

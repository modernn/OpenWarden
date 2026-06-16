package com.openwarden.proto

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class CanonicalTest {
    @Test
    fun rejectsIntegerAboveJcsBound() {
        assertFailsWith<IllegalArgumentException> {
            Canonical.requireJcsSafe(Canonical.MAX_JCS_SAFE_INTEGER + 1)
        }
    }

    @Test
    fun acceptsZeroAndBoundary() {
        Canonical.requireJcsSafe(0)
        Canonical.requireJcsSafe(Canonical.MAX_JCS_SAFE_INTEGER)
    }

    @Test
    fun rejectsNegative() {
        assertFailsWith<IllegalArgumentException> { Canonical.requireJcsSafe(-1) }
    }

    @Test
    fun versionIsOneAndSelfCompatible() {
        assertEquals(1, POLICY_BUNDLE_FORMAT_VERSION)
        assertTrue(Versioning.isCompatible(POLICY_BUNDLE_FORMAT_VERSION))
    }
}

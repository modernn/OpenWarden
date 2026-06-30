package com.openwarden.parent.crypto

import com.openwarden.proto.SignedCommand
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

/**
 * Byte-agreement contract tests for [CommandSigner] (ADR-046 D3).
 *
 * The parent and child are separate gradle modules, so the wire shape is mirrored, not shared. These
 * golden vectors pin the EXACT canonical bytes the child's `CommandVerifier.canonicalBody` produces —
 * if the parent ever drifts (key order, whitespace, `sig` inclusion, number formatting), the child
 * rejects every command with SIG_FAIL. The golden strings were derived from the documented child
 * contract: JCS-sorted keys, `sig` excluded, no whitespace, integer `issued_at`/`v`.
 */
class CommandSignerTest {
    @Test
    fun signingBytes_matchesChildCanonicalContract_lock() {
        val cmd =
            SignedCommand(type = SignedCommand.TYPE_LOCK, childDeviceId = "child-abcd", issuedAt = 10_000_000L)
        assertEquals(
            """{"child_device_id":"child-abcd","issued_at":10000000,"type":"lock","v":1}""",
            CommandSigner.signingBytes(cmd).decodeToString(),
        )
    }

    @Test
    fun signingBytes_matchesChildCanonicalContract_unlock() {
        val cmd = SignedCommand(type = SignedCommand.TYPE_UNLOCK, childDeviceId = "child-9", issuedAt = 1L)
        assertEquals(
            """{"child_device_id":"child-9","issued_at":1,"type":"unlock","v":1}""",
            CommandSigner.signingBytes(cmd).decodeToString(),
        )
    }

    @Test
    fun signingBytes_excludesSig_soSigningIsNotSelfReferential() {
        val signed = SignedCommand(type = "lock", childDeviceId = "c", issuedAt = 5L, sig = "deadbeef")
        val unsigned = signed.copy(sig = "")
        // The `sig` field must not change the signing input — the signature covers everything BUT sig.
        assertEquals(
            CommandSigner.signingBytes(unsigned).decodeToString(),
            CommandSigner.signingBytes(signed).decodeToString(),
        )
        assertFalse(
            CommandSigner.signingBytes(signed).decodeToString().contains("deadbeef"),
            "sig must be excluded from the signing input",
        )
    }
}

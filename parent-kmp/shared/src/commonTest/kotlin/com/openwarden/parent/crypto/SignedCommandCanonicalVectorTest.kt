package com.openwarden.parent.crypto

import com.openwarden.proto.SignedCommand
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Parent half of the shared canonical `SignedCommand` known-answer test (#154, ADR-047 D4).
 *
 * Source of truth: `docs/test-vectors/command/cmd-canonical-kat.json`. The child half
 * (`child-android` `CommandCanonicalVectorTest`) asserts the *same* golden bytes over the child's
 * independent `CommandVerifier.canonicalBody` / `Canonical` port. Both MUST match — a shared vector is
 * the standing merge gate that keeps the two RFC 8785 (JCS) implementations byte-identical for commands
 * (the pairing + bundle vectors already do their shapes).
 *
 * Asserts both the UTF-8 canonical string AND its byte-level hex, so a change in encoding (not just key
 * order) is caught. `v:1` is a defaulted field but MUST appear in the signed bytes (ADR-047 D1,
 * encodeDefaults=true); `sig` MUST be excluded.
 */
class SignedCommandCanonicalVectorTest {
    // --- docs/test-vectors/command/cmd-canonical-kat.json (verbatim) ---
    private val lockCanonical = """{"child_device_id":"child-abcd","issued_at":10000000,"type":"lock","v":1}"""
    private val lockHex =
        "7b226368696c645f6465766963655f6964223a226368696c642d61626364222c226973737565645f6174" +
            "223a31303030303030302c2274797065223a226c6f636b222c2276223a317d"
    private val unlockCanonical = """{"child_device_id":"child-9","issued_at":1,"type":"unlock","v":1}"""
    private val unlockHex =
        "7b226368696c645f6465766963655f6964223a226368696c642d39222c226973737565645f6174223a31" +
            "2c2274797065223a22756e6c6f636b222c2276223a317d"

    @Test
    fun lockCommand_matchesSharedVector_utf8AndHex() {
        val cmd = SignedCommand(type = SignedCommand.TYPE_LOCK, childDeviceId = "child-abcd", issuedAt = 10_000_000L)
        val bytes = CommandSigner.signingBytes(cmd)
        assertEquals(lockCanonical, bytes.decodeToString())
        assertEquals(lockHex, bytes.toHexLower())
    }

    @Test
    fun unlockCommand_matchesSharedVector_utf8AndHex() {
        val cmd = SignedCommand(type = SignedCommand.TYPE_UNLOCK, childDeviceId = "child-9", issuedAt = 1L)
        val bytes = CommandSigner.signingBytes(cmd)
        assertEquals(unlockCanonical, bytes.decodeToString())
        assertEquals(unlockHex, bytes.toHexLower())
    }

    private fun ByteArray.toHexLower(): String = joinToString("") { (it.toInt() and 0xFF).toString(16).padStart(2, '0') }
}

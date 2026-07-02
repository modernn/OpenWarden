package com.openwarden.child

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Child half of the shared canonical `SignedCommand` known-answer test (#154, ADR-047 D4).
 *
 * Source of truth: `docs/test-vectors/command/cmd-canonical-kat.json`. The parent half
 * (`parent-kmp` `SignedCommandCanonicalVectorTest`) asserts the *same* golden bytes over the parent's
 * independent `CommandSigner` / `Canonical` port. Both MUST match — this is the standing merge gate that
 * keeps the two RFC 8785 (JCS) implementations byte-identical for commands, so a parent-signed command
 * verifies under the child's libsodium/i2p verifier.
 *
 * Asserts the UTF-8 canonical string AND its byte-level hex. `v:1` MUST appear in the signed bytes
 * (ADR-047 D1); `sig` MUST be excluded (`canonicalBody` copies with `sig = ""` then omits it).
 */
class CommandCanonicalVectorTest {
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
        val cmd = SignedCommand(v = 1, type = SignedCommand.TYPE_LOCK, child_device_id = "child-abcd", issued_at = 10_000_000L)
        val bytes = CommandVerifier.canonicalBody(cmd)
        assertEquals(lockCanonical, bytes.decodeToString())
        assertEquals(lockHex, bytes.toHexLower())
    }

    @Test
    fun unlockCommand_matchesSharedVector_utf8AndHex() {
        val cmd = SignedCommand(v = 1, type = SignedCommand.TYPE_UNLOCK, child_device_id = "child-9", issued_at = 1L)
        val bytes = CommandVerifier.canonicalBody(cmd)
        assertEquals(unlockCanonical, bytes.decodeToString())
        assertEquals(unlockHex, bytes.toHexLower())
    }

    private fun ByteArray.toHexLower(): String = joinToString("") { (it.toInt() and 0xFF).toString(16).padStart(2, '0') }
}

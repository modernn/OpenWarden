package com.openwarden.parent.crypto

import com.openwarden.parent.policy.PolicyBundleBuilder
import com.openwarden.proto.Canonical
import com.openwarden.proto.Policy
import com.openwarden.proto.PolicyBundle
import com.openwarden.proto.SignedCommand
import com.openwarden.proto.SigningInput
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * #160 / ADR-047 D4 — pins the signed-wire `encodeDefaults` invariant at the BYTE level for all three
 * signed parent→child objects (policy bundle, lock, unlock).
 *
 * The property proven for each: the bytes the parent transmits, once parsed and canonicalized the way
 * the child does (`SigningInput.forDocument` for the bundle — ADR-040 verify-over-received-bytes;
 * `Canonical.canonicalizeWithout` for a command — ADR-030), equal the signer's `signingBytes` EXACTLY.
 * Because the Ed25519 signature is defined over `signingBytes`, byte-equality ⇒ the transmitted wire
 * verifies; a drop of any defaulted field the signer covered (`v`, empty policy lists) breaks it.
 *
 * Each case ships its NEGATIVE twin: re-serializing with a `Json` that omits `encodeDefaults` drops the
 * defaulted fields, so the wire no longer contains `"v"` and no longer canonicalizes to the signed
 * bytes — the exact #157 failure, reproduced deterministically. `explicitNulls=false` is held constant
 * across positive and negative so `encodeDefaults` is the ONLY variable (the real #157 root cause).
 */
class SignedWireByteEqualityTest {
    // Production-shaped wire configs (must match PolicySender.json / RealLockCommandSender's HttpClient).
    private val bundleWire =
        Json {
            encodeDefaults = true
            explicitNulls = false
        }
    private val commandWire =
        Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
        }

    // Negative: encodeDefaults DROPPED (the #157 defect); explicitNulls held false to isolate the variable.
    private val bundleNoDefaults = Json { explicitNulls = false }
    private val commandNoDefaults = Json { explicitNulls = false }

    private fun signedBundle(): PolicyBundle =
        PolicyBundleBuilder
            .build(
                policy = Policy(),
                childDeviceId = "child-1",
                policySeq = 1,
                nowMs = 1_000L,
                freshnessWindowMs = 86_400_000L,
                nonceHex = "0".repeat(32),
            ).copy(sig = "ab".repeat(64)) // byte-equality of the SIGNING INPUT — no real signature needed

    @Test
    fun bundleWire_canonicalizesToExactlyTheSignedBytes() {
        val signed = signedBundle()
        val wire = bundleWire.encodeToString(PolicyBundle.serializer(), signed)

        // Raw wire carries the defaulted fields the signature covers (decode would re-default and hide it).
        assertTrue(wire.contains("\"v\":1"), "wire must carry v:1: $wire")
        assertTrue(wire.contains("\"blocklist\":[]"), "wire must carry empty blocklist: $wire")
        assertTrue(wire.contains("\"windows\":[]"), "wire must carry empty windows: $wire")
        assertTrue(wire.contains("\"restrictions\":[]"), "wire must carry empty restrictions: $wire")

        // The transmitted wire, canonicalized as the child does (ADR-040), == the signed bytes.
        val wireCanonical = SigningInput.forDocument(Json.parseToJsonElement(wire).jsonObject)
        assertContentEquals(PolicySigner.signingBytes(signed), wireCanonical)
    }

    @Test
    fun bundleWire_withoutEncodeDefaults_dropsFieldsAndBreaksSignature() {
        val signed = signedBundle()
        val bad = bundleNoDefaults.encodeToString(PolicyBundle.serializer(), signed)

        assertFalse(bad.contains("\"v\":"), "no-encodeDefaults wire must DROP v (the #157 defect): $bad")
        val badCanonical = SigningInput.forDocument(Json.parseToJsonElement(bad).jsonObject)
        assertFalse(
            PolicySigner.signingBytes(signed).contentEquals(badCanonical),
            "dropping defaults must NOT canonicalize to the signed bytes (else the bug would be invisible)",
        )
    }

    @Test
    fun lockCommandWire_canonicalizesToExactlyTheSignedBytes() = assertCommandWireMatches(SignedCommand.TYPE_LOCK)

    @Test
    fun unlockCommandWire_canonicalizesToExactlyTheSignedBytes() = assertCommandWireMatches(SignedCommand.TYPE_UNLOCK)

    private fun assertCommandWireMatches(type: String) {
        val cmd = SignedCommand(type = type, childDeviceId = "child-abcd", issuedAt = 10_000_000L, sig = "ab".repeat(64))
        val wire = commandWire.encodeToString(SignedCommand.serializer(), cmd)

        assertTrue(wire.contains("\"v\":1"), "$type wire must carry v:1: $wire")
        val wireCanonical = Canonical.canonicalizeWithout(Json.parseToJsonElement(wire).jsonObject, "sig").encodeToByteArray()
        assertContentEquals(CommandSigner.signingBytes(cmd), wireCanonical)

        // Negative: encodeDefaults dropped → v (a default) vanishes → different bytes.
        val bad = commandNoDefaults.encodeToString(SignedCommand.serializer(), cmd)
        assertFalse(bad.contains("\"v\":"), "$type no-encodeDefaults wire must DROP v: $bad")
        val badCanonical = Canonical.canonicalizeWithout(Json.parseToJsonElement(bad).jsonObject, "sig").encodeToByteArray()
        assertFalse(
            CommandSigner.signingBytes(cmd).contentEquals(badCanonical),
            "$type: dropping v must NOT canonicalize to the signed bytes",
        )
    }
}

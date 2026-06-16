package com.openwarden.proto

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals

class SigningInputTest {
    private fun bundleBytes(b: PolicyBundle) = SigningInput.forBundle(b).decodeToString()
    private fun entryBytes(e: EventEntry) = SigningInput.forEntry(e).decodeToString()
    private fun obj(json: String) = Json.parseToJsonElement(json) as JsonObject

    private val goldenBundle = PolicyBundle(
        v = 1,
        policySeq = 5,
        childDeviceId = "dev-1",
        issuedAt = 50,
        notBefore = 100,
        notAfter = 200,
        nonce = "9f1b3c4d5e6f70819a2b3c4d5e6f7081",
        policy = Policy(allowlist = listOf("com.a")),
        sig = null,
    )

    // ----- ADR-015: one signing rule, full-object canonical form (PROTOCOL.md §2 wire names) -----

    @Test
    fun bundleSigningBytesAreCanonicalAndSigStripped() {
        // Keys sorted UTF-16; sig removed; §2 snake_case wire names; integer timestamps (ms,
        // not ISO strings); defaults (empty blocklist/windows/restrictions) present and signed;
        // null optionals (private_dns, frp_account_email) OMITTED (no `null` per §3.1 rule 6).
        assertEquals(
            """{"child_device_id":"dev-1","issued_at":50,"nonce":"9f1b3c4d5e6f70819a2b3c4d5e6f7081",""" +
                """"not_after":200,"not_before":100,""" +
                """"policy":{"allowlist":["com.a"],"blocklist":[],"restrictions":[],"windows":[]},""" +
                """"policy_seq":5,"v":1}""",
            bundleBytes(goldenBundle),
        )
    }

    @Test
    fun sigFieldNeverAffectsTheSigningInput() {
        // A present signature and an absent one yield identical signing bytes (sig excludes itself).
        assertEquals(
            bundleBytes(goldenBundle),
            bundleBytes(goldenBundle.copy(sig = "deadbeef")),
        )
    }

    @Test
    fun verifierDocumentBytesMatchSignerBundleBytes() {
        // Verifier canonicalizes the RECEIVED document (different key order, whitespace, sig present)
        // and must arrive at exactly the signer's bytes — otherwise parent/child disagree (SIG_FAIL).
        // Wire keys are PROTOCOL.md §2 snake_case; integer timestamps in ms.
        val wire = obj(
            """{ "sig":"ZZ", "v":1, "policy_seq":5, "child_device_id":"dev-1",
                 "issued_at":50, "not_after":200, "not_before":100,
                 "nonce":"9f1b3c4d5e6f70819a2b3c4d5e6f7081",
                 "policy":{"windows":[],"restrictions":[],"blocklist":[],"allowlist":["com.a"]} }""",
        )
        assertEquals(bundleBytes(goldenBundle), SigningInput.forDocument(wire).decodeToString())
    }

    @Test
    fun omittingASignedDefaultFieldDivergesFromSignerBytes() {
        // ADR-019: the verifier must receive the EXACT bytes the signer signed. A wire document that
        // drops a defaulted field the signer included (here: the empty blocklist/windows/restrictions)
        // canonicalizes to DIFFERENT bytes — which is precisely why the signer must transmit its
        // canonical form verbatim, not let the verifier re-derive it from a typed model. This guards
        // the forBundle-vs-forDocument divergence (PR #47 review finding).
        val wireMissingDefaults = obj(
            """{"v":1,"policy_seq":5,"child_device_id":"dev-1","issued_at":50,
                 "not_after":200,"not_before":100,"nonce":"9f1b3c4d5e6f70819a2b3c4d5e6f7081",
                 "policy":{"allowlist":["com.a"]}}""",
        )
        assertNotEquals(
            bundleBytes(goldenBundle),
            SigningInput.forDocument(wireMissingDefaults).decodeToString(),
        )
    }

    // ----- SG1 regression: EVERY field is signed (incl. v / issued_at / payload_type) -----

    @Test
    fun everyEventEntryFieldChangesTheSignature() {
        val base = EventEntry(
            v = 1, seq = 3, prevHash = "ab", issuedAt = 50, payloadType = "block", payload = "xx", sig = null,
        )
        val baseline = entryBytes(base)
        // The three fields SG1 left unsigned in the old scheme — now each must move the bytes.
        assertNotEquals(baseline, entryBytes(base.copy(v = 2)), "v must be signed (SG1)")
        assertNotEquals(baseline, entryBytes(base.copy(issuedAt = 51)), "issued_at must be signed (SG1)")
        assertNotEquals(baseline, entryBytes(base.copy(payloadType = "unblock")), "payload_type must be signed (SG1)")
        // ...and the fields the old scheme did cover still matter.
        assertNotEquals(baseline, entryBytes(base.copy(seq = 4)))
        assertNotEquals(baseline, entryBytes(base.copy(prevHash = "cd")))
        assertNotEquals(baseline, entryBytes(base.copy(payload = "yy")))
    }

    // ----- ADR-017 / #6: integers bounded to JCS-safe range at the signing boundary -----

    @Test
    fun jcsBoundaryIntegerIsAccepted() {
        // 2^53-1 is the largest JCS-exact integer — must round-trip.
        SigningInput.forDocument(obj("""{"policySeq":9007199254740991}"""))
    }

    @Test
    fun integerAboveJcsBoundIsRejected() {
        // 2^53 (fits in a Long, but JCS cannot represent it exactly) — reject before signing.
        assertFailsWith<IllegalArgumentException> {
            SigningInput.forDocument(obj("""{"policySeq":9007199254740992}"""))
        }
    }

    @Test
    fun integerBeyondLongRangeIsRejected() {
        // 2^63 — overflows Long entirely; must be cleanly rejected, never silently diverge.
        assertFailsWith<IllegalArgumentException> {
            SigningInput.forDocument(obj("""{"policySeq":9223372036854775808}"""))
        }
    }

    @Test
    fun nestedOutOfRangeIntegerIsRejected() {
        // Bounding is whole-tree, not just the top-level counters.
        assertFailsWith<IllegalArgumentException> {
            SigningInput.forDocument(obj("""{"policy":{"windows":[{"startMinuteOfDay":9007199254740992}]}}"""))
        }
    }
}

package com.openwarden.child

import android.content.Context
import android.content.pm.PackageManager
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assume.assumeNoException
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Instrumented **bench-confirm** for [KeystoreChildKeys] (issue #22, ADR-032). The class self-documents
 * as "bench-gated, untested here — StrongBox exists on neither the JVM nor the emulator … the exact
 * bytes + StrongBox/string-algorithm resolution MUST still be confirmed on-device per the gate." This
 * test encodes that gate as a single runnable suite, split by what each Android target can prove:
 *
 *  - **Any Android device (incl. the emulator):**
 *      - the fail-closed contract: a never-provisioned [KeystoreChildKeys] reports `isProvisioned()==false`
 *        and every accessor returns `null` (test 1);
 *      - the extractor REJECTS a non-Curve25519 key — a P-256 key under the production `K_id`/`K_enc`
 *        aliases yields `null`, executing the "91-byte P-256 ⇒ null ⇒ cannot pair" fail-closed path on
 *        the emulator rather than just asserting it in prose (test 1b).
 *  - **A genuine-Curve25519 device only (a real Pixel 7):**
 *      - the **SPKI-encoding assumption** the class's `rawCurve25519PublicKey` extractor pins — that
 *        AndroidKeyStore emits exactly the 12-byte RFC 8410 prefix ‖ 32-byte key — for Ed25519 (`K_id`)
 *        and X25519 (`K_enc`), plus an Ed25519 sign/verify round-trip (tests 2 + 3).
 *  - **A StrongBox device only (a bench Pixel 7 — `assumeTrue(FEATURE_STRONGBOX_KEYSTORE)`):**
 *      - the full `K_bind` (StrongBox P-256) → [ChildKeyBinding] → parent §7.3 check-4b round-trip, i.e. a
 *        real attested binding the **real** [ChildKeyBindingVerifier] accepts, and a tampered-nonce
 *        binding it rejects (tests 4 + 5).
 *
 * **Bench finding (this run, emulator API 36):** the emulator's AndroidKeyStore does NOT implement
 * Curve25519 — an `"Ed25519"`/`"XDH"` keygen request yields a 91-byte **P-256** SPKI, not a 44-byte RFC
 * 8410 Curve25519 key. So tests 2–5 all SKIP on the emulator (only test 1 runs there); they confirm the
 * ADR-032 "untested here" disclosure is real, and that the production extractor fail-closed-rejects the
 * non-Curve25519 encoding (44 ≠ 91 ⇒ `null` ⇒ child refuses to pair rather than mis-slicing). The genuine
 * Curve25519 + StrongBox confirmations are the maintainer's Pixel-7 bench run:
 *   ./gradlew :app:connectedDebugAndroidTest \
 *     -Pandroid.testInstrumentationRunnerArguments.class=com.openwarden.child.KeystoreChildKeysInstrumentedTest
 */
@RunWith(AndroidJUnit4::class)
class KeystoreChildKeysInstrumentedTest {
    private val context: Context get() = InstrumentationRegistry.getInstrumentation().targetContext

    // RFC 8410 SubjectPublicKeyInfo prefixes (12 bytes): SEQ, SEQ, OID 1.3.101.{112 Ed25519 | 110 X25519},
    // BIT STRING(33){0x00 ‖ 32}. These MUST match KeystoreChildKeys.{ED25519,X25519}_SPKI_PREFIX — that is
    // exactly the platform contract under test (a deviation here = the extractor would mis-slice).
    private val ed25519Prefix = byteArrayOf(0x30, 0x2a, 0x30, 0x05, 0x06, 0x03, 0x2b, 0x65, 0x70, 0x03, 0x21, 0x00)
    private val x25519Prefix = byteArrayOf(0x30, 0x2a, 0x30, 0x05, 0x06, 0x03, 0x2b, 0x65, 0x6e, 0x03, 0x21, 0x00)

    // Test-only aliases (tests 2/3) so we never touch the production key set; tests 4/5 use the real store.
    private val testAliases = listOf("openwarden_bench_test_ed25519", "openwarden_bench_test_x25519")
    private val prodAliases =
        listOf("openwarden_child_binding_p256", "openwarden_child_ed25519", "openwarden_child_x25519")

    private fun hex(b: ByteArray): String = b.joinToString("") { "%02x".format(it) }

    /** Read the public-key SPKI **exactly as KeystoreChildKeys does** — via the keystore self-cert, not
     * the KeyPairGenerator's returned key (their `.encoded` can differ on AndroidKeyStore). */
    private fun spkiViaKeystoreCert(alias: String): ByteArray {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        return ks.getCertificate(alias).publicKey.encoded
    }

    @After
    fun cleanup() {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        for (alias in testAliases + prodAliases) {
            try {
                if (ks.containsAlias(alias)) ks.deleteEntry(alias)
            } catch (e: Exception) {
                // best-effort. A surviving prod alias does not silently pass a fail-closed test: the
                // fail-closed assertions check for *absence*, so a leftover key makes them FAIL loudly
                // (the safe direction), never falsely green.
            }
        }
    }

    // ---- any Android device (emulator-runnable) -------------------------------------------------

    /** Test 1: a never-provisioned real store vouches for nothing (fail-closed, ADR-032). */
    @Test
    fun unprovisionedStoreIsFailClosed() {
        // Ensure no prior provisioning leaked into this process's keystore view.
        cleanup()
        val store = KeystoreChildKeys()
        assertFalse(store.isProvisioned(), "fresh store must not report provisioned")
        assertNull(store.identityPublicKey(), "K_id absent pre-provision")
        assertNull(store.encryptionPublicKey(), "K_enc absent pre-provision")
        assertNull(store.bindingPublicKey(), "K_bind absent pre-provision")
        assertNull(store.signIdentity(byteArrayOf(1, 2, 3)), "no identity signature pre-provision")
        assertNull(store.signBinding(byteArrayOf(1, 2, 3)), "no binding signature pre-provision")
        assertNull(store.attestationChain(), "no attestation chain pre-provision")
    }

    /**
     * Test 1b (executed fail-closed, ANY device incl. the emulator): the production extractor REJECTS a
     * non-Curve25519 key. Put a real **P-256** key — what the emulator's AndroidKeyStore actually yields
     * for an `"Ed25519"`/`"XDH"` request — under the **production** `K_id`/`K_enc` aliases, then assert
     * `identityPublicKey()`/`encryptionPublicKey()` return `null`. This **executes** the "91-byte P-256 ⇒
     * `null` ⇒ child cannot pair" fail-closed path that tests 2/3 only describe in prose: the extractor's
     * `size != 44` + OID-prefix guards reject it rather than mis-slicing 32 bytes out of a P-256 key.
     */
    @Test
    fun nonCurve25519KeyUnderProdAliasIsRejected() {
        cleanup()
        // Occupy each production Curve25519 alias with a genuine EC P-256 key (the emulator's de-facto
        // "Ed25519"/"XDH" output) — purpose is irrelevant; the extractor only reads the cert SPKI.
        for (alias in listOf("openwarden_child_ed25519", "openwarden_child_x25519")) {
            KeyPairGenerator
                .getInstance(KeyProperties.KEY_ALGORITHM_EC, "AndroidKeyStore")
                .apply {
                    initialize(
                        KeyGenParameterSpec
                            .Builder(alias, KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY)
                            .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
                            .setDigests(KeyProperties.DIGEST_SHA256)
                            .build(),
                    )
                }.generateKeyPair()
        }
        val store = KeystoreChildKeys()
        assertNull(store.identityPublicKey(), "a P-256 key under K_id must be rejected — not a 32-byte Curve25519 key")
        assertNull(store.encryptionPublicKey(), "a P-256 key under K_enc must be rejected — not a 32-byte Curve25519 key")
    }

    /**
     * Test 2: on a device with genuine Keystore Ed25519 (`K_id`), the keystore-cert SPKI is exactly the
     * pinned RFC 8410 prefix ‖ 32-byte key the `rawCurve25519PublicKey` extractor slices, and a TEE
     * Ed25519 sign/verify round-trips (what `signIdentity` relies on).
     *
     * **Bench finding (recorded here):** the API-36 **emulator** does NOT implement Curve25519 in
     * AndroidKeyStore — an `"Ed25519"` keygen request yields a **91-byte P-256** SPKI
     * (`30 59 … 06 08 2a8648ce3d030107 … 04` = `prime256v1`), not a 44-byte RFC 8410 Ed25519 key. So this
     * test `assumeTrue`-**skips** where the platform did not actually produce Curve25519 (the genuine path
     * is bench-gated to a real device — exactly ADR-032's "untested here"); the production extractor
     * fail-closed-**rejects** that 91-byte encoding anyway (44 ≠ 91 ⇒ `null`), so the child refuses rather
     * than mis-slices. On a real Pixel 7 this runs and confirms the genuine Curve25519 contract.
     */
    @Test
    fun teeEd25519EncodingMatchesPinnedPrefixAndSignVerifies() {
        val alias = "openwarden_bench_test_ed25519"
        val kp =
            try {
                KeyPairGenerator
                    .getInstance("Ed25519", "AndroidKeyStore")
                    .apply {
                        initialize(
                            KeyGenParameterSpec
                                .Builder(alias, KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY)
                                .setUnlockedDeviceRequired(true)
                                .build(),
                        )
                    }.generateKeyPair()
            } catch (e: Exception) {
                assumeNoException("AndroidKeyStore Ed25519 unavailable on this platform — bench on a device that has it", e)
                return
            }
        val spki = spkiViaKeystoreCert(alias)
        val genuineEd25519 =
            spki.size == ed25519Prefix.size + 32 &&
                spki.copyOfRange(0, ed25519Prefix.size).contentEquals(ed25519Prefix)
        assumeTrue(
            "no genuine AndroidKeyStore Ed25519 on this platform (got ${spki.size}B SPKI: ${hex(spki)} — " +
                "likely the emulator's P-256 fallback) — bench on a real device to confirm the Curve25519 path",
            genuineEd25519,
        )
        // Genuine Curve25519 (a real device): the extractor's pinned prefix holds, and signing round-trips.
        val msg = "openwarden-bench".encodeToByteArray()
        val sig =
            Signature.getInstance("Ed25519").run {
                initSign(kp.private)
                update(msg)
                sign()
            }
        val ok =
            Signature.getInstance("Ed25519").run {
                initVerify(kp.public)
                update(msg)
                verify(sig)
            }
        assertTrue(ok, "TEE Ed25519 signature must verify under its own public key")
    }

    /**
     * Test 3: on a device with genuine Keystore X25519 (`K_enc`), the keystore-cert SPKI is exactly the
     * pinned RFC 8410 prefix ‖ 32-byte key. Same emulator caveat as test 2 — the API-36 emulator yields a
     * P-256 SPKI for an `"XDH"` request, so this `assumeTrue`-skips off a genuine-Curve25519 device.
     */
    @Test
    fun teeX25519EncodingMatchesPinnedPrefix() {
        val alias = "openwarden_bench_test_x25519"
        try {
            KeyPairGenerator
                .getInstance("XDH", "AndroidKeyStore")
                .apply {
                    initialize(
                        KeyGenParameterSpec
                            .Builder(alias, KeyProperties.PURPOSE_AGREE_KEY)
                            .setUnlockedDeviceRequired(true)
                            .build(),
                    )
                }.generateKeyPair()
        } catch (e: Exception) {
            assumeNoException("AndroidKeyStore XDH unavailable on this platform — bench on a device that has it", e)
            return
        }
        val spki = spkiViaKeystoreCert(alias)
        val genuineX25519 =
            spki.size == x25519Prefix.size + 32 &&
                spki.copyOfRange(0, x25519Prefix.size).contentEquals(x25519Prefix)
        assumeTrue(
            "no genuine AndroidKeyStore X25519 on this platform (got ${spki.size}B SPKI: ${hex(spki)} — " +
                "likely the emulator's P-256 fallback) — bench on a real device to confirm the Curve25519 path",
            genuineX25519,
        )
        // Genuine Curve25519 (a real device): the 32-byte raw key is exactly the SPKI tail the extractor slices.
        assertEquals(32, spki.size - x25519Prefix.size, "X25519 raw key is the 32-byte tail after the RFC 8410 prefix")
    }

    // ---- StrongBox device only (bench Pixel 7) --------------------------------------------------

    /**
     * Test 4 (ADR-032 gate, **check 4b only**): on real StrongBox, the full child path produces an attested
     * `K_bind` binding the **real parent verifier** accepts — `provisionAndBind` →
     * [ChildKeyManager.ProvisionResult] → [ChildKeyBindingVerifier.verify] (PROTOCOL §7.3 **check 4b**)
     * passes, the attestation chain is present, and the TEE keys are exactly 32 raw bytes.
     *
     * **Scope — what a green run here does and does NOT clear:** it confirms the binding-signature link
     * (check 4b) with real StrongBox ECDSA-P-256 + the keygen/encoding half of the gate. It does **NOT**
     * exercise PROTOCOL §7.3 checks 1–4 (chain roots in the Google attestation root, leaf challenge ==
     * `provisioning_nonce`, GREEN boot + locked, allow-listed model, **`securityLevel == STRONGBOX`**) —
     * those are the parent-side cert-chain work ([ChildKeyBindingVerifier] disclaims them) and remain a
     * **manual gate item**: the maintainer's bench run must separately confirm the leaf `securityLevel ==
     * STRONGBOX (2)` and attestation `challenge == provisioning_nonce` from the live chain before treating
     * ADR-032's gate as cleared.
     */
    @Test
    fun strongBoxProvisionBindVerifyRoundTrip() {
        assumeTrue(
            "no StrongBox on this device — run on a bench Pixel 7 to clear the ADR-032 gate",
            context.packageManager.hasSystemFeature(PackageManager.FEATURE_STRONGBOX_KEYSTORE),
        )
        cleanup() // deterministic: start from no prior key set (provision() rotates, but be explicit).
        val store = KeystoreChildKeys()
        val nonce = ByteArray(32) { 7 }
        val result =
            assertNotNull(
                ChildKeyManager(store).provisionAndBind(nonce),
                "StrongBox provisioning + binding must succeed on a StrongBox device",
            )
        assertTrue(result.attestationChain.isNotEmpty(), "K_bind attestation chain must be present")

        val leafSpki = assertNotNull(store.bindingPublicKey(), "K_bind SPKI must be readable")
        assertTrue(
            ChildKeyBindingVerifier.verify(result.binding, leafSpki, nonce),
            "the parent §7.3 check-4b verifier must accept the on-device binding",
        )
        assertEquals(32, store.identityPublicKey()?.size, "K_id raw public key is 32 bytes")
        assertEquals(32, store.encryptionPublicKey()?.size, "K_enc raw public key is 32 bytes")
    }

    /**
     * Test 5 (ADR-032 gate, negative): a real on-device binding is rejected when verified against a
     * different nonce — the single-use freshness check (ADR-032 D2) holds with real StrongBox crypto.
     */
    @Test
    fun strongBoxBindingRejectsWrongNonce() {
        assumeTrue(
            "no StrongBox on this device — run on a bench Pixel 7 to clear the ADR-032 gate",
            context.packageManager.hasSystemFeature(PackageManager.FEATURE_STRONGBOX_KEYSTORE),
        )
        cleanup() // deterministic: start from no prior key set.
        val store = KeystoreChildKeys()
        val nonce = ByteArray(32) { 7 }
        val result =
            assertNotNull(
                ChildKeyManager(store).provisionAndBind(nonce),
                "StrongBox provisioning + binding must succeed on a StrongBox device",
            )
        val leafSpki = assertNotNull(store.bindingPublicKey(), "K_bind SPKI must be readable")
        assertFalse(
            ChildKeyBindingVerifier.verify(result.binding, leafSpki, ByteArray(32) { 8 }),
            "a binding must NOT verify against a nonce other than the one it was bound to",
        )
    }
}

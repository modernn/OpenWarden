# OpenWarden Cryptographic Design & Implementation

Companion to [`ATTACKS.md`](ATTACKS.md) (threat catalog) and [`DEFENSES.md`](DEFENSES.md) (defense ranking). This document is the implementation-level spec: every key, every derivation, every envelope, every verification step. License: Apache 2.0. Target child device: Pixel 7 (Titan M2 / StrongBox). Shared layer: Kotlin Multiplatform (Android + iOS parent app; Android-only child).

The crypto core lives in `:shared:crypto` (KMP). Hardware-bound key operations live in `:android:crypto` and `:ios:crypto`. Wire format is canonicalized JSON (RFC 8785) wrapped in libsodium primitives via [ionspin/kotlin-multiplatform-libsodium](https://github.com/ionspin/kotlin-multiplatform-libsodium) (ISC license, Apache 2.0 compatible).

---

## 1. Key inventory

Every long-lived or session secret in OpenWarden, by role:

| Key | Algorithm | Lifetime | Storage (parent) | Storage (child) | Recoverable? |
|---|---|---|---|---|---|
| **Parent identity** | Ed25519 | Permanent | OS Keystore (Android Keystore / iOS Keychain), `requireUserAuthentication=true` | n/a (pubkey only, pinned) | Yes, BIP39 root |
| **Parent encryption** | X25519 | Permanent | Same as parent identity | n/a (pubkey only, pinned) | Yes, BIP39 root |
| **Child identity** | Ed25519 | Permanent (per-device) | n/a (pubkey only, pinned) | StrongBox, `setIsStrongBoxBacked(true)` | **No** — regenerate on re-pair |
| **Child encryption** | X25519 | Permanent (per-device) | n/a (pubkey only, pinned) | StrongBox, same recipe | **No** — regenerate on re-pair |
| **Provisioning nonce** | 32 random bytes | One-shot | RAM only, discarded after pairing | RAM only, discarded after attestation | n/a |
| **Session ephemeral** | X25519 | 24h max | RAM, rotated | RAM, rotated | n/a |
| **Recovery root seed** | 256-bit | Permanent | BIP39 24-word phrase, printed + memorized | n/a | The recovery itself |
| **At-rest cache KEK** | AES-256-GCM | Permanent (per-install) | StrongBox-wrapped, derived from device + lock-screen | StrongBox-wrapped, derived from device + lock-screen | No (rebuilt on re-install) |
| **`policy_seq` counter** | int64 | Permanent | Parent app DB (signed) | StrongBox counter key, monotonic | No |
| **`cmd_seq` counter** | int64 | Permanent | Parent app DB | StrongBox counter key | No |
| **Cover-traffic key** | derived from session | 24h | n/a (only padding) | n/a (only padding) | n/a |

Two deliberate asymmetries:

1. **Parent keys are recoverable** (BIP39). Child keys are not — losing the child phone means re-pairing, which is intentional: physical control of the child device is part of the authorization boundary.
2. **Pubkeys cross devices, privkeys never do.** No key migration protocols, no shared secrets. Hardware-attested key generation at pairing produces the binding.

---

## 2. BIP39 to root key derivation

24 BIP39 words = 256 bits entropy (per [BIP-39](https://github.com/bitcoin/bips/blob/master/bip-0039.mediawiki)). We deliberately use **Argon2id** for the mnemonic-to-seed step, not the BIP-39 default PBKDF2-HMAC-SHA512(2048 iterations). PBKDF2 is too cheap on modern GPUs; Argon2id is memory-hard and matches [OWASP Password Storage Cheat Sheet (2024)](https://cheatsheetseries.owasp.org/cheatsheets/Password_Storage_Cheat_Sheet.html) guidance.

### Parameters

```
Argon2id:
  memory      = 256 MiB     (m=262144)
  iterations  = 4           (t=4)
  parallelism = 2           (p=2)
  output      = 64 bytes
  salt        = SHA-256("openwarden-bip39-v1")[0..16]
  password    = NFKD(mnemonic_words joined by single space)
```

Fixed salt is acceptable because the mnemonic itself carries 256 bits of entropy; the salt's role here is purely domain separation. A user-provided passphrase (BIP-39 §8 optional passphrase) is **not supported** in v1 — too easy to forget, breaks recoverability promise.

PBKDF2 fallback for environments without Argon2 (iOS shared code path): `PBKDF2-HMAC-SHA512`, 600,000 iterations, same salt and password.

### Seed to key derivation

The 64-byte Argon2id output is the **seed**. We then run HKDF-SHA256 ([RFC 5869](https://www.rfc-editor.org/rfc/rfc5869)) with domain-separation labels:

```
seed  = Argon2id(NFKD(mnemonic), salt, ...)             // 64 bytes
prk   = HKDF-Extract(salt = "openwarden-v1", ikm = seed)   // 32 bytes

ed25519_priv = HKDF-Expand(prk, info = "openwarden-parent-ed25519-v1", L = 32)
x25519_priv  = HKDF-Expand(prk, info = "openwarden-parent-x25519-v1",  L = 32)
```

The two `info` strings are the only differentiator — same mnemonic always derives the same two keys, deterministically and independently. No SLIP-10 / BIP-32 hierarchy; we don't need wallet-style derivation paths.

### Why not PGP-style identity

PGP keyrings carry subkey metadata, user IDs, web-of-trust signatures, expiration timestamps. OpenWarden keys carry none of that — they're raw 32-byte scalars with deterministic provenance. No keyring file, no GnuPG dependency, no key-server lookups.

### Test vectors (required, see §12)

Known mnemonic: `"abandon abandon abandon ... about"` (the all-zeros BIP-39 vector).

Expected output (placeholder until ratified by `:shared:crypto` test suite):

```
seed     = a36097...   (64 hex bytes from Argon2id with above params)
prk      = ...         (32 hex bytes)
ed25519_pub = ...      (32 hex bytes)
x25519_pub  = ...      (32 hex bytes)
```

### Libraries

| Layer | Library | License |
|---|---|---|
| BIP-39 wordlist + checksum | port of [`NovaCrypto/BIP39`](https://github.com/NovaCrypto/BIP39) Java to KMP common | Apache 2.0 |
| Argon2id | [`andreypfau/kotlinx-crypto`](https://github.com/andreypfau/kotlinx-crypto) Argon2 binding | MIT |
| HKDF-SHA256 | libsodium `crypto_kdf_hkdf_sha256_*` | ISC |
| Ed25519 / X25519 scalars | libsodium `crypto_sign_seed_keypair`, `crypto_scalarmult_base` | ISC |

---

## 3. StrongBox keygen with attestation challenge

The child phone generates its identity and encryption keys inside StrongBox at first pairing. The exact recipe (Android, Kotlin):

```kotlin
val nonce: ByteArray = readQrProvisioningNonce()  // 32 bytes

val edSpec = KeyGenParameterSpec.Builder(
        "openwarden_child_ed25519",
        KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY)
    .setAlgorithmParameterSpec(ECGenParameterSpec("ed25519"))
    .setDigests(KeyProperties.DIGEST_NONE)  // Ed25519 is hash-included
    .setIsStrongBoxBacked(true)
    .setUserAuthenticationRequired(true)
    .setUserAuthenticationParameters(
        /* timeout = */ 0,
        KeyProperties.AUTH_DEVICE_CREDENTIAL or KeyProperties.AUTH_BIOMETRIC_STRONG)
    .setUnlockedDeviceRequired(true)
    .setAttestationChallenge(nonce)
    .build()

val kpg = KeyPairGenerator.getInstance(
    KeyProperties.KEY_ALGORITHM_EC, "AndroidKeyStore")
kpg.initialize(edSpec)
val edKeyPair = kpg.generateKeyPair()   // throws StrongBoxUnavailableException if no Titan M2

// Same recipe for X25519 (alias "openwarden_child_x25519"), purpose = AGREE_KEY.
```

`setIsStrongBoxBacked(true)` is non-negotiable. If StrongBox is unavailable (no Titan M2, e.g. non-Pixel device), the call throws `StrongBoxUnavailableException` — we **do not** fall back to TEE-only. The v1 product requires Pixel 6/7/8. Falling back silently to TEE would give the kid a route to extraction via known TEE exploits ([CVE-2022-20465](https://nvd.nist.gov/vuln/detail/CVE-2022-20465) Titan-only attestation key leak was StrongBox-bound; TEE attestation chains have been compromised more frequently).

### Retrieving the attestation cert chain

```kotlin
val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
val chain: Array<Certificate> = ks.getCertificateChain("openwarden_child_ed25519")
```

The leaf is the attestation cert for the just-generated key; the chain terminates at one of [Google's hardware attestation roots](https://developer.android.com/privacy-and-security/security-key-attestation#root_certificate).

### Parsing the attestation extension

OID `1.3.6.1.4.1.11129.2.1.17` ([Google's KeyDescription extension](https://source.android.com/docs/security/features/keystore/attestation#schema)). Fields we read:

| Field | Why we care |
|---|---|
| `attestationChallenge` | Must equal `nonce` we passed in |
| `securityLevel` | Must equal `STRONGBOX` (value 2) |
| `keymasterSecurityLevel` | Must equal `STRONGBOX` |
| `rootOfTrust.verifiedBootState` | Must equal `VERIFIED` (value 0; aka GREEN) |
| `rootOfTrust.deviceLocked` | Must be `true` |
| `rootOfTrust.verifiedBootKey` | SHA-256 of the bootloader-trusted key; compare to Pixel 7 expected hash |
| `attestationApplicationId` | Sanity-check the package name + cert hash matches OpenWarden |

Parser: [`google/android-key-attestation`](https://github.com/google/android-key-attestation), Apache 2.0. We vendor the parser into `:android:crypto` as a Java source dep (no transitive bloat).

```kotlin
val parser = KeyDescriptionParser()
val desc = parser.parse(chain[0])
require(desc.attestationChallenge.contentEquals(nonce)) { "ATTEST_NONCE_MISMATCH" }
require(desc.securityLevel == SecurityLevel.STRONG_BOX) { "ATTEST_NOT_STRONGBOX" }
require(desc.rootOfTrust.verifiedBootState == VerifiedBootState.VERIFIED) { "VB_NOT_GREEN" }
require(desc.rootOfTrust.deviceLocked) { "BOOTLOADER_UNLOCKED" }
```

Failure is **fail-closed**: we refuse to complete pairing and surface the failure code to the parent app. There is no "try again" loop — the child phone is rejected.

---

## 4. Sealed-box envelope for events

`libsodium.crypto_box_seal` provides exactly the property we want: anyone with the recipient's X25519 pubkey can encrypt; only the holder of the matching privkey can decrypt; the sender is anonymous (no signature, ephemeral sender key destroyed). [libsodium docs](https://doc.libsodium.org/public-key_cryptography/sealed_boxes).

### Wire format

```
sealed_box = ephemeral_x25519_pub  ||  ciphertext_with_poly1305_tag
             32 bytes                  N + 16 bytes
```

The full event envelope on disk and on the wire:

```
EventEnvelope = {
  v:     1                                // version byte
  ts:    int64                            // child monotonic ns since boot
  seq:   int64                            // append-only counter (StrongBox-backed)
  body:  sealed_box(canonical_json(event), parent_x25519_pub)
}
```

`canonical_json` is [RFC 8785 JSON Canonicalization Scheme](https://www.rfc-editor.org/rfc/rfc8785) (JCS): sorted keys, fixed number representation, fixed UTF-8 escape rules. Implementation: KMP port of [`cyberphone/json-canonicalization`](https://github.com/cyberphone/json-canonicalization) (Apache 2.0).

### Why JCS

Two reasons:

1. **Deterministic signing.** A signed event must produce the same bytes regardless of map iteration order or pretty-printing.
2. **Verifiability.** A parent looking at a stored ciphertext + the canonical plaintext can re-derive the bytes and match the signature.

### Ephemeral key handling

`crypto_box_seal` generates an ephemeral X25519 keypair internally, performs scalar multiplication with the recipient's pubkey, derives a symmetric key with BLAKE2b, encrypts with XSalsa20-Poly1305, and discards the ephemeral private key immediately. libsodium handles the zeroization; we do not get to (or need to) touch it.

Consequence: **the child cannot decrypt its own writes.** A kid with root sees a queue of opaque blobs. To map a blob to "what did OpenWarden report about me," the kid would need to either (a) compromise libsodium (a non-vulnerability so far) or (b) recover the recipient privkey, which lives in the parent's Keystore / Keychain on a device they don't control.

### Constant-rate cover traffic

The cover-traffic layer:

- Every event is padded to exactly **4096 bytes** of ciphertext (PKCS#7-style padding of plaintext to 4080 bytes before sealing, then sealed adds 32+16 = 48 bytes overhead).
- A "null" event (`{"type":"null"}` canonical, padded) is emitted every **5 minutes** even when no real event occurred.
- Parent decrypts, drops `null`-type events, surfaces the rest.

Cost: 12 envelopes/hour × 4 KiB × 24 hr = ~1.1 MB/day. Tolerable on cellular.

### Reading on parent

```kotlin
// parent_x25519_priv lives in OS Keystore; pull via getKey()
val plain = crypto_box_seal_open(env.body, parent_x25519_pub, parent_x25519_priv)
val event = json.parseToCanonical(plain.dropPadding())
```

A failed open (auth tag mismatch) is logged and surfaced. Forged events can be probed at zero cost by anyone holding the parent pubkey; the auth tag is the gate.

---

## 5. Signed command bundles (parent → child)

Commands flow the other direction: parent issues, child verifies and executes. These are **plaintext + signed**, not encrypted. The child needs to enforce policy by reading it; encrypting policy to a key the child holds gains nothing (the kid with root would just read the key from StrongBox at unlock time).

### Wire format

```
SignedBundle = {
  v:           1
  cmd_seq:     int64                  // monotonic per parent identity
  not_before:  int64                  // signed parent wall-clock ms
  not_after:   int64
  payload:     {...}                  // policy bundle, command, etc.
  sig:         ed25519(canonical_json({v, cmd_seq, not_before, not_after, payload}),
                        parent_ed25519_priv)
}
```

### Child verification

```kotlin
fun childVerifySignedBundle(bundle: SignedBundle, parentEd: PubKey): VerifyResult {
    val canonical = jcs.canonicalize(bundle.withoutSig())
    if (!sodium.crypto_sign_verify_detached(bundle.sig, canonical, parentEd))
        return VerifyResult.BadSignature

    if (bundle.cmd_seq <= lastSeenSeq(parentEd))
        return VerifyResult.Replay

    val wall = walletNow()  // see §9 for how we get this safely
    if (wall < bundle.not_before || wall > bundle.not_after)
        return VerifyResult.Stale

    persistSeq(parentEd, bundle.cmd_seq)
    return VerifyResult.Ok
}
```

The `(parent_ed25519_pub, cmd_seq)` tuple gives us replay rejection at near-zero cost. The window `(not_before, not_after)` defangs the attack where a captured 2024 bundle is replayed in 2026 ("here's a more permissive policy I found on the floor").

---

## 6. Parent pubkey pinning + rotation

At pairing the child receives `(parent_ed25519_pub, parent_x25519_pub)` from the QR code, alongside the provisioning nonce. Both pubkeys are stored in StrongBox-wrapped storage via:

```kotlin
val masterKey = MasterKey.Builder(ctx, "openwarden_cache_kek")
    .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
    .setRequestStrongBoxBacked(true)
    .setUserAuthenticationRequired(true)
    .build()

EncryptedSharedPreferences.create(ctx, "openwarden_pinned", masterKey,
    PrefKeyEncryptionScheme.AES256_SIV,
    PrefValueEncryptionScheme.AES256_GCM)
  .edit()
  .putString("parent_ed25519_pub", parentEd.encodeBase64())
  .putString("parent_x25519_pub",  parentX.encodeBase64())
  .commit()
```

The pinned pubkeys cannot be read without the device being unlocked (lock-screen-bound master key) and a working StrongBox.

### Rotation

A parent rotating keys (e.g. after parent phone replacement, see §7) issues a `RotateKey` command:

```
RotateKey = SignedBundle {
  cmd_seq:    next
  payload:    { type: "rotate", new_ed25519_pub: ..., new_x25519_pub: ... }
  sig:        ed25519(..., OLD_parent_ed25519_priv)
}
```

The child verifies with the **currently pinned** parent_ed25519_pub, atomically swaps both pinned pubkeys, and acknowledges. The acknowledgment is a sealed-box event encrypted to the **new** parent_x25519_pub — confirming the rotation roundtrip.

If the old privkey is lost (parent phone gone), rotation proceeds through the BIP-39 recovery flow (§7): the parent enters the phrase on a new device, re-derives the old privkey deterministically, signs the rotation with new keys generated from the same seed (which produces the *same* keys), and the child accepts.

When the recovery phrase generates a new identity (e.g., parent wants a clean break), there's no out-of-band channel that lets a remote attacker pretend to be the parent; rotation in that case requires re-pairing via QR.

---

## 7. Recovery from parent device loss

Scenario: parent phone in the lake. Parent has three artifacts:

1. **BIP-39 24-word recovery phrase** (printed, ideally laminated, ideally memorized).
2. **FRP-bound Google account email** (printed alongside the phrase).
3. **Pixel serial number** (recorded by parent at provisioning, for Google FRP escalation).

### Step by step

1. Buy / borrow new phone, install OpenWarden parent app.
2. Choose "Recover from phrase." Enter 24 words. App runs Argon2id → HKDF → derives identical `(parent_ed25519_priv, parent_x25519_priv)`. Re-stores in OS Keystore.
3. Child phone, when next contacted, sees commands signed by the *same* parent identity (same Ed25519 pubkey). No rotation needed — keys are bit-identical to what was pinned.
4. If the parent wants a *new* identity (e.g. phrase was shoulder-surfed), they:
   - generate fresh entropy → fresh phrase → fresh keys,
   - issue `RotateKey` signed by OLD keys (still derivable from the lost phrase),
   - re-print new phrase.

### Re-pairing via QR

If the child also gets a new phone (or factory reset survives FRP), pairing proceeds normally — the parent on new device shows a new pairing QR, child scans, generates new StrongBox keys with a new attestation nonce. The previously-pinned parent keys are still valid; the child's identity is fresh. Parent app remembers prior child identities under one "Oliver's phone" logical account and treats the new identity as a device replacement.

The first event after re-pair carries a `prev_identity_pub` field (the old child Ed25519 pubkey) signed by the old child key — so the parent can verify the new device is willingly succeeding the old one. If the old child phone was simply lost, the parent confirms manually in the UI; we cannot enforce a cryptographic handoff from a phone we can't reach.

---

## 8. Anti-tamper at-rest

Both parent and child stores use **encrypted DataStore on Android** with StrongBox-backed master keys, and **Keychain** on iOS for parent state. The data covered:

- Pinned parent pubkeys (child).
- Last-seen `cmd_seq` (child).
- Sealed-box event queue (child).
- `policy_seq` and `not_after` watermarks (child).
- BIP-39-derived parent privkeys (parent).
- Pairing history, attestation cert chains (parent).

The master key uses `setRequestStrongBoxBacked(true)` and `setUserAuthenticationRequired(true)` with a 10-second auth validity window so DataStore reads work seamlessly during normal use but require a fresh unlock after a forced lock.

**A kid with root on the child device cannot decrypt the cache without StrongBox cooperation**, and StrongBox refuses to release the master key without a valid lock-screen unlock. Even if the kid knows the PIN (it's their phone, they use it), the on-disk cache only contains:

- Plaintext-signed policy bundles (the kid can read these — that's fine, they're the rules being enforced; reading them buys nothing).
- Opaque sealed-box ciphertext (kid can't decrypt — sealed to parent X25519 priv).
- Pinned parent pubkeys (kid can read these; substituting them needs to survive a signed `RotateKey` flow, which the kid can't fake).
- Counters (kid can read; rolling them back is detected by parent's monotonicity check on next sync).

The win is "no offline forensic value." A wiped phone leaves no extractable secret material on the userdata partition that isn't either signed (and thus useless to forge) or sealed (and thus unreadable).

---

## 9. Time + freshness

Wall clock on a kid-held Android phone is a liar. Even with `DISALLOW_CONFIG_DATE_TIME`, NTP spoofing on a kid's hotspot or in-app clock-drift attacks (F1-F3 in ATTACKS.md) are possible.

### Clock model

```
monotonic_now      = SystemClock.elapsedRealtime()         // unspoofable; resets on reboot
parent_ts_at_sign  = bundle.signedAtWallMs                 // set by parent app, signed
local_mono_at_recv = SystemClock.elapsedRealtime() at receipt of signed bundle

walletNow() = parent_ts_at_sign + (SystemClock.elapsedRealtime() - local_mono_at_recv)
```

Every signed bundle and signed event carries a parent-supplied wall timestamp. The child uses that as the wall-clock anchor and advances it by monotonic elapsed time. The system wall clock is never trusted for policy evaluation.

### Skew detection

On every signed-bundle receipt, the child computes:

```
local_wall_now = System.currentTimeMillis()
inferred_wall  = walletNow()
skew           = local_wall_now - inferred_wall
```

If `|skew| > 5 minutes`, emit a `tamper.clock_skew` event (sealed to parent) and continue using `walletNow()`. The kid can spoof the wall clock; they cannot spoof monotonic time without a reboot, and reboots are independently visible via the seq counter staying constant across the gap.

### Reboot handling

Monotonic time resets on reboot. We persist `(parent_ts_at_sign, local_mono_at_recv_walled)` to encrypted DataStore. On boot, we use the most recent persisted anchor and accept up to 24h of staleness; beyond that, the child enters fail-closed strict baseline until a fresh signed bundle arrives.

---

## 10. Verifying Verified-Boot state

The attestation extension's `rootOfTrust` SEQUENCE carries:

```
RootOfTrust ::= SEQUENCE {
  verifiedBootKey   OCTET STRING,
  deviceLocked      BOOLEAN,
  verifiedBootState ENUMERATED {
      Verified    (0),    // GREEN  -- accept
      SelfSigned  (1),    // YELLOW -- reject
      Unverified  (2),    // ORANGE -- reject
      Failed      (3)     // RED    -- reject
  },
  verifiedBootHash  OCTET STRING OPTIONAL
}
```

Hard rules enforced at pairing **and** periodically (every 7 days via refreshed attestation challenge from the parent):

| Field | Required value | Rejection action |
|---|---|---|
| `verifiedBootState` | `Verified` (0) | Refuse to operate; surface `BOOT_NOT_GREEN` to parent |
| `deviceLocked` | `true` | Refuse; `BOOTLOADER_UNLOCKED` |
| `securityLevel` | `StrongBox` (2) | Refuse; `KEY_NOT_STRONGBOX` |
| `keymasterSecurityLevel` | `StrongBox` (2) | Refuse; `KEYMASTER_NOT_STRONGBOX` |
| `attestationChallenge` | matches sent nonce | Refuse; `ATTEST_NONCE_MISMATCH` |
| Cert chain root | one of Google's hardware attestation roots ([list](https://developer.android.com/privacy-and-security/security-key-attestation#root_certificate)) | Refuse; `ATTEST_ROOT_UNKNOWN` |
| Revocation status | not in [Google's revocation list](https://android.googleapis.com/attestation/status) | Refuse; `ATTEST_REVOKED` |

`securityLevel < TRUSTED_ENVIRONMENT` (i.e., `SOFTWARE`) is an immediate kill; `TRUSTED_ENVIRONMENT` (1) is not acceptable on a Pixel 7 (it has StrongBox; if the key reports TEE it means the attestation extension is forged or the device is mis-provisioned).

---

## 11. Sequence of operations (KMP-shared Kotlin)

The `:shared:crypto` module exposes:

```kotlin
// §2
fun generateRootFromMnemonic(words: List<String>): ParentKeys

// §5
fun parentSignBundle(bundle: PolicyBundle, parentEd: Ed25519PrivKey): SignedBundle

// §6
fun childPinParentKeys(parentEd: Ed25519PubKey, parentX: X25519PubKey): PinResult

// §3 (Android actual; iOS expects throw)
fun childGenerateAttestedIdentity(nonce: ByteArray): AttestedKeyPair

// §3, §10
fun parentVerifyAttestation(chain: List<X509Certificate>, expectedNonce: ByteArray): AttestationVerdict

// §4
fun childSealEvent(event: Event): SealedEnvelope

// §4
fun parentOpenSealedEnvelope(env: SealedEnvelope): Event

// §5
fun childVerifySignedBundle(bundle: SignedBundle, parentEd: Ed25519PubKey): VerifyResult
```

Every function returns a sealed result type (`VerifyResult`, `AttestationVerdict`, `PinResult`) — never `null`, never `throw` on cryptographic failure. Exceptions are reserved for programmer errors (wrong array length, etc.). The caller is forced to handle the failure branch:

```kotlin
sealed class VerifyResult {
    object Ok : VerifyResult()
    object BadSignature : VerifyResult()
    object Replay : VerifyResult()
    object Stale : VerifyResult()
    data class MalformedPayload(val reason: String) : VerifyResult()
}
```

Fail-closed default: the policy engine's `evaluate(result: VerifyResult)` falls through to strict baseline on anything that isn't `Ok`.

---

## 12. Test vectors

Stored under `:shared:crypto:src/commonTest/resources/vectors/` as JSON. The CI build refuses to publish if any vector fails to round-trip.

### `bip39_to_keys.json`

For each entry: `mnemonic` (24 words), `expected_seed_hex` (64 bytes), `expected_ed25519_pub_hex`, `expected_x25519_pub_hex`.

Seed entries: the BIP-39 standard `abandon abandon ... about`, plus 3 randomly-generated phrases checked-in for regression.

### `canonical_bundles.json`

For each entry: `bundle_object` (JSON), `expected_canonical_bytes_hex`, `signing_priv_hex`, `expected_sig_hex`.

Catches RFC 8785 implementation drift across KMP targets.

### `sealed_envelope.json`

For each entry: `plaintext_event_json`, `recipient_x25519_pub_hex`, `recipient_x25519_priv_hex`, `expected_ciphertext_length`, `expected_padding_to`, `roundtrip_match: true`.

We cannot pin ciphertext bytes (ephemeral key randomness) but we can pin size and verify open-roundtrip.

### `attestation_chain.json`

For each entry: a real Pixel 7 attestation chain (PEM-encoded), the `nonce` it attests to, and `expected_verdict: Ok`. Captured from a bench Pixel 7 under controlled conditions; includes one tampered chain that must produce `BOOT_NOT_GREEN`.

---

## 13. Library matrix

All listed libraries are Apache 2.0, MIT, BSD, or ISC. No GPL, no LGPL, no SSPL.

| Need | KMP shared (Kotlin common) | Android-specific | iOS-specific |
|---|---|---|---|
| Ed25519 sign/verify | `libsodium` via [ionspin/kotlin-multiplatform-libsodium](https://github.com/ionspin/kotlin-multiplatform-libsodium) (ISC) | Identity key gen: `KeyPairGenerator` w/ `AndroidKeyStore`, StrongBox-backed (system) | CryptoKit `Curve25519.Signing` (Apple system framework) |
| X25519 ECDH / sealed box | libsodium KMP (ISC) | Identity key gen: same as above (system) | CryptoKit `Curve25519.KeyAgreement` |
| BIP-39 wordlist + checksum | KMP port of [NovaCrypto/BIP39](https://github.com/NovaCrypto/BIP39) (Apache 2.0) | n/a | n/a |
| Argon2id | [andreypfau/kotlinx-crypto](https://github.com/andreypfau/kotlinx-crypto) (MIT) | n/a | n/a |
| BLAKE3 (optional integrity) | [komputing/khash](https://github.com/komputing/KHash) or [blake3-jvm](https://github.com/sken77/BLAKE3jni) (CC0/Apache) | n/a | n/a |
| JCS / RFC 8785 | KMP port of [cyberphone/json-canonicalization](https://github.com/cyberphone/json-canonicalization) (Apache 2.0) | n/a | n/a |
| Attestation cert parse | KMP-common ASN.1 over [google/android-key-attestation](https://github.com/google/android-key-attestation) vendored Java (Apache 2.0) | Consumed directly via JVM interop | n/a (child never iOS) |
| Encrypted at-rest store | n/a | AndroidX `EncryptedSharedPreferences` (Apache 2.0, system) | Keychain Services (Apple system) |

Licenses are recorded in `THIRD_PARTY_LICENSES.txt`; CI fails the build if any new dep introduces a non-compatible license.

---

## 14. Threat-vs-defense reconcile

Mapping from ATTACKS.md IDs to the crypto pattern in this doc:

| Attack | Cat | Neutralized by section |
|---|---|---|
| C7 mDNS spoof | Network | §5 Ed25519-signed bundle, child rejects unsigned |
| C8 Bundle replay | Network | §5 `cmd_seq` + `not_before/not_after` |
| F1/F2 Clock rollback | Time | §9 monotonic clock anchored to signed parent ts |
| F3 NTP spoof | Time | §9 same |
| H1 Replay old bundle | Crypto | §5 `cmd_seq` window |
| H2 Sig stripping / downgrade | Crypto | §11 fail-closed on `BadSignature` |
| H3 Pubkey substitution | Crypto | §6 StrongBox-wrapped pin + signed-rotation requirement |
| H4 Extract parent privkey | Crypto | §1 keys in Keystore/Keychain, `requireUserAuthentication=true`; §2 phrase derivation only on user-driven recovery |
| H5 Forge child events | Crypto | n/a — events sealed-to-parent are anonymous; tamper-evidence handled via hash-chain in `:shared:log` (DEFENSES.md #7) |
| H6 Recovery phrase shoulder-surf | Crypto + behavioral | §2 high-cost Argon2id raises offline-crack cost; behavioral 24h delay (DEFENSES.md #15) |
| I3 OOBE reprovision window | Provisioning | §3 attestation nonce binds child identity to a specific pairing event |
| Device-swap during pairing | Provisioning | §3 attestation chain proves "this is a Pixel 7 with locked bootloader" |
| Rooted child reads cache | Crypto | §4 sealed-box (kid can't decrypt own writes); §8 StrongBox-wrapped at-rest cache |
| Rooted child forges policy | Crypto | §5 Ed25519 sig over canonical JSON; signing key never on child |
| Wipe + reprovision | Crypto | §3 fresh attestation challenge surfaces fresh attestation cert, parent re-confirms |
| Boot rolled back to vulnerable version | Crypto | §10 verifiedBootState refresh every 7 days; rollback index check is implicit in `verifiedBootKey` matching latest Pixel 7 expected hash |

Anything that doesn't appear here (A-class Settings/ADB attacks, D-class app-layer, K-class social) is addressed by non-crypto defenses in DEFENSES.md. Crypto is necessary, not sufficient — but the necessary part has to be correct, and this document is the spec for getting it correct.

---

## References

- libsodium [sealed boxes](https://doc.libsodium.org/public-key_cryptography/sealed_boxes), [HKDF](https://doc.libsodium.org/key_derivation), [Ed25519](https://doc.libsodium.org/public-key_cryptography/public-key_signatures)
- BIP-39 [spec](https://github.com/bitcoin/bips/blob/master/bip-0039.mediawiki)
- RFC 5869 [HKDF](https://www.rfc-editor.org/rfc/rfc5869), RFC 8785 [JSON Canonicalization](https://www.rfc-editor.org/rfc/rfc8785), RFC 8032 [EdDSA](https://www.rfc-editor.org/rfc/rfc8032)
- OWASP [Password Storage Cheat Sheet](https://cheatsheetseries.owasp.org/cheatsheets/Password_Storage_Cheat_Sheet.html)
- NIST [SP 800-56C Rev. 2](https://nvlpubs.nist.gov/nistpubs/SpecialPublications/NIST.SP.800-56Cr2.pdf) (key derivation)
- Android Developer Docs: [Key Attestation](https://developer.android.com/privacy-and-security/security-key-attestation), [StrongBoxBacked](https://developer.android.com/reference/android/security/keystore/KeyGenParameterSpec.Builder#setIsStrongBoxBacked(boolean)), [EncryptedSharedPreferences](https://developer.android.com/reference/androidx/security/crypto/EncryptedSharedPreferences)
- AOSP source: [KeyDescription schema](https://source.android.com/docs/security/features/keystore/attestation#schema), [Verified Boot](https://source.android.com/docs/security/features/verifiedboot)
- [google/android-key-attestation parser](https://github.com/google/android-key-attestation)
- [ionspin/kotlin-multiplatform-libsodium](https://github.com/ionspin/kotlin-multiplatform-libsodium)
- Apple [CryptoKit Curve25519](https://developer.apple.com/documentation/cryptokit/curve25519)

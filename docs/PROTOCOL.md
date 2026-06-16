# OpenWarden Wire Protocol v1

Status: Normative. SPDX-License-Identifier: Apache-2.0.

This document specifies the **wire format** for OpenWarden peer-to-peer sync. It extends — not duplicates — [`ARCHITECTURE.md`](../ARCHITECTURE.md) (planes, transport modes), [`STORE_AND_FORWARD.md`](STORE_AND_FORWARD.md) (sync semantics + transport tier-list), [`ATTACKS.md`](ATTACKS.md) (replay = critical), and [`DEFENSES.md`](DEFENSES.md) (sealed-box, StrongBox attestation, fail-closed). Read those first.

Anything written here is binding on a "OpenWarden-compatible" implementation. Anything not written here is forbidden.

Inspirations cited inline: SSB feed format (single-writer append-only), Briar BSP (multi-transport agnosticism), Olm/Signal (sealed sender), libsodium [`crypto_box_seal`](https://doc.libsodium.org/public-key_cryptography/sealed_boxes), BLAKE3 ([paper](https://github.com/BLAKE3-team/BLAKE3-specs)), RFC 8785 JCS.

---

## 1. Log entry format

Each peer owns exactly one append-only log. Parent's log carries commands; child's log carries events. There is **no third log**, no shared log, no consensus.

### 1.1 Canonical JSON schema

```json
{
  "v": 1,
  "seq": 0,
  "prev_hash": "b3:0000000000000000000000000000000000000000000000000000000000000000",
  "issued_at": 1734307200000,
  "payload_type": "PolicyBundle",
  "payload": { },
  "sig": "ed25519:base64url(64bytes)"
}
```

Field semantics:

| Field | Type | Required | Notes |
|---|---|---|---|
| `v` | u8 | yes | Schema version. v1 = `1`. Unknown `v` → fail-closed reject. |
| `seq` | u53-bounded integer (0 .. 2^53−1) | yes | Monotonic, starts at `0`, increments by exactly `1`. No gaps. Values above 2^53−1 are rejected `MALFORMED` before signature verification. |
| `prev_hash` | string | yes | `"b3:" + hex(BLAKE3-256(canonicalized_prev_entry_bytes))`. For `seq=0`, the all-zero hash above (genesis sentinel). |
| `issued_at` | u53-bounded integer (0 .. 2^53−1) | yes | Unix milliseconds, writer's signed claim. Values above 2^53−1 are rejected `MALFORMED` before signature verification. Authoritative only when writer is parent (see §5). |
| `payload_type` | enum string | yes | One of: `PolicyBundle`, `Event`, `SealedEvent`, `AckPolicy`, `AckCommand`, `Heartbeat`, `PairingHandshake`. |
| `payload` | object | yes | Schema determined by `payload_type` (§1.3). |
| `sig` | string | yes | `"ed25519:" + base64url(Ed25519_sign(canonical_bytes_without_sig_field, owner_privkey))`. Unpadded. |

### 1.2 BLAKE3 hashing

`prev_hash` is `BLAKE3-256` of the **canonicalized bytes of the entire previous entry, including its `sig`**. Hashing post-sig (rather than pre-sig) means a tampered signature on entry N is detected at entry N+1 even if entry N's `sig` field is locally re-forged with a wrong key. We use BLAKE3-256 (32-byte digest) for speed on ARM and to avoid SHA-2 collision-extension concerns. The `b3:` prefix is a hash-algorithm tag so v2 can migrate (e.g. `b3:` → `sha3:`) without re-keying the chain.

### 1.3 Payload types

#### `PolicyBundle`
Full schema in §2.

#### `Event` (parent-decryptable on its own)
Reserved for cases where the parent already knows the content (test vectors, debugging). **Production child→parent events MUST use `SealedEvent`.** Senders are NOT permitted to ship `Event` in v1 except for test fixtures.

#### `SealedEvent`
Outer envelope (§6):
```json
{ "ciphertext": "base64url(sodium_sealed_box)" }
```

#### `AckPolicy`
```json
{ "policy_seq": 42, "result": "applied" | "rejected", "reason": "string" }
```
`reason` only present if `result = "rejected"`. Reason codes: `EXPIRED`, `REGRESSION`, `SIG_FAIL`, `CLOCK_SKEW`, `MALFORMED`, `STORAGE_FULL`.

#### `AckCommand`
```json
{ "ref_seq": 17, "result": "ok" | "err", "code": "string" }
```
`ref_seq` is the parent-log seq being acked.

#### `Heartbeat`
```json
{ "uptime_ms": 12345, "battery_pct": 73, "padding": "base64url(N bytes)" }
```
`padding` is sized so the serialized entry hits a constant 2048 bytes total. Constant-size heartbeats defeat traffic-analysis correlation of event semantics (Defense #13 in [`DEFENSES.md`](DEFENSES.md)).

#### `PairingHandshake`
See §7.

### 1.4 Size constraints

**Maximum canonical entry size: 65536 bytes (64 KiB).**

Justifications:
- Email-relay transport (S&F tier 3, [`STORE_AND_FORWARD.md`](STORE_AND_FORWARD.md) §Transports) caps practical attachment chunks ~75 KB.
- SMS-fragmentation transport tops out around 30 fragments × 153 bytes = 4.6 KB; a 64 KB cap keeps SMS for `Heartbeat` and `AckCommand` only.
- StrongBox-wrapped storage on Pixel 7 Titan M2 has page-cost beyond ~64 KB.
- Largest legitimate payload (`PolicyBundle` w/ ~500 allowlist entries) is ~24 KB; 64 KB leaves 2.5× headroom.

Entries exceeding 64 KiB MUST be rejected at parse time, before signature verification, with reason `MALFORMED`.

---

## 2. PolicyBundle schema

The most security-critical artifact in the system. Every byte is signed, every field is verified, every field is fail-closed on mismatch.

```json
{
  "v": 1,
  "policy_seq": 42,
  "child_device_id": "base64url(child_ed25519_pub_32_bytes)",
  "issued_at": 1734307200000,
  "not_before": 1734307200000,
  "not_after":  1736899200000,
  "nonce": "9f1b3c4d5e6f70819a2b3c4d5e6f7081",
  "policy": {
    "allowlist":   ["com.android.chrome", "com.life360.android.safetymapd"],
    "blocklist":   ["com.discord", "com.roblox.client"],
    "windows":     [{"pkg": "com.google.android.youtube", "allow": "16:00-18:00", "days": "Mon,Tue,Wed,Thu,Fri", "tz": "America/Los_Angeles"}],
    "restrictions": ["DISALLOW_CONFIG_VPN", "DISALLOW_FACTORY_RESET", "DISALLOW_SAFE_BOOT", "DISALLOW_DEBUGGING_FEATURES", "DISALLOW_OEM_UNLOCK", "DISALLOW_USER_SWITCH", "DISALLOW_ADD_USER", "DISALLOW_MODIFY_ACCOUNTS", "DISALLOW_CONFIG_DATE_TIME", "DISALLOW_APPS_CONTROL"],
    "private_dns": "openwarden.example.com",
    "frp_account_email": "parent@example.com"
  },
  "sig": "ed25519:base64url(64bytes)"
}
```

Fields:

| Field | Type | Required | Notes |
|---|---|---|---|
| `v` | u8 | yes | Bundle schema version. Bumped only on format break. |
| `policy_seq` | u53-bounded integer (0 .. 2^53−1) | yes | **Device-global monotonic floor at child.** Child rejects any bundle with `policy_seq ≤ floor` with reason `REGRESSION`. `policy_seq = 0` is reserved and never a live policy. Values above 2^53−1 are rejected `MALFORMED` before signature verification. Closes attack H1/C8 ([`ATTACKS.md`](ATTACKS.md)). |
| `child_device_id` | base64url string (32 bytes) | yes | **Audience binding.** The addressed child's pinned Ed25519 pubkey (or a stable id derived from it). Child rejects `MALFORMED` any bundle where this field does not match its own identity — checked before signature verification. Prevents cross-child replay under one parent key. |
| `issued_at` | u53-bounded integer (0 .. 2^53−1) | yes | Parent's claimed authorship time, ms. Values above 2^53−1 are rejected `MALFORMED` before signature verification. |
| `not_before` | u53-bounded integer (0 .. 2^53−1) | yes | Earliest legal application time, ms. Values above 2^53−1 are rejected `MALFORMED` before signature verification. |
| `not_after` | u53-bounded integer (0 .. 2^53−1) | yes | Latest legal application time, ms. Hard deadline → stale-policy mode. Values above 2^53−1 are rejected `MALFORMED` before signature verification. |
| `nonce` | hex string (32 chars, 16 bytes) | yes | CSPRNG-fresh per bundle. Distinct from `policy_seq` — `policy_seq` defeats replay, `nonce` defeats parallel forgery of identical content. |
| `policy.allowlist` | string[] | yes | Package names that may launch. May be empty (= deny-all). |
| `policy.blocklist` | string[] | no | Hard-deny even if otherwise allowed. |
| `policy.windows` | object[] | no | Per-app time windows. Evaluated against monotonic clock + signed parent `issued_at` (§5). |
| `policy.restrictions` | string[] | yes | DPC `DISALLOW_*` set to apply. |
| `policy.private_dns` | string | no | Hostname for `setGlobalPrivateDnsMode`. Defense #17. |
| `policy.frp_account_email` | string | no | Pinned via `setFactoryResetProtectionPolicy`. |
| `sig` | string | yes | Ed25519 over canonicalized bundle minus the `sig` field. |

### 2.1 Verification algorithm

```
verify_bundle(bundle, pinned_parent_ed25519_pub, my_child_device_id,
              floor := max(at_rest_floor, highest_policy_seq_in_chain),
              monotonic_now_ms):
  1.  if bundle.v != 1:                                 reject MALFORMED
  2.  if size(canonicalize(bundle)) > 65536:            reject MALFORMED
  3.  if any int/timestamp field > 2^53−1:              reject MALFORMED  // JCS integer bound
  4.  if bundle.child_device_id != my_child_device_id:  reject MALFORMED  // audience binding
  5.  body := canonicalize(bundle without "sig" field)
  6.  if not Ed25519.verify(bundle.sig, body, pinned_parent_ed25519_pub):
                                                        reject SIG_FAIL  (fail-closed)
  7.  if bundle.policy_seq <= floor:                    reject REGRESSION
  8.  if bundle.policy_seq > floor + MAX_SEQ_JUMP:      reject MALFORMED  // floor-poison DoS guard
  9.  if monotonic_now_ms < bundle.not_before:          reject CLOCK_SKEW (defer, retry on heartbeat)
  10. if monotonic_now_ms >= bundle.not_after:          reject EXPIRED    (stale-policy mode)
  11. if anchor_or_not_after_watermark rolled back:     enter STRICT BASELINE (anomaly — see §5.1)
  // ---- two-phase durable apply; floor advances LAST ----
  12. STAGE the new policy to a temp record                     // not yet live
  13. apply(bundle.policy); fsync the applied policy + staged record  // durable
  14. ADVANCE floor := bundle.policy_seq               // at-rest DataStore + chain mirror, AFTER step 13
  15. emit AckPolicy{policy_seq, "applied"}            // chain witness
```

`MAX_SEQ_JUMP = 1024`.

Steps 6 and 9–11 are **fail-closed**: parse error, signature failure, clock anomaly, or storage write failure MUST leave the previous bundle in force OR enter stale-policy mode. Never "unrestricted."

**Crash safety:** a crash between steps 12 and 14 leaves the old floor in force, so the same valid bundle re-applies cleanly on restart (idempotent). A crash never strands a legitimate bundle as a permanent `REGRESSION`.

---

## 3. RFC 8785 canonicalization (JCS)

All Ed25519 signing and BLAKE3 hashing operate on **JCS canonical bytes** ([RFC 8785](https://www.rfc-editor.org/rfc/rfc8785)). This is non-negotiable: an implementation that signs over a JSON serializer's default output is not OpenWarden-compatible.

### 3.1 JCS rules (summary)

1. UTF-8, no BOM.
2. Object members sorted by UTF-16 code unit of the key (RFC 8785 §3.2.3).
3. No insignificant whitespace. No trailing newline.
4. Numbers serialized per RFC 8785 §3.2.2.3 (ECMAScript 6 `Number.prototype.toString`). Integers without exponent or fraction; floats as shortest round-tripping form. **OpenWarden entries use integers only.** Floats in `payload` are forbidden — reject `MALFORMED`. All integer and timestamp fields (`seq`, `policy_seq`, `issued_at`, `not_before`, `not_after`) MUST be in the range `0 .. 2^53−1` (9007199254740991); values outside this range are rejected `MALFORMED` before signature verification. This ensures signer and verifier always produce byte-identical JCS canonical bytes (RFC 8785 §3.2.2.3 round-trips exactly within this range).
5. Strings: shortest valid JSON escape; only `\"`, `\\`, `\b`, `\f`, `\n`, `\r`, `\t`, and `\u00XX` for control codes; all other characters literal UTF-8.
6. `null` is forbidden in OpenWarden. Omit the key instead.
7. Booleans literal `true` / `false`.
8. Arrays preserve insertion order (NOT sorted).

**Sign-and-transmit-exact-bytes (ADR-019).** The signer transmits the exact canonical bytes it
signed — the canonical JCS encoding of the object with its `sig` removed **is** the wire body. The
verifier verifies the signature over the bytes it received, verbatim, and only then parses. A
verifier MUST NOT parse a document and re-canonicalize it before verifying; that reintroduces
signer/verifier byte drift and fails valid signatures.

### 3.2 Why JCS, not JWS/JOSE

RFC 7515 JWS signs over base64url(header).base64url(payload). That introduces:
- Encoder/decoder asymmetry (canonicalization moves into base64 framing).
- A second parser surface area (JOSE header) which has been a CVE generator (alg=none, alg confusion).
- Unsigned-but-trusted fields possible via header.

JCS signs over the raw canonical JSON bytes. One parser, no header confusion, no `alg` field to substitute. Briar, SSB, and matrix-spec all explicitly canonicalize their own bytes; we follow that lineage.

### 3.3 Reference implementations

- **Kotlin (child):** [`com.github.cyberphone:json-canonicalization:1.0.2`](https://github.com/cyberphone/json-canonicalization) — official RFC 8785 reference impl. Apache 2.
- **Swift / Dart (parent):** `package:json_canonical` (pub.dev) for Flutter; for Swift parent app, port cyberphone's algorithm (it's ~300 lines).
- **Rust (Iroh node v2):** [`serde_jcs`](https://crates.io/crates/serde_jcs) crate.
- **Go (relay v3+):** [`github.com/gowebpki/jcs`](https://github.com/gowebpki/jcs).

All four MUST produce byte-identical output for the test vectors in §9. CI cross-validates.

### 3.4 Test vectors (excerpt; full set in §9)

| Input | Canonical bytes (UTF-8, hex) |
|---|---|
| `{"b": 1, "a": 2}` | `7b2261223a322c2262223a317d` → `{"a":2,"b":1}` |
| `{"x": [3, 1, 2]}` | `7b2278223a5b332c312c325d7d` → `{"x":[3,1,2]}` (array order preserved) |
| `{"é": 1, "e": 2}` | `7b2265223a322c2022c3a9223a317d` → `{"e":2,"é":1}` (UTF-16 code-unit sort) |

---

## 4. Sync handshake state machine

When any transport ([`STORE_AND_FORWARD.md`](STORE_AND_FORWARD.md) §Transports) opens a channel, both peers run this state machine. v1 transport is LAN mDNS + REST (`_openwarden._tcp.local`, HTTPS with self-signed cert pinned post-pairing); v2 adds Iroh.

### 4.1 States

| State | Meaning |
|---|---|
| `IDLE` | No active session. Listening on transport. |
| `PROBING` | Peer discovered via mDNS / Iroh ticket. TLS handshake in progress. Cert pinning against pubkey. |
| `EXCHANGING_DIGEST` | Sent / awaiting `HelloDigest`. |
| `SENDING_ENTRIES` | Streaming `EntriesBatch` to peer per their requested range. |
| `RECEIVING_ENTRIES` | Receiving `EntriesBatch`, verifying chain, persisting on each verified entry (crash-safe). |
| `RECONCILED` | Both peers have acknowledged each other's tip. Emit `Bye`. |
| `ERROR` | Transition target on any verification failure. Log reason, drop session, do not blacklist transport (a different bundle next time may succeed). |

### 4.2 Messages

| Message | Direction | Body |
|---|---|---|
| `HelloDigest` | both | `{ "my_log_tip": {"seq": N, "hash": "b3:..."}, "your_log_seen_through": {"seq": M, "hash": "b3:..."} }` |
| `SendRange` | both | `{ "from_seq": M+1, "to_seq": N }` |
| `EntriesBatch` | both | `{ "entries": [Entry, Entry, ...] }` (≤32 entries per batch, ≤1 MiB total) |
| `Ack` | both | `{ "received_through_seq": K, "hash": "b3:..." }` |
| `Bye` | both | `{ "reason": "reconciled" \| "timeout" \| "error", "code": "..." }` |

### 4.3 Transitions

```
IDLE ── transport opens ──► PROBING
PROBING ── cert-pin ok ──► EXCHANGING_DIGEST
PROBING ── cert-pin fail ──► ERROR
EXCHANGING_DIGEST ── HelloDigest sent + received ──► SENDING_ENTRIES + RECEIVING_ENTRIES (parallel)
SENDING_ENTRIES ── peer Ack covers our tip ──► RECONCILED (when paired with RECEIVING_ENTRIES → done)
RECEIVING_ENTRIES ── entry verify fail ──► ERROR
RECEIVING_ENTRIES ── all received & acked ──► RECONCILED
RECONCILED ── Bye exchanged ──► IDLE
ERROR ── drop session ──► IDLE
```

### 4.4 Timeouts

mDNS is slow; cellular is slower. Timeouts reflect transport class.

| Transport | `PROBING` | per-batch `RECEIVING_ENTRIES` | total session |
|---|---|---|---|
| mDNS-LAN | 5 s | 10 s | 60 s |
| Iroh (v2) | 15 s | 20 s | 180 s |
| Email relay (v3) | 5 min | 30 min | 24 h |
| SMS (v3) | 60 s/frag | n/a | 24 h |

Timeout → `ERROR` → `IDLE`. Re-attempt on next discovery cycle.

### 4.5 Idempotency

A partial sync over Wi-Fi resumes over cellular because **the chain is the source of truth, not the session.** Each peer persists every verified entry **before** sending `Ack`. If the channel drops mid-batch:
- Receiver's persistent `last_seen[peer]` advances only for entries actually written.
- Next session's `HelloDigest` reflects current persistent tip → sender re-streams from there.
- Entries are content-addressed by `(seq, prev_hash)`; re-receiving an already-applied entry is a no-op (verify equality, drop).

### 4.6 Error recovery

| Failure | Behavior |
|---|---|
| Corrupt entry (JSON parse) | `ERROR`, log `MALFORMED`, drop session, do NOT advance tip. |
| Sig verify fail | `ERROR`, log `SIG_FAIL`. If repeated 3× from same pubkey → emit silence alarm to user, suspect pubkey-swap attack (H3). |
| Hash chain break | `ERROR`, log `CHAIN_BREAK`. Indicates writer forked their log → re-pair required. |
| Expired bundle in stream | Accept entry (it's history). Do not apply to live policy. |
| Out-of-order seq | `ERROR`, log `OOO`. Receiver MUST refuse gaps; sender MUST stream in order. |

---

## 5. Replay + freshness

Replay was rated CRITICAL in [`ATTACKS.md`](ATTACKS.md) (H1, C8). The mandate:

1. **`policy_seq` floor is device-global and best-effort at-rest + chain-mirrored.** The floor is persisted in TEE/StrongBox-bound encrypted DataStore (best-effort at-rest integrity; not rollback-proof) and mirrored as a chain witness in the child's append-only event log. On read, `floor := max(at_rest_floor, highest_policy_seq_in_chain)`. The **parent is the authoritative monotonicity anchor**: on every sync it checks the child's reported floor and chain tip for regression or fork (`CHAIN_BREAK`), and on any anomaly raises a silence/tamper alarm and pushes a fresh strict-baseline bundle at a `policy_seq` above the highest ever seen. A bundle with `policy_seq ≤ floor` is rejected `REGRESSION`.
2. **`not_before` / `not_after` is a strict window.** Outside the window → reject. No grace period beyond what's already encoded in `not_after`. Default `not_after` is intentionally short (hours, not weeks) to minimize the replay window on any local rollback.
3. **Stale-policy mode** on `EXPIRED`: strictest baseline (allowlist = essentials only: dialer, parent app, school app), banner "Ask parent to sync," heartbeat retries every 60 s instead of 5 min. Stale-policy mode persists until a fresh, valid, in-order, audience-matched bundle is applied — never times out into "unrestricted."
4. **Child fails closed to strict baseline on any local anomaly.** Anomaly conditions: at-rest floor reads lower than a `policy_seq` already witnessed in the chain; chain break (`prev_hash` mismatch); provisioning marker present but floor missing; time-anchor rollback (§5.1). Strict baseline persists until a valid bundle with `policy_seq > floor` is successfully applied.
5. **After N hours without parent contact, the child ratchets toward the strict baseline** rather than coasting on the last permissive policy. See offline/silence alarm ladder in [`ATTACKS.md`](ATTACKS.md) (parent alerts at 15 min / 1 h / 6 h / 24 h).
6. **Genesis (TOFU) vs anomaly.** A never-provisioned child (no provisioning marker, no pinned parent key, no floor) TOFU-accepts the first valid signed bundle with `policy_seq ≥ 1`, pins the parent pubkey, writes the provisioning marker, and seeds the floor to that `policy_seq`. `policy_seq = 0` is reserved and never a live policy. A provisioned child with a missing or lower floor is an **anomaly**, not genesis — fail closed to strict baseline.

### 5.1 Clock sources

The child MUST NOT trust the wall clock for window evaluation. Sources, in order of authority:

| Source | Use | Trust level |
|---|---|---|
| `SystemClock.elapsedRealtime()` | Time-since-boot deltas | High (kernel-monotonic, survives clock changes) |
| `bundle.issued_at` at apply time | Anchor → "system was at T0 when policy applied" | High (signed by parent) |
| `Heartbeat.issued_at` from parent log | Periodic re-anchor | High (signed by parent) |
| `System.currentTimeMillis()` wall clock | Display only | Low (kid can attack indirectly even with `DISALLOW_CONFIG_DATE_TIME`) |

Window evaluation uses `parent_anchor + (elapsedRealtime_now - elapsedRealtime_at_anchor)`. `SystemClock.elapsedRealtime()` is kernel-monotonic and survives wall-clock changes. The anchor is re-established only by a signed parent timestamp (`bundle.issued_at` at apply time, or `Heartbeat.issued_at`).

The persisted `(parent_anchor, elapsedRealtime_at_anchor)` pair is treated as monotonic. If, on read, the stored anchor or the last-applied `not_after` watermark is **lower** than one already witnessed — i.e., the anchor was rolled back — that is an **anomaly** and the child immediately enters strict baseline (§5 item 4). This defeats the snapshot-revival variant of F1/F2 (an expired-but-never-applied bundle cannot be revived by rolling back the time anchor). If wall clock diverges from the monotonic estimate by more than 24 h, child enters stale-policy mode and waits for the next signed parent timestamp. Defeats F1/F2/F3.

---

## 6. Event encryption (sealed box)

Child events are encrypted to the parent's X25519 pubkey using libsodium [`crypto_box_seal`](https://doc.libsodium.org/public-key_cryptography/sealed_boxes). The child writes ciphertext it cannot decrypt — Pattern B in [`DEFENSES.md`](DEFENSES.md).

### 6.1 Plaintext schema (pre-seal)

```json
{
  "kind": "APP_LAUNCH" | "APP_BLOCK" | "GEOFENCE_EXIT" | "AI_FLAG" | "INSTALL_REQUEST" | "TIME_REQUEST",
  "at_ms": 1734307200000,
  "data": { /* kind-specific */ }
}
```

Canonicalized via §3 before sealing.

### 6.2 Sealing

```
ephemeral_pub, ephemeral_priv := X25519.keypair()
nonce  := BLAKE3-256(ephemeral_pub || parent_x25519_pub)[0..24]
key    := X25519(ephemeral_priv, parent_x25519_pub)
cipher := XSalsa20-Poly1305(key, nonce, canonical_plaintext)
sealed := ephemeral_pub || cipher
ephemeral_priv  := zeroized (libsodium handles)
```

This is the libsodium sealed-box construction verbatim. The ephemeral sender key never persists.

### 6.3 Outer envelope (signed)

```json
{
  "v": 1,
  "seq": 173,
  "prev_hash": "b3:...",
  "issued_at": 1734307200000,
  "payload_type": "SealedEvent",
  "payload": { "ciphertext": "base64url(sealed)" },
  "sig": "ed25519:base64url(...)"
}
```

The Ed25519 signature is over the canonical envelope minus `sig`. Sealing is for confidentiality (kid w/ root); signing is for authenticity (kid w/ root cannot forge events). Both are required.

### 6.4 Heartbeat padding

Every `Heartbeat` and `SealedEvent` envelope MUST be padded so the canonical entry size equals a constant 2048 bytes. Use `padding` (Heartbeat) or oversized plaintext (SealedEvent) to reach the constant. This implements Defense #13.

---

## 7. Pairing handshake

Pairing is the one-time bootstrap that pins both pubkeys. Failure here is unrecoverable except by re-pair. Cf. [`PROVISIONING.md`](PROVISIONING.md) for OOBE flow.

### 7.1 QR payload (parent → child, displayed on parent)

```json
{
  "v": 1,
  "parent_ed25519_pub": "base64url(32)",
  "parent_x25519_pub":  "base64url(32)",
  "provisioning_nonce": "base64url(32)",
  "transport_hints": { "mdns": "_openwarden._tcp.local", "iroh_ticket": "..." }
}
```

32-byte nonce CSPRNG-fresh per pair attempt. Single-use.

### 7.2 Child response

The child app, freshly DPC-provisioned:

1. Scans QR.
2. Calls Android Keystore `generateKey` with `setIsStrongBoxBacked(true)`, `setAttestationChallenge(provisioning_nonce)`, `setUnlockedDeviceRequired(true)`. Generates both Ed25519 and X25519 in StrongBox.
3. Retrieves the attestation certificate chain.
4. POSTs to parent's pairing endpoint (discovered via `transport_hints`):

```json
{
  "v": 1,
  "child_ed25519_pub": "base64url(32)",
  "child_x25519_pub":  "base64url(32)",
  "child_attestation_cert_chain": ["base64(DER)", "base64(DER)", "base64(DER)"]
}
```

### 7.3 Parent verification

```
1. Parse cert chain, root MUST be Google Hardware Attestation root.
2. Leaf cert extension MUST contain provisioning_nonce as the attestation challenge.
3. Leaf extension MUST report:
     - verifiedBootState = VERIFIED  (GREEN)
     - bootloader locked = true
     - device = "Pixel 7" or other allow-listed model
     - attestation security level = STRONGBOX
4. Public key in leaf cert MUST equal child_ed25519_pub.
5. If any check fails: refuse pair. Show parent: "Device failed hardware attestation."
```

### 7.4 Six-emoji confirmation

After cert verification, both screens derive and display a confirmation:

```
shared_secret_for_display := HKDF-SHA256(
    salt = "openwarden-pair-v1",
    ikm  = parent_ed25519_pub || child_ed25519_pub,
    info = provisioning_nonce
)[0..15]
6 emojis := SAS-emoji-table-lookup-from-Signal-spec(shared_secret_for_display)
```

Parent visually compares. Parent taps "Match" → child's pubkey enters `pinned` state in parent app; parent's pubkey is StrongBox-wrapped on child. Mismatch → abort pair, surface MITM warning.

### 7.5 Pinning on child

The child writes `(parent_ed25519_pub, parent_x25519_pub)` to a StrongBox-wrapped encrypted file. Rotation MUST require the parent's BIP39 recovery phrase + 24-h delay (Defense #15). Closes H3.

---

## 8. Versioning & migration

- The on-wire `v` field is `1` for this entire spec.
- **Forward compat:** verifiers MUST ignore unknown top-level fields in `payload` for known `payload_type` values. New optional fields can be added in v1.x without bumping `v`.
- **Format break = v2.** Any breaking change (changed canonicalization, new mandatory field, hash algorithm change, signature suite swap) MUST bump `v` to `2`. v1 child + v2 parent MUST re-pair from scratch; the chain does not migrate. There is no "v1.5 transitional."
- The implementation MUST refuse to write entries with `v` it cannot fully serialize and refuse to verify entries with `v` it cannot fully validate.

---

## 9. Test vectors

Test vectors are shipped as JSON files alongside this doc at [`docs/test-vectors/`](test-vectors/). Implementations MUST pass all of them byte-for-byte before claiming compatibility.

### 9.1 Required files

| File | Purpose |
|---|---|
| `test-vectors/parent-keys.json` | Fixed parent Ed25519 + X25519 keypairs (privkey + pubkey) for repeatable signing. |
| `test-vectors/bundles/bundle-01-minimal.json` + `.canonical` + `.sig` | Smallest legal bundle. |
| `test-vectors/bundles/bundle-02-full.json` + `.canonical` + `.sig` | Every field present. |
| `test-vectors/bundles/bundle-03-unicode.json` + `.canonical` + `.sig` | Non-ASCII package names + emoji in policy strings (canonicalization stress). |
| `test-vectors/bundles/bundle-04-rejected-regression.json` | `policy_seq` below prior; expect `REGRESSION`. |
| `test-vectors/replay-floor/bundle-05-reject-seq-overflow.json` | `policy_seq = 2^53` (above JCS-safe bound); expect `MALFORMED` before signature verification. |
| `test-vectors/replay-floor/bundle-06-reject-seq-jump.json` | `policy_seq = floor + 1025` (exceeds `MAX_SEQ_JUMP = 1024`); expect `MALFORMED`. |
| `test-vectors/replay-floor/bundle-07-reject-wrong-audience.json` | `child_device_id` in bundle does not match child's own id; expect `MALFORMED` before signature verification. |
| `test-vectors/replay-floor/snapshot-rollback.json` | at-rest floor < highest `policy_seq` in chain witness; expect child enters strict baseline (anomaly, not `REGRESSION`). |
| `test-vectors/replay-floor/crash-during-apply.json` | Same bundle re-applied after simulated crash (floor not yet advanced); expect no `REGRESSION` — apply is idempotent. |
| `test-vectors/replay-floor/time-anchor-rollback.json` | Persisted time anchor rolled back below last-seen value; expect child enters strict baseline. |
| `test-vectors/events/event-01-applaunch.json` + `.sealed` | App-launch event, sealed to known parent pubkey. |
| `test-vectors/events/event-02-aiflag.json` + `.sealed` | AI flag event with payload data. |
| `test-vectors/events/event-03-heartbeat.json` + `.sealed` | Heartbeat at exactly 2048 bytes post-canonical. |
| `test-vectors/pairing/pair-01-success.json` | Full handshake transcript with valid Pixel 7 attestation chain (synthetic root for testing). |
| `test-vectors/pairing/pair-02-fail-not-verified.json` | Attestation chain reports `verifiedBootState=ORANGE`; expect refusal. |

Each `.canonical` is the exact JCS UTF-8 output. Each `.sig` is the base64url Ed25519 signature using `parent-keys.json`'s privkey. CI runs cross-implementation parity:

```
for vector in test-vectors/**/*.json:
    assert kotlin_canonicalize(vector) == python_reference_canonicalize(vector) == swift_canonicalize(vector) == rust_canonicalize(vector)
    if vector has .sig:
        assert Ed25519.verify(sig, canonical, parent_pub)
```

---

## 10. Conformance checklist

An alternative implementation is **OpenWarden-compatible** iff it satisfies every item below.

### 10.1 Cryptography
- [ ] Ed25519 signing matches RFC 8032; vectors pass.
- [ ] X25519 ECDH matches RFC 7748.
- [ ] Sealed box matches libsodium `crypto_box_seal` byte-for-byte.
- [ ] BLAKE3-256 matches the official BLAKE3 reference (no truncation tricks).
- [ ] HKDF-SHA256 for SAS emoji derivation.

### 10.2 Canonicalization
- [ ] RFC 8785 (JCS) implementation passes vectors in §9.
- [ ] Rejects `null`, floats, NaN, ±Inf in `payload`.
- [ ] UTF-8 output, no BOM, no trailing newline.

### 10.3 Log
- [ ] Append-only, single-writer, monotonic `seq` starting at 0.
- [ ] `prev_hash` = BLAKE3-256 of full canonical previous entry **including** its `sig`.
- [ ] Genesis `prev_hash` = 32 zero bytes hex-encoded with `b3:` prefix.
- [ ] Entries >64 KiB rejected pre-verify with `MALFORMED`.

### 10.4 PolicyBundle verification
- [ ] Verification follows §2.1 exactly; fails-closed on every error path.
- [ ] `policy_seq` regression rejected.
- [ ] `not_before` / `not_after` strictly enforced.
- [ ] Stale-policy mode on expiry; never falls back to "unrestricted."

### 10.5 Sync
- [ ] State machine matches §4.
- [ ] Tip persisted **before** Ack.
- [ ] Partial sync over one transport resumes over another (idempotency test).
- [ ] Sig-fail count surfaces silence alarm at threshold 3.

### 10.6 Events
- [ ] All child→parent production events use `SealedEvent`; raw `Event` accepted only from test fixtures.
- [ ] Heartbeat + SealedEvent canonical size = exactly 2048 bytes.
- [ ] Ephemeral sender keys destroyed post-seal.

### 10.7 Pairing
- [ ] StrongBox-backed keypair generation w/ `provisioning_nonce` as attestation challenge.
- [ ] Cert-chain validation up to Google Hardware Attestation root; rejects ORANGE / YELLOW / RED.
- [ ] Six-emoji SAS derived per §7.4; mismatch aborts pair.
- [ ] Pinned parent pubkey rotation requires recovery phrase + 24-h delay.

### 10.8 Fail-closed posture
- [ ] Sig parse error → strict baseline applied.
- [ ] Missing policy file → strict baseline applied.
- [ ] Storage write failure → strict baseline + alert.
- [ ] Clock anomaly → strict baseline until re-anchored.

### 10.9 Required CI tests
- [ ] All §9 vectors pass byte-for-byte across all language implementations the project ships.
- [ ] Negative-vector suite (`pair-02-fail-*`, `bundle-04-rejected-*`) rejects with the expected reason code.
- [ ] Fuzz test: 1M random byte sequences fed to entry parser; zero crashes, zero false-accepts.
- [ ] Replay test: re-stream prior policy bundle → child rejects `REGRESSION`.

An implementation that ticks every box above and exhibits the documented stale-policy / fail-closed behavior under the negative tests can mark itself "OpenWarden-compatible v1."

---

## References

- RFC 8785 — JSON Canonicalization Scheme (JCS).
- RFC 8032 — Edwards-Curve Digital Signature Algorithm (Ed25519).
- RFC 7748 — Elliptic Curves for Security (X25519).
- libsodium docs — `crypto_box_seal`, `crypto_sign_detached`.
- BLAKE3 specification — O'Connor et al., 2020.
- Briar BSP — Bramble Synchronisation Protocol; multi-transport, single-writer logs.
- Secure Scuttlebutt — single-writer signed feed; inspiration for our log shape.
- Signal Olm / X3DH — sealed sender + SAS-emoji authentication.
- Android Hardware-backed Key Attestation — verified-boot + StrongBox cert chain validation.

# Store-and-forward protocol

OpenWarden's communication model is **store-and-forward**, not request-response. Parent and child each maintain an append-only log; transports are interchangeable; offline is the default state, online is the exception. No transport is the source of truth.

## Inspirations

| Project | What we steal |
|---|---|
| **Briar** | Multi-transport (Bluetooth, Wi-Fi direct, Tor), no central servers, peer-issued identity |
| **Secure Scuttlebutt (SSB)** | Append-only log per peer, gossip sync, content-addressed |
| **Delta Chat** | Uses existing infrastructure (SMTP/IMAP) as opportunistic transport |
| **Hypercore / Earthstar** | Tiny syncable datasets, multi-device |
| **Matrix / Olm** | Forward-secret ratcheted E2EE for direct peer messages |
| **Meshtastic** | Reliable delivery over unreliable mesh |
| **Veilid** | Modern P2P routing primitives |

## Logs

Each peer owns exactly one **append-only log**, single-writer. Other peers replicate.

```
Entry = {
  seq:        monotonic u64
  prev_hash:  blake3(prev entry serialized)
  issued_at:  unix ms
  payload:    one of {PolicyBundle, Event, AcknowledgeReq, AcknowledgePolicy}
  sig:        Ed25519(payload || prev_hash || seq, owner_privkey)
}
```

Parent's log carries: policy bundles, lock/unlock commands, install approvals, time-bank credits.
Child's log carries: events (app launched, blocked, geofence, AI flag), state pings, "more time?" requests.

Replay: append-only + monotonic seq + prev_hash chain = tamper-evident. Re-pairing requires a fresh log (seq resets).

## Sync protocol

When transport opens between peers:

```
Peer A → Peer B: "I have log_A through seq=N_A; I last saw log_B through seq=M_B"
Peer B → Peer A: "I have log_B through seq=N_B; I last saw log_A through seq=M_A"
Peer A → Peer B: entries M_A+1..N_A from log_A
Peer B → Peer A: entries M_B+1..N_B from log_B
Both verify Ed25519 sig + prev_hash chain on every entry. Append valid entries.
```

Idempotent. Crash-safe (entries are written before ACK). Multi-transport friendly (a partial sync over Wi-Fi resumes over cellular).

## Transports (any combination)

Each transport is a `dyn Transport` implementing `try_sync(remote_addr) -> Result<SyncReport>`. The DPC tries all enabled transports on a schedule + on-demand.

| Transport | Mode | Range | Latency | Battery | Privacy |
|---|---|---|---|---|---|
| **mDNS-LAN** | direct TCP on home LAN | same network | seconds | low | great (no third party) |
| **Bluetooth LE** | when proximity detected | ~30 ft | seconds | low | great |
| **NFC bump** | tap-to-sync | touching | seconds | none | great |
| **QR exchange** | display on phone A, scan on phone B | line-of-sight | manual | none | great |
| **WireGuard** | self-hosted home VPN endpoint | anywhere | seconds | low-medium | great (your endpoint) |
| **Tailscale** | free tier, opt-in | anywhere | seconds | medium | good (coordinator visible) |
| **Email relay (Delta Chat-style)** | SMTP/IMAP via existing accounts | anywhere | minutes | low | medium (provider visible) |
| **SMS** | signed payload chunks via 160-char SMS | anywhere with cell signal | minutes | low | poor (carrier visible) |
| **Project DERP** (v3+) | self-hostable relay you can run on a $5 VPS | anywhere | seconds | low | good if relay is yours |

**Default v1:** LAN-only. **v2:** + WireGuard, Tailscale, QR-bump. **v3:** + email, SMS, project DERP.

## End-to-end encryption

Parent + child each generate an Ed25519 identity (signing) + X25519 keypair (encryption) at first launch. During pairing, the X25519 pubkeys are exchanged + pinned along with Ed25519 identity pubkeys.

Each log entry's `payload` is encrypted to the recipient via `box(payload, recipient_pubkey, sender_privkey)` (NaCl/libsodium box semantics). Transports see only opaque ciphertext + a "sender → recipient" hint.

**Sealed sender** (Signal-style) for transports that leak metadata (email, SMS, DERP): an outer envelope encrypted to the relay's well-known pubkey, hiding the sender from passive observers.

**Forward secrecy**: Olm-style double-ratchet on the payload channel. Compromised present key ≠ historical content readable. Out of scope for v1 (policy bundles don't really benefit from FS), in scope for v3 if we add AI-summary message channels.

## Reliability + retries

- Each entry has a delivery state per (peer, transport): `pending | sent | ack'd`
- Background retry: every transport tries unsent entries every 15min when network changes
- After 24h with no ACK on any transport: parent app raises "child unreachable" alert
- After expiry of active policy bundle: child enters **stale-policy mode** — stricter than nominal, banner shown to kid: "Ask dad to sync"

## Failure modes covered

| Scenario | Behavior |
|---|---|
| Child offline 8 hours | Last bundle enforced. No new commands. Events queue. |
| Child offline 30 days | Bundle expires. Stale-policy mode. Kid told to find parent. |
| Parent phone lost | Recovery phrase → new device → re-pair via QR. Child log restarts with new parent identity; old log archived. |
| Parent + child both offline | Each queues. Next contact (any transport) reconciles. |
| Mobile network only, CGNAT both sides | WireGuard/Tailscale relay if configured. Else BLE-on-meet. Else email. Else SMS. Else next time you're both home on Wi-Fi. |
| Transport compromised | Entries are signed + encrypted; eavesdropper sees opaque bytes |
| Replay attack | seq + prev_hash + monotonic — old entries rejected |
| Coercer tries to inject command | No matching parent privkey = no valid sig |

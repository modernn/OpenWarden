# DNS Resolver

Implementation doc for the on-device OpenWarden DNS resolver. This is the v2 follow-on to [`DNS_FILTER.md`](DNS_FILTER.md) §11 — the "tiny localhost DoT resolver inside the DPC" — promoted to v1 because video-CDN session tracking, per-app caps, and parent-visible query logs all collapse onto the same component. License targets: Apache 2.0 / MIT / BSD only. F-Droid clean.

---

## 1. Why a local resolver

Setting `setGlobalPrivateDnsMode(PRIVATE_DNS_MODE_PROVIDER_HOSTNAME, "1dot1dot1dot1.cloudflare-dns.com")` already gives us a filtered, encrypted resolver. It is the floor in [`DNS_FILTER.md`](DNS_FILTER.md) and it works. What it does not give us:

- **Visibility.** Cloudflare sees every query. OpenWarden sees none. The parent dashboard cannot show "your kid tried to open pornhub.com at 9:14pm" because that information lives in Cloudflare's logs, not on the device.
- **Per-app caps.** "No YouTube after 8pm" needs OpenWarden to map a CDN hostname (`*.googlevideo.com`) to a policy decision (block now / allow until 8pm). Cloudflare has no notion of the kid's bedtime or daily cap.
- **Video session tracking.** Sustained queries to a video CDN are the cheapest signal Android gives us for "kid is actively watching video right now." We need the queries to flow through our code, not Cloudflare's.

Three architectural options:

- **Option A — VPN mode.** OpenWarden runs as an Always-On `VpnService`, owns the tun interface, and intercepts DNS packets directly. Maximum flexibility, including per-package routing. ~5–10% battery cost. VPN reconfigure windows interrupt traffic. Conflicts with the kid using any other VPN (homework MDM, school net, etc.).
- **Option B — Private DNS to localhost.** OpenWarden runs a DoT server bound to `127.0.0.1:853`. DPC points `PRIVATE_DNS_MODE_PROVIDER_HOSTNAME` at a hostname that resolves to `127.0.0.1`. Battery cheaper than VPN. No tun interface. Loses per-package selectivity (one resolver serves all apps), which is fine for v1 — we are not yet shipping per-app DNS overrides.
- **Option C — Hybrid.** Local DoT server on `127.0.0.1:853` does policy, classification, and logging; forwards approved queries upstream to Cloudflare 1.1.1.3 over DoT. Cache layer between. Local cert pinned and rotated by OpenWarden.

**Recommend Option C.** Battery acceptable, no VPN dependency, integrates with the existing DoT floor as an upstream rather than replacing it. Option A stays on the v2 shelf for the per-app-override use case in [`DNS_FILTER.md`](DNS_FILTER.md) §8.

---

## 2. Android API path

| Path | Control | Battery | Reconfigure cost | Verdict |
|---|---|---|---|---|
| `VpnService` w/ DNS intercept | Full per-packet | 5–10% / day | High (tun teardown) | v2 only |
| Private DNS → `127.0.0.1:853` (local DoT) | Full per-query | 1–2% / day | None | **v1 choice** |
| Local DoH server on `127.0.0.1:443` | Same as DoT | Comparable | None | v2; Private DNS doesn't speak DoH yet |
| Hybrid (local DoT → upstream DoT) | Full per-query + cache | 1.5–2% / day | None | **Recommended composition** |

The hybrid composes inside the Private-DNS-to-localhost path: the local DoT server is what the OS sees, and inside the process we forward to Cloudflare. The OS never knows the difference.

---

## 3. Library landscape

Constraints: Apache 2.0 / MIT / BSD only (per [`SIMPLIFY.md`](SIMPLIFY.md) license discipline), pure Kotlin/Java preferred to keep APK small and F-Droid build reproducible, no gomobile or native blob.

- **dnsjava** (BSD-2). Mature Java DNS library. Heavy historical baggage, server-side bits we don't need, but battle-tested.
- **mini-dns** (Apache 2.0). Modern, Kotlin-friendly, small. Used in Smack/XMPP. Strong DNS-over-* support.
- **Knot Resolver** (GPL-3.0). Skip on license.
- **PowerDNS Recursor** (GPL-2.0). Skip on license.
- **CoreDNS** (Apache 2.0, Go). Embeddable only via gomobile; adds ~10 MB to APK and a second runtime. Skip for v1.
- **dnscrypt-proxy** (ISC, Rust). Same problem — would need JNI + Rust toolchain in the F-Droid build. Skip for v1.
- **BouncyCastle** (MIT). TLS server primitives for the DoT listener. Already a transitive dep via crypto stack in [`CRYPTO.md`](CRYPTO.md).
- **ktor-server-core** (Apache 2.0). Only if we add a DoH endpoint in v2.

**Recommendation:** `mini-dns` for wire parsing + caching, BouncyCastle for the TLS listener, custom Kotlin glue for policy and classification. Pure Kotlin, ~400 KB added to APK, no native code.

---

## 4. Resolver state machine

One query passes through this pipeline:

1. **Accept.** DoT listener on `127.0.0.1:853` accepts TLS connection from the OS resolver. Cert presented is a OpenWarden-issued localhost cert pinned by the DPC; rotated daily, stored in Android Keystore.
2. **Parse.** `mini-dns` decodes the DNS message. Reject malformed, ANY, AXFR, IXFR.
3. **Policy check.** Lookup hostname against the active policy bundle (signed by parent, see [`PROTOCOL.md`](PROTOCOL.md)). Categories: blocked-category, blocked-hostname, capped-CDN, bedtime-blocked, allowlisted, neutral.
4. **Block decision.** If blocked → synthesize `NXDOMAIN`, log event, return.
5. **Classify.** Run the video-CDN classifier (§5). Update session state. Emit session-start / session-continue marker if threshold crossed.
6. **Cache check.** Positive cache hit honoring upstream TTL → return cached answer.
7. **Forward.** Open (or reuse pooled) DoT connection to upstream (Cloudflare 1.1.1.3 by default; see [`DNS_FILTER.md`](DNS_FILTER.md) §3 for alternates). One in-flight query per upstream connection; multiplex via keep-alive.
8. **Receive.** Decode upstream answer. Validate transaction ID. Honor upstream TTL but cap at 3600s.
9. **Cache.** Insert into LRU cache keyed by `(qname, qtype)`. Negative answers cached 60s.
10. **Log.** Append sealed-box event (§8) for interesting queries only — CDN hits, blocks, first-seen domains. Routine cache hits do not generate events.
11. **Return.** Send DNS response over the DoT connection. Close or keep-alive per OS resolver behavior (Android tends to keep-alive).

Failures at any stage land in §11.

---

## 5. Video-CDN classification

Goal: turn a stream of DNS queries into "kid started watching YouTube at 7:42pm, still watching at 7:58pm." This is the cheapest passive signal we have; richer signals require accessibility-service-level introspection that we are deliberately not doing.

**Pattern table.** Curated list of CDN hostname patterns and the apps they back. Examples (non-exhaustive):

| Pattern | Service | App-hint |
|---|---|---|
| `*.googlevideo.com` | YouTube | `com.google.android.youtube`, `com.google.android.youtube.kids` |
| `*.akamaized.net` + Referer signal | Multiple (Disney+, HBO, TikTok edge) | varies |
| `*.muscdn.com`, `*.tiktokcdn.com` | TikTok | `com.zhiliaoapp.musically` |
| `*.cdninstagram.com` Reels path | Instagram Reels | `com.instagram.android` |
| `*.nflxvideo.net` | Netflix | `com.netflix.mediaclient` |
| `*.ttvnw.net`, `video-weaver.*.hls.ttvnw.net` | Twitch | `tv.twitch.android.app` |
| `*.media.dssott.com` | Disney+ | `com.disney.disneyplus` |
| `*.hbomaxcdn.com` | HBO Max | `com.wbd.stream` |

Each pattern carries a `service` tag. App-hint is informational; we cannot prove which app issued the query (the OS resolver is shared), but we publish app-hint to the parent as a "likely source."

**Session heuristic.** Naive count-based works well enough for v1:

- Sliding 30s window per service tag.
- 5+ queries to the same service in window → mark session **active**.
- 30s of no queries to that service → mark session **ended**.
- Sessions shorter than 30s collapse into a single "browse" event, not a "watch" event.

Tunables live in the policy bundle so we can adjust without an app update.

**Output.** Per session: `{service, started_at, ended_at, query_count, app_hint}`. One sealed-box event per session boundary (start + end), not per query.

---

## 6. Per-app caps

Policy bundle ships a `video_caps` block:

```json
{
  "video_caps": [
    {
      "service": "youtube",
      "max_minutes_per_day": 60,
      "time_windows": [{"start": "07:00", "end": "20:00"}]
    },
    {
      "service": "tiktok",
      "max_minutes_per_day": 0
    }
  ]
}
```

Resolver tracks a daily counter per service. Counter ticks while a session is active (§5). When counter ≥ `max_minutes_per_day`, every subsequent query matching the service's CDN patterns returns `NXDOMAIN`. The DPC also surfaces a parent-app notification: "Kid hit YouTube cap at 6:14pm."

Reset: counter resets at the kid-tz midnight, but the timestamp used is the most recent **signed parent timestamp** from a heartbeat, not the device wall clock. This blocks the obvious "change device time" bypass per [`ATTACKS.md`](ATTACKS.md). If no signed timestamp newer than 24h is available, the resolver keeps the counter and surfaces a tamper event.

---

## 7. Time-window enforcement

Bedtime enforcement is a degenerate case of per-app caps: `max_minutes_per_day` set high, `time_windows` set to `[{"start": "07:00", "end": "21:00"}]`. Outside the window, the service is treated as capped-out and queries return `NXDOMAIN`.

The resolver consults the active bundle on **every** query, not at bundle apply time. This is cheap (the bundle is in memory) and means time-window transitions take effect instantly without re-applying anything.

---

## 8. Sealed-box logging

Every interesting query produces a `ResolverEvent`:

```json
{
  "type": "resolver_event",
  "ts": 1718416800,
  "kind": "block | cdn_hit | session_start | session_end | tamper",
  "qname": "...",
  "service": "youtube",
  "app_hint": "com.google.android.youtube",
  "decision": "nxdomain | answered | cached"
}
```

Events are serialized, then sealed-boxed to the parent's X25519 public key per [`CRYPTO.md`](CRYPTO.md) and [`PROTOCOL.md`](PROTOCOL.md) `SealedEvent`. They join the same store-and-forward queue as all other events; cover-traffic padding is unchanged. The resolver never writes a domain name to disk in cleartext — at-rest events are sealed before they hit storage.

Volume budget: 200–500 events / day for a typical kid. Well under the existing cover-traffic envelope.

---

## 9. Battery model

Component-by-component, measured against a Pixel 7a baseline:

- **Local DoT listener idle.** Bound socket, no work. <0.1% / day.
- **Local DoT listener under load.** ~2000 queries / day for a typical kid (mostly cached). <0.3% / day.
- **Upstream DoT keep-alive.** One long-lived TLS connection to Cloudflare with periodic pings. ~1% / day; if we let it close between idle windows, ~0.5% with extra handshake cost.
- **Classifier + policy check.** Pure CPU, in-memory. Negligible.
- **Sealed-box logging.** AEAD per event, batched. Negligible.
- **Watchdog FGS.** Already on the budget for other defenses; no incremental cost.

**Total estimated: ~1.5–2% daily.** Comparable to a typical messaging app's background cost. Acceptable per the [`SIMPLIFY.md`](SIMPLIFY.md) battery ceiling.

---

## 10. Cache and perf

- Positive cache: LRU, 4096 entries, key `(qname, qtype)`, value `(answer, expires_at)`. TTL honored, capped 3600s.
- Negative cache: LRU, 1024 entries, fixed 60s TTL regardless of SOA minimum.
- Target hit rate: >70% steady-state (repeated CDN chunk lookups dominate).
- Latency target: cached <2ms p99, forwarded <50ms p99 on Wi-Fi, <120ms p99 on LTE.
- DoT connection pool: one persistent connection to upstream, second opened only if first is saturated; drained after 60s idle.

Perf is dominated by upstream RTT; the local resolver itself adds <1ms.

---

## 11. Failure modes

- **Upstream Cloudflare unreachable.** Fall back to Quad9 family (`9.9.9.9` family resolver, DoT to `family.quad9.net`). Logged once per outage.
- **All upstreams unreachable.** Return `SERVFAIL`. Log a `tamper` event (possible jamming, possible captive portal misbehavior). Retry upstream every 30s with backoff.
- **Upstream cert pin mismatch.** Refuse, log `tamper`, fall back to next upstream. Do not silently downgrade to plaintext.
- **Local listener cert rejected by OS.** Should not happen — DPC installs the cert in the system trust store at provisioning time — but if it does, the OS resolves directly against the **public filtering floor** ([`ADR-016`](adr/016-fail-closed-dns-floor.md): Private DNS is pinned to `family.cloudflare-dns.com`/parent-selected filtering host, **not** localhost), so **filtering is preserved** — only visibility/per-app caps are lost for the window. Detected by absence of queries; raises a *visibility*-gap event after 5 minutes of silence. This is NOT an unfiltered window (K3 closed).
- **Resolver process crashes.** Foreground service watchdog (per the FGS budget in [`SIMPLIFY.md`](SIMPLIFY.md)) restarts within 2s. During the gap the OS uses cached entries and then the **public filtering floor** (ADR-016), **not** default network DNS — filtering preserved, visibility lost. The gap is logged as a *visibility* gap, not a filtering gap.
- **DNS exfiltration (kid sends 100k queries to weird TLD).** Rate limit per source-process is not available (we can't see source process from inside the resolver). Instead: per-window query-rate ceiling. Exceeding it raises a tamper event, does not block.

---

## 12. Provisioning integration

Per [`PROVISIONING_V2.md`](PROVISIONING_V2.md), provisioning is the only chance to install root state. The DNS resolver setup happens between DPC install and policy-bundle apply:

1. Generate localhost DoT cert. Store private key in Android Keystore, non-exportable, hardware-backed where available.
2. Install cert into system trust store via the DPC.
3. Start the resolver foreground service. Bind to `127.0.0.1:853`. Verify the listener responds.
4. Set Private DNS to the **public filtering floor** (ADR-016): `dpm.setGlobalPrivateDnsModeSpecifiedHost(admin, "family.cloudflare-dns.com")` (or the parent-selected *filtering* resolver). This is the floor the OS falls back to whenever the local resolver is down. The local DoT listener still intercepts as the *active* resolver while healthy; the pinned public host is the fail-closed fallback, **never** `127.0.0.1`/localhost (whose absence would drop the OS to unfiltered network DNS — the K3 bug). The DPC never sets OFF/OPPORTUNISTIC. Implemented by `DnsFloor` (#19).
5. DPC sets `DISALLOW_CONFIG_PRIVATE_DNS` so the kid cannot change Private DNS in Settings. (`DnsFloor.applyFloor` asserts this alongside the pin, and the watchdog re-asserts both on boot/connectivity/timer.)
6. Smoke test: resolve `example.com` end-to-end. Resolve a known-blocked test domain and confirm `NXDOMAIN`.

Cert rotation: daily, before expiry. Rotation is in-process — old cert valid for one extra hour to cover the handoff.

---

## 13. Domain rule sources

The resolver's policy bundle is composed of:

- **Cloudflare 1.1.1.3 family baseline** — adult + malware. We inherit this by using `1.1.1.3` upstream; the resolver doesn't need its own list for these categories because upstream NXDOMAINs them.
- **Curated category lists.** Maintained by OpenWarden, signed, shipped via OTA per [`OTA.md`](OTA.md). Categories: video CDNs, social media (Facebook, Twitter/X, Snapchat, Reddit), gaming (Roblox, Discord, Steam), streaming (Netflix, HBO, Disney+, Twitch), adult (always blocked, defense in depth even though upstream catches it).
- **Parent allowlist / blocklist.** Per-kid overrides. Allowlist always wins over category blocks.
- **Optional ad-block.** StevenBlack unified hosts list, opt-in. Saved as a compiled radix tree, ~600 KB.
- **Service patterns.** CDN-to-service mapping (§5 table). Updated via OTA, not bundled.

All curated lists are versioned, signed, and updated through the same channel as other policy data. No live network calls to fetch lists at runtime.

---

## 14. Privacy

The resolver sees more of the kid's behavior than any other component. That earns it the strictest data-handling rules in the system.

- **At-rest encryption.** Events are sealed-boxed before they touch storage. The on-disk queue holds ciphertext only. The Keystore master key is non-exportable.
- **Egress.** Sealed boxes go only to the parent. Cloudflare sees upstream traffic but cannot link it back to "OpenWarden" beyond the SNI hostname. No third-party analytics, no telemetry, no crash reporter that captures hostnames.
- **In-memory exposure.** The classifier holds 30 seconds of recent qnames. After that they are dropped. The cache holds qnames + answers for TTL, then evicted.
- **Kid transparency.** Per [`KID_TRANSPARENCY.md`](KID_TRANSPARENCY.md), the kid-facing screen reads: "OpenWarden sees which websites you visit. It doesn't see what you do on them, and it doesn't share them with anyone except your parent."
- **Parent-facing display.** Aggregated by default ("YouTube — 42 minutes today"). Raw qname log available only when parent expands a session detail.

---

## 15. Test plan

- **Unit.** Pattern matcher hits a fixture of 200 real qnames sampled from a test device, classifies each correctly. False-positive rate on a 10k benign-domain corpus <0.5%.
- **Unit.** Cache honors TTL, evicts LRU correctly under load.
- **Unit.** Policy decision is deterministic given a bundle + qname + timestamp.
- **Integration.** Emulator with the resolver service running. Drive YouTube via UI Automator. Expect: session-start event within 10s of playback, session-end event within 35s of pause.
- **Integration.** Bundle with `max_minutes_per_day: 1` for YouTube. Drive 90s of playback. Expect `NXDOMAIN` for `googlevideo.com` within the second minute.
- **Integration.** Bedtime window 22:00–07:00. Set device-signed time to 23:00. Resolve `googlevideo.com`. Expect `NXDOMAIN`. Set to 08:00. Expect normal answer.
- **E2E.** Bench Pixel runs the full stack. Parent app receives session events end-to-end. Battery measured over 24h: <2% attributable to the resolver.
- **Soak.** 7-day run. Memory steady, no FD leaks, no cert-rotation failures.

---

## 16. Library choice summary

- `mini-dns` (Apache 2.0) — wire parsing, cache primitives.
- BouncyCastle (MIT) — DoT TLS listener and upstream client.
- `ktor-server-core` (Apache 2.0) — only if/when DoH is added in v2.
- Android Keystore — cert and key storage; system component, no license issue.

All v1 deps are Apache 2.0 / MIT. F-Droid build is reproducible. No native binary blobs.

---

## 17. Out of scope for v1

- DoH endpoint. Private DNS on Android speaks DoT only, so DoH internally buys nothing for the OS-resolver path. Revisit when Android supports DoH in Private DNS.
- DNSSEC validation locally. We rely on upstream validation. Local validation adds CPU and complexity for marginal benefit when the link to upstream is itself DoT-authenticated.
- TXT / SRV / SVCB beyond pass-through forwarding. We parse them; we don't classify them.
- Per-app DNS routing. Needs VPN mode (Option A). Deferred per [`DNS_FILTER.md`](DNS_FILTER.md) §8.
- Parent-side UI for custom CDN-to-service mapping. v1 ships defaults plus a per-hostname allowlist override; richer curation lives in v2.

---

## 18. References

- mini-dns — https://github.com/MiniDNS/minidns
- Cloudflare 1.1.1.3 for Families — https://developers.cloudflare.com/1.1.1.1/setup/#1111-for-families
- Quad9 family — https://quad9.net/service/service-addresses-and-features/
- Android Private DNS — https://source.android.com/docs/core/ota/modular-system/dns-resolver
- `DevicePolicyManager.setGlobalPrivateDnsModeProviderHostname` — https://developer.android.com/reference/android/app/admin/DevicePolicyManager#setGlobalPrivateDnsModeProviderHostname
- StevenBlack unified hosts — https://github.com/StevenBlack/hosts
- DontKillMyApp (Android background process survival) — https://dontkillmyapp.com/
- BouncyCastle — https://www.bouncycastle.org/
- See also: [`DNS_FILTER.md`](DNS_FILTER.md), [`ATTACKS.md`](ATTACKS.md), [`DEFENSES.md`](DEFENSES.md), [`SIMPLIFY.md`](SIMPLIFY.md), [`PROTOCOL.md`](PROTOCOL.md), [`CRYPTO.md`](CRYPTO.md), [`PROVISIONING_V2.md`](PROVISIONING_V2.md), [`KID_TRANSPARENCY.md`](KID_TRANSPARENCY.md), [`OTA.md`](OTA.md).

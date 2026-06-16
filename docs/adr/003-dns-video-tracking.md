# ADR-003: DNS-based video tracking, no titles

Status: Accepted
Date: 2026-06-15

## Context

User asked: track all videos played on child device (YouTube, TikTok, Instagram, Netflix, etc.). Naive options crossed the stalkerware boundary (Accessibility-service title extraction) or required vendor account integration (YouTube supervised account).

## Options

1. **YouTube supervised Google account.** Titles via Family Link API. Google sees + parent sees. Dependence on Google account; opt-in only.
2. **Accessibility service screen-scrape.** Pulls video titles from on-screen UI. **Stalkerware-class.** REJECT.
3. **MediaSession + UsageStatsManager.** Per-app duration + media session events. No titles. Privacy-safe but limited.
4. **DNS-based session tracking.** OpenWarden runs its own DNS resolver; logs video-CDN domain hits; classifies into per-app sessions. No titles. Reuses existing DNS filter infra.

## Decision

Adopt **option 4: DNS-based video session tracking**.

OpenWarden child runs a local DoT resolver on `127.0.0.1:853`, set as Android Private DNS by the DPC. Resolver forwards to Cloudflare 1.1.1.3 family. Tags + logs queries matching known video-CDN patterns (googlevideo.com, akamaized + TikTok hints, instagram CDN, Netflix/Disney+/HBO CDNs).

Classifier: sliding 30s window per service tag, 5+ queries → active session, 30s silence → session ended. Output: per-session events sealed-box-encrypted to parent pubkey.

Parent dashboard shows: "Oliver watched 2h12m of video today: 1h YouTube, 28m TikTok, 45m Netflix." No titles. No content.

Per-app daily caps enforceable via DNS time-block (block googlevideo.com when cap exceeded; reset midnight signed parent timestamp).

Option 3 (MediaSession) ships as v1 secondary signal for cross-validation. Option 1 (YouTube account) is v3+ optional power feature, never required.

## Consequences

**Good:**
- No Accessibility service required (stays clear of stalkerware boundary).
- No vendor account dependency.
- Reuses DNS filter infrastructure (defense #17).
- Privacy-by-construction: domain seen, content not.
- Parent gets meaningful "Oliver watched X minutes of Y" without invasive data.
- Per-app caps + time windows enforceable at network layer.

**Bad:**
- No concerning-content visibility from this layer (AI screenshot classifier covers that orthogonally, v2).
- Heuristic classifier has false-positive surface (browsing YouTube ≠ watching video).
- DNS resolver = ~1.5-2% daily battery (documented in PERFORMANCE.md).
- DoH-in-browser bypass closed by Chrome enterprise policy + DISALLOW_CONFIG_VPN; if those fail, kid can route around resolver.

## Cross-refs

- [`docs/DNS_RESOLVER.md`](../DNS_RESOLVER.md)
- [`docs/DNS_FILTER.md`](../DNS_FILTER.md)
- [`docs/KID_TRANSPARENCY.md`](../KID_TRANSPARENCY.md) (kid sees "Kidlock sees which CDNs your phone visits")
- [`docs/PERFORMANCE.md`](../PERFORMANCE.md) §1, §2 (battery + bandwidth)
- [`docs/PRIVACY_LEGAL.md`](../PRIVACY_LEGAL.md) (stalkerware boundary)

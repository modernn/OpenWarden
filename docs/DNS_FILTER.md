# DNS Filter

Defense #17, pulled from v2 into v1 in response to [`ATTACKS.md`](ATTACKS.md) findings on the in-app WebView (D1) and in-browser DoH (C2) bypass paths. License: Apache 2.0, OSS, no mandatory SaaS.

---

## 1. Threat being countered

Three concrete attack paths from [`ATTACKS.md`](ATTACKS.md) collapse onto the same defense:

- **C2 — DoH-in-browser bypass.** Chrome, Firefox, and Brave ship their own DNS-over-HTTPS resolvers. Once enabled, those resolvers tunnel name resolution past any system-wide DNS, including a custom OpenWarden resolver. A kid who toggles "Use secure DNS" inside Chrome settings escapes any DNS-based filter we configure at the OS level.
- **D1 — In-app WebView in Discord, Roblox, Snap, YouTube.** These are the daily-driver bypass. Every "allowlisted" social or game app ships an embedded browser that loads arbitrary URLs unfiltered by per-app blocks. The kid clicks a link in Discord and lands on the open web; no per-app restriction sees it.
- **General URL entry.** Kid types `pornhub.com` in Chrome, clicks a link in Roblox chat, taps a Snap story link — all of these resolve a hostname before they can fetch content. If we can lie at resolution time, content never loads.

The defense is to make every hostname resolution on the device go through a filter that returns `NXDOMAIN` for adult, malware, social-engineering, and parent-blocked categories, and to do it in a way the kid cannot bypass without unenrolling the device.

---

## 2. Defense layers

DNS filter is a stack, not a single switch. Each layer covers what the layer above misses.

- **Layer 1 — Device-wide private DNS.** OpenWarden DPC, acting as Device Owner, calls `setGlobalPrivateDnsMode(admin, PRIVATE_DNS_MODE_PROVIDER_HOSTNAME, hostname)`. The hostname points at a filtering resolver. DNS-over-TLS is enforced; user cannot turn it off, cannot edit it, cannot fall back to plaintext. This is the floor.
- **Layer 2 — Chrome enterprise policy.** Chrome's built-in DoH would route around Layer 1 in the browser. We set `DnsOverHttpsMode=off` via Managed Configuration so Chrome respects system DNS. See §5.
- **Layer 3 — Browser allowlist.** Firefox, Brave, and Samsung Internet either ignore Chromium enterprise policies on Android or ship their own DoH that policy can't reach. We blocklist them by default; parent can explicitly allow a specific browser by exception. See §6.
- **Layer 4 — Per-app DNS via VPN tunnel mode (v2).** For apps that ship their own resolver inside a WebView and ignore system DNS, the only complete answer is to route their traffic through an always-on local VPN that intercepts DNS. Deferred to v2 because the implementation cost is high and Layers 1–3 catch the cases we care about today.

---

## 3. DNS provider options

We do not pick one provider. We ship sensible defaults and a clean override path. The four constraints are: Apache-2.0-compatible (provider need not be FOSS, but client integration must be), no mandatory paid SaaS, F-Droid friendly (no proprietary binaries in the APK), and at least one option where the parent self-hosts.

| Option | License | Cost | OPSEC | Setup difficulty | F-Droid friendly | Recommended for |
|---|---|---|---|---|---|---|
| Pi-hole on home Pi | EUPL-1.2 | $0 + Pi hardware | Self-hosted, all queries visible to parent | Medium — DDNS + port forwarding required for off-home use | Yes | Tech-savvy parents |
| Self-hosted AdGuard Home | GPL-3.0 | $0 + Pi hardware | Self-hosted | Easier than Pi-hole; built-in DoT/DoH listener | Yes | Tech-savvy parents |
| NextDNS Family-Safe | Proprietary, free tier (300k queries/mo) | Free tier sufficient for one kid | Queries traverse NextDNS infrastructure | Easy — sign up, paste config URL | OK — NextDNS is a service endpoint, not a bundled binary | Default for non-tech parents who want custom blocklists |
| Cloudflare for Families (1.1.1.3 / 1.1.1.2) | Free public resolver | Free forever | Cloudflare sees queries | Trivial — one hostname | Yes | Quick start, no account |
| CleanBrowsing free tier | Free + paid | Free for family preset | CleanBrowsing sees queries | Trivial | Yes | Alternative quick start |
| Quad9 family.quad9.net | Free public resolver | Free | Quad9 sees queries | Trivial | Yes | Alternative quick start |

**Recommended defaults:**

- **v1 default:** Cloudflare 1.1.1.3 (`family.cloudflare-dns.com`). Zero setup, no account, no quotas, blocks adult and malware categories, free in perpetuity, DoT supported. Best onboarding for the median parent.
- **v1 alternative:** NextDNS free tier for parents who want category-level customization, allow/blocklists, and per-device logs.
- **v2 advanced:** Pi-hole / AdGuard Home setup wizard inside OpenWarden for parents who already self-host.

---

## 4. Implementation

The DPC sets private DNS at provisioning time and reasserts on every policy bundle apply:

```kotlin
dpm.setGlobalPrivateDnsMode(
    adminComponent,
    DevicePolicyManager.PRIVATE_DNS_MODE_PROVIDER_HOSTNAME,
    "family.cloudflare-dns.com"
)
```

Validation tests (run on bench Pixel after each Android release):

- Open Chrome, navigate to a hostname in the Cloudflare family blocklist. Expect `NXDOMAIN` and the browser's "site can't be reached" page. Confirm via `adb shell dumpsys connectivity | grep -i dns` that private DNS mode is `hostname` with the expected provider.
- Launch Discord, open a link from a chat that points to a blocked hostname. The WebView must hit the same resolver and the same `NXDOMAIN`. This is the D1 coverage check.
- Toggle airplane mode and back. Confirm private DNS reapplies on reconnect without parent intervention.

---

## 5. Chrome enterprise policy

As Device Owner, OpenWarden writes a Managed Configuration bundle for `com.android.chrome` via `setApplicationRestrictions(admin, "com.android.chrome", bundle)`. Chrome reads this bundle on launch and applies the policies.

Policies to set:

- `DnsOverHttpsMode=off` — force Chrome to use system DNS, which is our DoT resolver.
- `DnsOverHttpsTemplates=` — empty; no DoH template can be configured.
- `SafeBrowsingEnabled=true` — defense in depth at the URL level.
- `SafeSitesFilterBehavior=1` — block explicit content via Chrome's SafeSites classifier (independent of DNS).
- `URLBlocklist=[...]` — category blocking driven by parent policy bundle.
- `IncognitoModeAvailability=1` — incognito disabled so history is auditable.
- `BrowserSignin=0` — kid cannot sign Chrome into a different Google account.
- `SyncDisabled=true` — no cross-device account leakage.

Reference: https://chromeenterprise.google/policies/

Sample apply call:

```kotlin
val chromePolicies = Bundle().apply {
    putString("DnsOverHttpsMode", "off")
    putString("DnsOverHttpsTemplates", "")
    putBoolean("SafeBrowsingEnabled", true)
    putInt("SafeSitesFilterBehavior", 1)
    putInt("IncognitoModeAvailability", 1)
    putBoolean("SyncDisabled", true)
    putInt("BrowserSignin", 0)
    putStringArray("URLBlocklist", parentBlocklist.toTypedArray())
}
dpm.setApplicationRestrictions(adminComponent, "com.android.chrome", chromePolicies)
```

---

## 6. Browsers that ignore enterprise policy on Android

Not every Chromium-derived browser respects Managed Configuration. Verified gaps:

- **Firefox (Mozilla).** Limited managed-config support on Android; ships its own DoH defaulting to Cloudflare's non-family resolver. Parent policy cannot reliably disable it.
- **Brave.** Ignores Chromium enterprise policies in consumer builds. Ships built-in DoH.
- **Samsung Internet.** Enterprise controls are Knox-only and not available to a standard Device Owner.
- **Vivaldi, Kiwi, Opera.** Untested; assume noncompliant.

Recommendation: OpenWarden ships a default browser-blocklist (Firefox, Brave, Samsung Internet, Vivaldi, Kiwi, Opera) enforced via `setUninstallBlocked` + `setApplicationHidden`. Parent can grant an exception per browser through the OpenWarden app; the exception is logged and surfaces in the parent audit feed. Chrome remains the only browser available by default.

---

## 7. Bypass watch

Predictable kid moves against the DNS filter, each cross-referenced with the restriction that closes it.

- **Install a different browser.** Blocked by `DISALLOW_INSTALL_UNKNOWN_SOURCES_GLOBALLY` plus Play install-approval flow (defense #8).
- **Use a search engine that doesn't depend on DNS.** Not possible. Search results may surface, but content fetch still resolves a hostname.
- **Tor browser.** Blocked at install time; Orbot/Tor requires APK install or Play install, both gated.
- **Configure a VPN to escape DNS.** Blocked by `DISALLOW_CONFIG_VPN` plus pinned `setAlwaysOnVpnPackage(OpenWarden)` in v2.
- **Tether off a friend's phone.** The private DNS setting travels with the device, not the network. DoT to `family.cloudflare-dns.com` works over any uplink. Filter still applies.
- **Public Wi-Fi that intercepts plaintext DNS.** DoT is encrypted and authenticated to the hostname; a captive portal cannot transparently rewrite responses without breaking the TLS handshake. Device shows no connectivity instead of silently failing open. Acceptable.
- **Toggle Chrome's "Use secure DNS" setting.** Greyed out and shows the managed-by-organization badge once Chrome reads the Managed Configuration bundle.
- **Edit `/etc/hosts`.** Requires root. Out of scope for the kid threat model.

---

## 8. Per-app override (v2)

Real case: homework app legitimately needs a host that the strict preset blocks. v2 ships a per-app DNS override. Implementation sketch: a local always-on VPN that routes the target package's traffic through an alternate resolver while leaving all other apps on the device-wide resolver. Complex enough that we defer it; v1 ships a documented workaround (parent temporarily drops to Moderate preset, or adds the host to NextDNS allowlist).

---

## 9. Parent UX

In the OpenWarden parent app, the DNS Filter screen shows:

- Current provider name and filtering level, plain language ("Cloudflare Family — strict").
- Preset toggle: Strict / Moderate / Custom / Off.
- Custom inputs:
  - NextDNS — "your config URL" with paste-and-validate.
  - Pi-hole / AdGuard Home — hostname field plus a "Test connection" button that resolves a known-good host through DoT and confirms a sub-second response.
- Last 24h resolver health summary: uptime, median latency, last failure timestamp.

---

## 10. Setup wizard

Onboarding includes one DNS step: "Pick your filter level."

- **Strict** (default) — Cloudflare family DNS + Chrome SafeSites strict + browser blocklist.
- **Moderate** — Cloudflare family DNS + Chrome SafeSites moderate + browser blocklist.
- **Custom** — Parent enters NextDNS, Pi-hole, or AdGuard Home hostname.
- **Minimum floor** (formerly "Off") — drops the curated blocklists + Chrome SafeSites but **keeps the public filtering resolver**. Per [`ADR-016`](adr/016-fail-closed-dns-floor.md), "no DNS filtering at all" is **not a reachable state** on a managed child device: `DnsFloor` always pins Private DNS to a *filtering* resolver and never sets OFF/OPPORTUNISTIC. "Opt out of Cloudflare" means "pick a different filtering floor," not "turn filtering off."

---

## 11. Logs and visibility

Free public resolvers (Cloudflare, Quad9, CleanBrowsing) do not stream per-device query logs back to the parent in real time. NextDNS does, behind their dashboard. Pi-hole and AdGuard Home expose logs to the self-hoster directly.

Visibility gap for the default Cloudflare path: parent cannot see what the kid tried to resolve. To close this without forcing the parent off the free path, OpenWarden can (v2) ship a tiny localhost DoT resolver inside the DPC that proxies upstream to Cloudflare, logs queries to the encrypted event store, and signs entries into the parent event feed. This is a separate sub-component tracked in [`ROADMAP.md`](ROADMAP.md).

---

## References

- AOSP private DNS — https://source.android.com/docs/core/ota/modular-system/dns-resolver
- `DevicePolicyManager.setGlobalPrivateDnsMode` — https://developer.android.com/reference/android/app/admin/DevicePolicyManager#setGlobalPrivateDnsModeProviderHostname
- Chrome Enterprise policy list — https://chromeenterprise.google/policies/
- Cloudflare for Families 1.1.1.3 — https://developers.cloudflare.com/1.1.1.1/setup/#1111-for-families
- NextDNS API — https://nextdns.io/api
- Pi-hole — https://docs.pi-hole.net/
- AdGuard Home — https://github.com/AdguardTeam/AdGuardHome/wiki

See also: [`ATTACKS.md`](ATTACKS.md) §"What V1 MUST defend against" items 1, [`DEFENSES.md`](DEFENSES.md) for the full v1 defense list.

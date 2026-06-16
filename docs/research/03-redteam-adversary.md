# OpenWarden Red Team — Adversary Attack Catalog

**Adversary profile:** Motivated 9–13 year old child on a Pixel 7 running OpenWarden as Device Owner. Semi-technical: can follow YouTube tutorials, can run `adb` from a Windows or Mac laptop, can search Reddit/XDA/Discord for bypass guides, can ask older friends, has access to a school Chromebook and family computers. Cannot solder, cannot afford JTAG hardware, cannot buy a replacement Pixel. Has hours per day and weeks of motivation. Treats this as a game.

**Target posture recap:** Pixel 7 stock, DO via `dpm set-device-owner`, restrictions: DISALLOW_FACTORY_RESET, DISALLOW_SAFE_BOOT, DISALLOW_DEBUGGING_FEATURES, DISALLOW_INSTALL_UNKNOWN_SOURCES_GLOBALLY, DISALLOW_CONFIG_VPN, DISALLOW_MODIFY_ACCOUNTS, DISALLOW_ADD_USER, DISALLOW_USB_FILE_TRANSFER, DISALLOW_CONFIG_DATE_TIME, DISALLOW_OUTGOING_BEAM. App allowlist via `setPackagesSuspended`. FRP locked to parent Google account. Bootloader locked. Recovery phrase offline. Comms = signed store-and-forward over LAN.

The catalog is exhaustive on purpose. Severity rates *impact if the attack succeeds*, not likelihood. Likelihood is captured separately under "first-attempt list" and "actually-succeeds list".

---

## A. Software-level attacks (Settings UI, ADB, recovery, fastboot)

| # | Attack | Capability | Time | Tools | Procedure | Achieves | Public refs | Severity |
|---|---|---|---|---|---|---|---|---|
| A1 | **Safe Mode boot to disable third-party apps** | basic | 5 min | phone | Hold power, long-press "Power off" → "Reboot to safe mode". In safe mode, third-party DPC apps are stopped; many MDMs uninstall via Settings → Device Admin → revoke → uninstall. | Full bypass if it works; v1 must explicitly set `DISALLOW_SAFE_BOOT` and verify the DPM call returns success on Pixel 7. | [42Gears: Safe Mode bypasses lockdown apps](https://knowledgebase.42gears.com/article/how-to-lock-safe-mode-on-android-devices/); [Scalefusion: restrict safe mode](https://blog.scalefusion.com/restrict-safe-mode-access-android-devices/); [XDA: blocking safe mode + factory reset](https://xdaforums.com/t/blocking-safe-mode-and-factory-reset-on-android.4603707/) | **CRITICAL** — listed as blocked but the DPM call silently no-ops on some OEMs; verify on bench Pixel before trusting. |
| A2 | **Factory reset via Settings** | basic | 5 min | phone | Settings → System → Reset → Erase all data. | Full bypass; wipes OpenWarden. **Blocked by DISALLOW_FACTORY_RESET.** | [Bitdefender Family Link bypass 2025](https://www.bitdefender.com/en-us/blog/hotforsecurity/family-link-bypass-android-2025) | CRITICAL if restriction is silently dropped after OTA. |
| A3 | **Recovery-mode factory wipe** | basic | 10 min | phone | Power off → hold vol-down + power → bootloader → recovery → Wipe data/factory reset. | Wipes device but **FRP** then demands parent's Google account; if attacker doesn't have it, device becomes a brick until parent signs in. | [PixelOS FRP guide](https://blog.pixelos.net/docs/guides/BypassFRP); [DroidWin Pixel FRP](https://droidwin.com/how-to-bypass-frp-on-any-pixel-device/) | HIGH — kid loses access to phone but parent has to spend an evening reprovisioning. Effectively a denial-of-service of OpenWarden service itself. |
| A4 | **Fastboot `-w` wipe** | intermediate | 20 min | phone + laptop + USB-C | Reboot to bootloader; `fastboot -w` or `fastboot erase userdata`. Same FRP wall as A3. | Same as A3. **OEM unlock is locked**; `fastboot flashing unlock` returns FAIL on a locked Pixel. | [XDA Pixel 7 Pro OEM grayed out](https://xdaforums.com/t/just-got-a-new-pixel-7-pro-oem-unlocking-is-greyed-out.4609545/); [fitzsim Pixel bootloader notes](https://www.fitzsim.org/blog/?p=545); [GrapheneOS forum: OEM unlock grayed on Pixel 7](https://discuss.grapheneos.org/d/20348-oem-unlock-grayed-pixel-7) | HIGH — same FRP outcome. |
| A5 | **Enable Developer Options + USB debugging + `dpm clear`** | intermediate | 30 min | phone + laptop + adb | If `DISALLOW_DEBUGGING_FEATURES` is somehow bypassed: 7-tap build number → enable USB debug → `adb shell dpm remove-active-admin com.openwarden/.AdminReceiver`. | Full bypass. **Blocked by DISALLOW_DEBUGGING_FEATURES** — Developer Options panel is locked out by the DPC restriction. | [iditect: dpm remove device owner](https://www.iditect.com/program-example/adb--how-to-remove-setdeviceowner-in-android-dpm.html); [Chronolink](https://github.com/rifting/chronolink) (ADB tool that runs once dev options is on) | CRITICAL if the restriction is missing or applied after `BOOT_COMPLETED` instead of from boot. |
| A6 | **Chronolink (ADB time-feature removal)** | intermediate | 30 min | phone + laptop | Plug in USB, run Chronolink binary, it nukes Family-Link time features via `dpm` calls. | Removes time enforcement; doesn't help against OpenWarden's `setPackagesSuspended` but a OpenWarden-targeted fork could. | [github.com/rifting/chronolink](https://github.com/rifting/chronolink); [Bitdefender writeup](https://www.bitdefender.com/en-us/blog/hotforsecurity/family-link-bypass-android-2025) | HIGH — proves the existence of single-purpose ADB demolition tools; an enterprising kid forks it. **Blocked at root by DISALLOW_DEBUGGING_FEATURES.** |
| A7 | **Boot-window race before DO applies** | intermediate | varies | phone | First couple hundred ms after boot, before OpenWarden's FGS rebinds, some Settings pages may be open. Mash buttons to enter Developer Options or VPN config. | Brief window; usually <2s on a Pixel 7. Realistic only if OpenWarden applies restrictions on `BOOT_COMPLETED` instead of via the persistent DO config. | XDA threads on early-boot races; [Headwind MDM source](https://github.com/h-mdm/hmdm-android) | MEDIUM — design implication: restrictions must come from the persisted DO policy file, not be re-applied at boot in app code. |
| A8 | **Hold power for emergency dialer escape hatch** | basic | 2 min | phone | Long-press power → Emergency → use the dialer numpad's "back" arrows / accessibility shortcuts to escape into Settings. Historic Android trick. | Brief window onto a normally-locked screen. Pixel 7 stock has hardened this since Android 12. | [Tom's Guide: 7-year-old hacked Screen Time](https://www.tomsguide.com/us/ios-screen-time-hack-kid,news-28177.html) — analogous lock-screen escape on iOS | LOW on current Pixel; LOW–MEDIUM if OpenWarden adds a custom lock screen. |
| A9 | **Power off + remove battery (not possible on Pixel 7)** | n/a | n/a | n/a | Pixel 7 has a sealed battery; battery pull is hardware-locked out. | none | n/a | n/a — listed for completeness. |
| A10 | **`adb sideload` from recovery** | intermediate | 20 min | phone + laptop | Boot recovery → Apply update from ADB. Requires `adb sideload` package signed with Google's recovery key. Stock recovery rejects unsigned. | None unless attacker has a leaked signing key. | [GrapheneOS docs] | LOW — practical impossibility for the kid. |

---

## B. Hardware attacks (bootloader, JTAG, SIM, SD)

| # | Attack | Capability | Time | Tools | Procedure | Achieves | Public refs | Severity |
|---|---|---|---|---|---|---|---|---|
| B1 | **OEM bootloader unlock** | intermediate | 30 min | phone + laptop | Settings → Dev Opts → OEM unlock → fastboot flashing unlock. | Full bypass: flash custom recovery, wipe userdata, reflash stock. | [fitzsim Pixel bootloader log](https://www.fitzsim.org/blog/?p=545); [Tenorshare OEM grayed](https://www.tenorshare.com/unlock-android/oem-unlocking-greyed-out.html) | **Blocked**: OEM unlock toggle requires Dev Opts (blocked by DISALLOW_DEBUGGING_FEATURES) and a network/account check. OpenWarden should also call `setUserRestriction(DISALLOW_OEM_UNLOCK)` explicitly. CRITICAL if forgotten. |
| B2 | **EDL / Qualcomm emergency download** | advanced | hours | phone + laptop + EDL cable | Force EDL mode via test-point shorting. Pixel 7 is Tensor (not Qualcomm) → EDL doesn't apply. Some Tensor exploits exist in research labs but no public tooling. | Full bypass if it ever works. | [Tensor security research articles] | LOW — out of scope for an unsoldered kid. |
| B3 | **JTAG / chip-off** | advanced | days | $1000+ hardware | Read raw NAND, extract data, but DO state is in Trusted Element on Tensor; doesn't help. | partial | n/a | LOW — kid can't afford and can't solder. |
| B4 | **SIM swap into another phone** | basic | 10 min | another phone | Pop the SIM, put in friend's phone, use friend's phone for the day. | Bypasses OpenWarden entirely (different device). OpenWarden can't control a phone it isn't installed on. | [Bark vs Pinwheel article re: removable SIM](https://www.bark.us/learn/bark-phone-vs-pinwheel-phones/) | HIGH but **out of scope** — explicitly conceded in research doc §3 (the "borrowed friend's phone" line). |
| B5 | **eSIM provisioning on a second phone** | intermediate | 30 min | another phone + carrier portal | Log into carrier portal from school computer, transfer eSIM to friend's phone. | Same as B4. | Carrier portal docs | HIGH but out of scope. |
| B6 | **microSD insertion** | basic | 2 min | phone + SD card | Pixel 7 has **no microSD slot**. Not possible. | none | n/a | n/a. |
| B7 | **USB-OTG keyboard to spam reset combos** | basic | 10 min | USB-C OTG + keyboard | Plug keyboard, attempt key combos to unlock Settings or trigger menu. | DO restrictions still apply; keyboard input is still input. | XDA OTG threads | LOW. |
| B8 | **USB-C MITM with fake charger** | intermediate | 1 hr | malicious charger | Some chargers send AT commands; Pixel 7 hardened against this. | none meaningful | research papers on JuiceJacking | LOW. |

---

## C. Network attacks (VPN, DoH, Tor, hotspot)

| # | Attack | Capability | Time | Tools | Procedure | Achieves | Public refs | Severity |
|---|---|---|---|---|---|---|---|---|
| C1 | **Sideload a VPN app** | basic | 10 min | phone | Open Chrome, download a free VPN APK, install. | **Blocked by DISALLOW_INSTALL_UNKNOWN_SOURCES_GLOBALLY + DISALLOW_CONFIG_VPN.** | [Bitdefender 2025 bypass](https://www.bitdefender.com/en-us/blog/hotforsecurity/family-link-bypass-android-2025) | Would be CRITICAL if either restriction is missing. |
| C2 | **DNS-over-HTTPS in Chrome / Firefox / Brave** | basic | 5 min | phone | Chrome → Settings → Privacy → Use secure DNS → custom (Cloudflare 1.1.1.1). DoH inside browser ignores any device-wide DNS filter set via private DNS. | Partial bypass — defeats DNS filter; does not unblock app suspension. | [Cloudwards: Screen Time DoH bypass](https://www.cloudwards.net/how-to-hack-screen-time/); Mozilla docs | HIGH for v2 DNS filter; v1 has no DNS filter so MEDIUM. Defense: disable per-browser DoH via `Chrome` enterprise policy `DnsOverHttpsMode=off`, or block all browsers except a managed one. |
| C3 | **Tor browser / Onion Browser** | basic | 10 min | phone | If allowlist includes a generic browser, install Tor APK. **Install blocked**; but Tor over Orbot via the Play Store if Play is allowlisted is sneakier. | Bypasses URL filtering. | [Guardian Project Orbot](https://guardianproject.info/apps/) | HIGH if Play Store install is unrestricted; install-approval flow (v2) fixes. |
| C4 | **Always-On VPN slot hijack** | intermediate | 30 min | phone | If parent didn't pin Always-On VPN to OpenWarden's own loopback, kid can configure another VPN. Settings panel is normally locked but a known CVE-2022-20544-style profile-owner check miss could allow it. | Full traffic redirect. | [CVE-2022-20544](https://api.osv.dev/v1/vulns/CVE-2022-20544) | HIGH — design implication: pin the VPN slot at provisioning time using `setAlwaysOnVpnPackage`. |
| C5 | **Friend's phone Wi-Fi hotspot to escape parent LAN** | basic | 5 min | another phone | Connect OpenWarden-managed Pixel to friend's hotspot. OpenWarden's parent-LAN sync now can't reach the parent. Policy continues to enforce locally (good design) but the parent can't see live status. | Visibility loss only; control is still enforced. | n/a — basic observation | MEDIUM — design implication: policy MUST be locally enforced; never depend on parent-online. Already in plan §4. |
| C6 | **Block parent LAN sync to prevent restrictive updates** | basic | 5 min | phone | Disable Wi-Fi, use cellular only, or block parent's MAC at the router. New restrictive policies never arrive. | Freezes policy at last-known-good (from kid's perspective). | n/a | MEDIUM — design implication: policies should expire if not refreshed within N days, fail-closed to a "strict" baseline. |
| C7 | **mDNS spoofing on LAN** | intermediate | 1 hr | laptop on same LAN | Run `avahi-publish` or `dns-sd` from a laptop to advertise a fake `_openwarden._tcp` service. OpenWarden child app might trust it. | Could push a forged "policy" if signatures aren't checked. **Blocked by Ed25519 verify.** | mDNS abuse papers (e.g., Black Hat 2017 mDNS) | CRITICAL if signature verification is wrong — would let the kid push their own "open everything" policy. Design: signature is mandatory. |
| C8 | **MITM / replay old signed policy bundle** | intermediate | 1 hr | laptop on LAN | Capture an old, more-permissive policy bundle from a past school-day window, replay it on a Saturday. | Partial bypass if no monotonic counter / freshness check. | [Signal replay-protection docs](https://signal.org/docs/specifications/sesame/) | CRITICAL without monotonic counter. **Design must enforce: signed bundles include `policy_seq` + `not_before`/`not_after`, child rejects regressions.** |
| C9 | **ARP poisoning to intercept parent push** | intermediate | 1 hr | laptop + ettercap | Same as C7 but at L2. | Same as C7/C8. | Standard MITM lit | HIGH; signature defense catches it. |

---

## D. App-layer attacks (allowlisted apps as escape hatches)

| # | Attack | Capability | Time | Tools | Procedure | Achieves | Public refs | Severity |
|---|---|---|---|---|---|---|---|---|
| D1 | **In-app browser inside Discord / Roblox / YouTube** | basic | 5 min | phone | Open Discord, click any external link → opens an embedded Chromium WebView with full web browsing. Same for Roblox social links, YouTube descriptions, Snapchat. | Full unfiltered web access including porn, social, gaming, all without ever opening Chrome. | [Microsoft Q&A: Edge WebView bypasses parental controls](https://learn.microsoft.com/en-us/answers/questions/2403235/microsoft-edge-webview-(internal-browser-for-progr); [Roblox→YouTube WebView bypass](https://learn.microsoft.com/en-us/answers/questions/2403235/microsoft-edge-webview-(internal-browser-for-progr); openwarden-research §3 ("embedded browser games inside whitelisted apps") | **CRITICAL** — by far the most common practical bypass. Design: blocklist Discord; force Chrome with policy filter; accept Roblox/YouTube leak or wrap them too. |
| D2 | **File picker → expose other apps** | basic | 5 min | phone | Many file pickers (Storage Access Framework) show *all* installed apps as document providers. Kid sees their hidden games' data. | Information leak; can sometimes launch hidden apps. | AOSP Storage Access Framework reports | MEDIUM. |
| D3 | **APK extractor from an allowlisted app** | intermediate | 30 min | phone | Install "APK Extractor" from Play — wait, install requires approval. If on allowlist, extract any APK and share. | Possible exfil; can't re-install due to unknown-sources block. | XDA threads | LOW (install path closed). |
| D4 | **Account linking inside game** | basic | 10 min | phone | Roblox/Discord/Snap let you "sign in with Google" which loads a Google web flow. Sometimes that flow can be used to add an account → bypasses MODIFY_ACCOUNTS. | Partial — adds an account that might unlock Play. | Family Link bypass reports | HIGH — `DISALLOW_MODIFY_ACCOUNTS` is the gate. |
| D5 | **YouTube cast / Google Cast to TV** | basic | 2 min | phone + Chromecast | Kid casts YouTube to bedroom TV. OpenWarden can't enforce time limits on the TV. | Full bypass for YouTube. | Pinwheel notes | MEDIUM. |
| D6 | **Pokemon Go GPS spoof via allowlisted app** | basic | 10 min | phone | Some games let you change location via in-app menus. Not relevant to OpenWarden control plane but breaks geofencing if OpenWarden adds it. | n/a v1 | XDA Pokemon Go cheats | LOW v1. |
| D7 | **Google Drive / Photos as a side channel for blocked content** | basic | 5 min | phone | Kid stores game APKs in Drive; "downloads" them — Android still requires unknown-sources install permission. | Blocked. | n/a | LOW. |
| D8 | **Sideload via desktop Play web** | basic | 10 min | school computer | "Install" Roblox from play.google.com on school PC; Play pushes to OpenWarden phone bypassing the suspend list. | Partial — Play Store install isn't blocked by `setPackagesSuspended` if the parent allowlist's Play. OpenWarden must intercept install via `PackageInstaller` callback and block unapproved installs. | [Family Link bypass via web Play Store](https://www.bitdefender.com/en-us/blog/hotforsecurity/family-link-bypass-android-2025) | **HIGH — common.** Design: install-approval flow is mandatory for v1, not v2 like the plan suggests. |
| D9 | **PWA / "add to home screen" from Chrome** | basic | 2 min | phone | Open YouTube in Chrome, hit menu → install app. The PWA is a separate launcher icon that OpenWarden's `setPackagesSuspended` doesn't see (it's a Chrome shortcut). | Partial — content is YouTube, not a separate package. | PWA security research | MEDIUM. |
| D10 | **Alternative app store** | basic | 10 min | phone | Install F-Droid, Aurora, APKPure. **Blocked by DISALLOW_INSTALL_UNKNOWN_SOURCES_GLOBALLY.** | none | F-Droid docs | LOW (blocked). |

---

## E. Account-layer attacks

| # | Attack | Capability | Time | Tools | Procedure | Achieves | Public refs | Severity |
|---|---|---|---|---|---|---|---|---|
| E1 | **Add a secondary Google account** | basic | 5 min | phone | Settings → Accounts → Add. **Blocked by DISALLOW_MODIFY_ACCOUNTS.** | none | [Bitdefender 2025](https://www.bitdefender.com/en-us/blog/hotforsecurity/family-link-bypass-android-2025) | LOW (blocked). |
| E2 | **Create secondary device user** | basic | 5 min | phone | Quick Settings → user switcher. **Blocked by DISALLOW_ADD_USER.** | none | Bitdefender | LOW (blocked). |
| E3 | **Guest user mode** | basic | 5 min | phone | Some Android builds expose Guest user from lock screen. DISALLOW_ADD_USER doesn't always cover guest — needs `DISALLOW_USER_SWITCH` too. | Guest mode has no OpenWarden; full bypass during session. | AOSP user docs | **HIGH** — design implication: also set `DISALLOW_USER_SWITCH` + verify Pixel 7 lock screen doesn't expose Guest. |
| E4 | **Recover parent Google account via "forgot password"** | basic | hours | phone + parent SMS access | Initiate password recovery on parent account from kid's phone; if kid grabs parent's phone for the SMS code, take over the Google account → FRP, Family Link, parent app credentials all fall. | Catastrophic. | Krebs and similar account-takeover writeups | CRITICAL but socially-limited. |
| E5 | **Google account on another device → unmanaged copy of allowlist app data** | basic | 10 min | school computer | Sign in to Roblox / YouTube on school Chromebook → kid has unrestricted experience there. | Out of scope but the *parent should know* this is a leak channel. | n/a | OUT OF SCOPE. |

---

## F. Time / clock attacks

| # | Attack | Capability | Time | Tools | Procedure | Achieves | Public refs | Severity |
|---|---|---|---|---|---|---|---|---|
| F1 | **Manual clock rollback** | basic | 2 min | phone | Settings → Date & Time → off auto → roll back 12 hours. **Blocked by DISALLOW_CONFIG_DATE_TIME.** | none | [Apple Developer forum: date bypass](https://developer.apple.com/forums/thread/809318); [Jellies clock bypass](https://jelliesapp.com/blog/kids-bypassing-screen-time/); [Phonearena Screen Time defeat](https://www.phonearena.com/news/Kids-defeat-Screen-Time-app-limits-using-workarounds_id109290) | LOW if restriction holds. |
| F2 | **Timezone trick (set to Hawaii to roll past midnight)** | basic | 2 min | phone | Same Settings panel. **Same blocker.** | none | [Jellies timezone trick](https://jelliesapp.com/blog/kids-bypassing-screen-time/) | LOW if blocked. **Design check:** OpenWarden must enforce policy in UTC + locked timezone, not the displayed local time. |
| F3 | **NTP spoofing** | intermediate | 1 hr | laptop on LAN | Run a fake NTP server, force Pixel to use it via DHCP option 42. Pixel uses Google's NTP servers; harder to redirect without rooted gateway. | Could roll clock forward/back. **Design defense:** OpenWarden uses monotonic clock (`SystemClock.elapsedRealtime`) + signed parent timestamps for windows, not wall clock. | NTP-spoof papers; [Cloudwards](https://www.cloudwards.net/how-to-hack-screen-time/) | HIGH if OpenWarden trusts wall clock. |
| F4 | **Force-stop OpenWarden during a time window, hope it forgets** | basic | 2 min | phone | Settings → Apps → OpenWarden → Force stop. **Blocked**: DO apps cannot be force-stopped; button is grayed. Verify. | none | AOSP DO docs | LOW if DO is actually applied. |

---

## G. Storage / state attacks

| # | Attack | Capability | Time | Tools | Procedure | Achieves | Public refs | Severity |
|---|---|---|---|---|---|---|---|---|
| G1 | **Clear OpenWarden app data / cache** | basic | 2 min | phone | Settings → Apps → OpenWarden → Storage → Clear data. **Blocked** for DO apps. | none | AOSP | LOW. |
| G2 | **Fill storage to crash OpenWarden FGS** | basic | 30 min | phone | Download huge files to fill /data until OpenWarden's signed-bundle file can't be written, hope it fails-open. | Partial — depends on OpenWarden's fail-closed posture. | n/a | HIGH if OpenWarden fails open. **Design: any I/O failure on policy load → strict baseline, not "no restrictions".** |
| G3 | **Delete OpenWarden's policy file via file manager** | basic | 5 min | phone | Allowlisted file manager → /data/data/com.openwarden — Android sandbox blocks. | none | AOSP sandbox | LOW. |
| G4 | **Backup-and-restore via Google One / Seedvault** | intermediate | hours | phone + cloud | Restore a pre-OpenWarden backup → DO is gone, policy gone. Blocked unless attacker can factory-reset first (A2/A3 blocked by FRP). | Catastrophic if backup restore is allowed without FRP. | [Seedvault docs](https://github.com/seedvault-app/seedvault); FRP guides | HIGH if FRP ever fails. Design: rely on FRP. |
| G5 | **OTA-rollback / sideload older Android image** | advanced | hours | phone + laptop | Sideload an older Android factory image with a known DPC bug. Requires unlocked bootloader (blocked). | n/a | XDA factory image threads | LOW given bootloader lock. |

---

## H. Crypto / protocol attacks

| # | Attack | Capability | Time | Tools | Procedure | Achieves | Public refs | Severity |
|---|---|---|---|---|---|---|---|---|
| H1 | **Replay an old permissive signed bundle** | intermediate | 1 hr | LAN tap | Capture and replay (covered in C8). | full bypass of *time windows* until next legitimate refresh. | replay-protection literature | CRITICAL without `policy_seq` + freshness; mandatory v1 fix. |
| H2 | **Signature stripping / downgrade** | intermediate | 1 hr | laptop | If OpenWarden falls back to unsigned policy when signature parse fails, deliver a malformed bundle to force fallback. | Full bypass. | TLS downgrade literature | CRITICAL. Design: **fail-closed on any verify error.** |
| H3 | **Substitute parent pubkey** | intermediate | hours | LAN + phone | If pubkey is mutable (e.g., kept in EncryptedSharedPreferences without anti-rollback), kid replaces it with own key. | Full bypass. | Trust-on-first-use abuse | CRITICAL. Design: pin pubkey in DO setup, refuse to update without recovery phrase. |
| H4 | **Extract parent private key from parent device** | advanced | hours | parent's phone | Borrow parent's phone, exfil keystore via accessibility or screen-record. KMP `flutter_secure_storage` / Android Keystore is hardware-backed → exfil hard. | catastrophic if successful. | Android Keystore exfil research | LOW–MEDIUM (hardware-backed key + parent's lock screen guard it). |
| H5 | **Brute force Ed25519** | n/a | impossible | n/a | n/a | none | n/a | n/a. |
| H6 | **Use recovery phrase if kid can read it** | basic | 5 min | parent's printed sheet | Kid finds laminated recovery phrase in drawer, photos it. | **Full unlock.** Recovery phrase is root authority. | n/a — by design | CRITICAL — **the recovery phrase is a physical-security item, not a software defense.** Document this prominently for parents. |

---

## I. Provisioning attacks

| # | Attack | Capability | Time | Tools | Procedure | Achieves | Public refs | Severity |
|---|---|---|---|---|---|---|---|---|
| I1 | **Re-run `dpm set-device-owner` to demote** | intermediate | 30 min | phone + laptop | Only works if `device_provisioned=0`, which requires a fresh factory reset (blocked by FRP). | none | [XDA: DO without factory reset](https://xdaforums.com/t/how-i-got-device-owner-without-factory-reset.4745834/); [Bayton FAQ](https://bayton.org/android/android-enterprise-faq/can-i-set-device-owner-without-factory-reset/) | LOW given FRP. |
| I2 | **`dpm remove-active-admin`** | intermediate | 10 min | phone + laptop | Requires ADB which requires Dev Opts (blocked). | none | [iditect dpm guide](https://www.iditect.com/program-example/adb--how-to-remove-setdeviceowner-in-android-dpm.html) | LOW (blocked). |
| I3 | **Hijack OOBE window during reprovision** | intermediate | 30 min | phone + laptop | When parent reprovisions after an OTA or recovery, if there's a window where DO is not yet enforced, kid grabs phone and adds an account. | Brief bypass. | Bayton notes; openwarden-research §2 | MEDIUM — design implication: provisioning script must complete atomically; never hand back unprovisioned phone. |

---

## J. Communications attacks

| # | Attack | Capability | Time | Tools | Procedure | Achieves | Public refs | Severity |
|---|---|---|---|---|---|---|---|---|
| J1 | **Jam LAN sync (mDNS flood, ARP storm)** | intermediate | 1 hr | laptop | Saturate LAN to prevent parent's policy updates and event uploads. | Visibility loss; control still enforced locally. | LAN-flood research | MEDIUM. Design: store-and-forward log catches up later; parent should get a "child silent for N hours" alert. |
| J2 | **BLE pairing impersonation** | advanced | hours | laptop + BLE dongle | If OpenWarden adds BLE pairing in v3, impersonate parent's BLE advertisement. | Could pair as fake parent. | BLE pairing research (Mike Ryan crackle) | HIGH for v3. v1 is mDNS+REST so n/a. |
| J3 | **Wi-Fi Direct file transfer between phones** | basic | 10 min | another phone | Send blocked APKs phone-to-phone via Wi-Fi Direct or Nearby Share. **Install blocked by unknown-sources.** | none | AOSP Nearby Share docs | LOW. |
| J4 | **NFC tap to provision a different DPC** | intermediate | 30 min | another NFC phone | NFC provisioning of a DPC requires `device_provisioned=0`. | LOW. |  | LOW. |

---

## K. Social engineering

| # | Attack | Capability | Time | Tools | Procedure | Achieves | Public refs | Severity |
|---|---|---|---|---|---|---|---|---|
| K1 | **"Dad, the homework app needs Chrome to be unrestricted"** | basic | 5 min | mouth | Convince parent to push permissive policy under false pretense. | Full or partial bypass via parent action. | Parenting-blog reports | **CRITICAL — most successful long-term vector.** Design: parent app should show "Last unlock: 2h ago by you; expires in 1h" — friction + audit. |
| K2 | **"Mom, OpenWarden broke, please factory-reset"** | basic | 10 min | mouth | Convince parent the phone is broken; parent inputs recovery phrase to reset. | Full bypass. | n/a | CRITICAL. Design: any recovery-phrase use logs to parent's audit feed and requires a 24-hour delay for non-emergency. |
| K3 | **Shoulder-surf the recovery phrase** | basic | min | eyes | Watch parent type recovery phrase. | Full bypass. | n/a | CRITICAL — physical OPSEC concern. |
| K4 | **Bribe a sibling who knows the unlock** | basic | candy | candy | n/a | n/a | n/a | n/a. |

---

## L. Side channels

| # | Attack | Capability | Time | Tools | Procedure | Achieves | Public refs | Severity |
|---|---|---|---|---|---|---|---|---|
| L1 | **Browser Picture-in-Picture for video** | basic | 2 min | phone | YouTube PiP keeps playing even when "blocked". | Partial — PiP overlays user surface despite suspension. | Google Issue Tracker | MEDIUM. |
| L2 | **Bluetooth tether to friend's laptop** | basic | 5 min | phone + friend laptop | Use phone as keyboard for laptop, or vice versa. | Tangential — kid uses laptop, not phone. | n/a | OUT OF SCOPE. |
| L3 | **Google Cast → bedroom TV → unmanaged YouTube** | basic | 5 min | phone + Chromecast | (D5 duplicate, listed for completeness as side channel.) | partial | n/a | MEDIUM. |
| L4 | **Screen mirror to bedroom monitor** | basic | 10 min | HDMI dongle | USB-C → HDMI; not blocked. Phone shows whatever it shows — but allowlist still applies on phone. | Cosmetic. | n/a | LOW. |

---

## M. Out-of-band

| # | Attack | Capability | Time | Tools | Procedure | Achieves | Public refs | Severity |
|---|---|---|---|---|---|---|---|---|
| M1 | **Use school Chromebook for blocked content** | basic | 5 min | school PC | Out-of-scope but parent should know. | full bypass off-device | n/a | OUT OF SCOPE — accept. |
| M2 | **Library / friend's gaming console** | basic | 1 hr | n/a | Same. | full | n/a | OUT OF SCOPE. |
| M3 | **Burner Android phone gifted by older friend** | basic | 1 day | another phone | Cited in openwarden-research as accepted risk. | full | openwarden-research §3 | OUT OF SCOPE. |

---

## N. Long-term and CVE-driven

| # | Attack | Capability | Time | Tools | Procedure | Achieves | Public refs | Severity |
|---|---|---|---|---|---|---|---|---|
| N1 | **Wait for an OTA that breaks OpenWarden's FGS** | basic | months | patience | Pixel monthly OTA changes FGS rules. OpenWarden might fail to rebind. | Brief window post-OTA. | [Android 14 FGS restrictions](https://developer.android.com/develop/background-work/services/fgs/restrictions-bg-start); openwarden-research §2 | HIGH — design implication: aggressive watchdog + parent-side alert when child device is silent post-OTA. |
| N2 | **Exploit known DPC bypass CVE** | advanced | days | research | CVE-2022-20544 (profile owner restriction bypass), or future analogs. | Targeted bypass of specific restrictions. | [CVE-2022-20544](https://api.osv.dev/v1/vulns/CVE-2022-20544); [Harvard tagteam Android remote-access vuln feed](https://tagteam.harvard.edu/hub_feeds/3415/feed_items/2121771) | MEDIUM — assume one will exist; design defense in depth. |
| N3 | **Kernel exploit (rowhammer-class, GPU drivers)** | advanced | weeks | n/a | Annual rate. | Full root → full bypass. | Google Project Zero blog | LOW for v1 timeline; accept. |
| N4 | **Tensor-specific bootloader bug** | advanced | research | n/a | Some published. | Full bypass if exploited. | Tensor security blog posts | LOW for kid. |

---

## O. App-specific escape hatches (deep-dive)

| # | App | Escape vector | Severity |
|---|---|---|---|
| O1 | **YouTube** | In-video links open WebView; YouTube Search → click link → full Chrome session; cast to TV; PiP. | HIGH. |
| O2 | **Roblox** | Embedded WebView to YouTube (bypasses YouTube restriction); social chat is full text channel; in-game browsers in user-generated experiences are unfiltered. | CRITICAL. |
| O3 | **Discord** | In-app browser for any link → full unfiltered web; voice chat unmonitored; file sharing for arbitrary content. | CRITICAL. |
| O4 | **Snapchat** | In-app browser; Snap Map social; AR lenses with web payload. | HIGH. |
| O5 | **Pokemon Go** | GPS spoof in-app; Niantic Social embedded chat. | LOW for control bypass. |
| O6 | **Life360** | Geofence reporting can be jammed by airplane mode. | OUT OF SCOPE. |
| O7 | **Google Docs / Classroom** | Comment threads used as covert chat channels (cited in [aol.com 7-year-old article](https://www.aol.com/news/kids-young-7-finding-ingenious-101635503.html)). | MEDIUM. |
| O8 | **Spotify** | Podcast descriptions contain links → WebView. | LOW. |
| O9 | **Chrome** | DoH bypass; PWAs; cast; download manager (blocked by unknown-sources). | HIGH. |

---

## Top-priority attacker playbook

### The 3–5 attacks a motivated kid tries FIRST (in order)

1. **Safe Mode reboot (A1).** Free, no tools, top YouTube result. If OpenWarden's DISALLOW_SAFE_BOOT silently failed to apply, this wins instantly.
2. **In-app WebView in Discord / Roblox / YouTube (D1, O2, O3).** Zero technical skill; absolute first thing a kid stumbles into. Probably already discovered accidentally.
3. **Factory reset via Settings (A2).** Blocked, but kid will try once "just in case".
4. **Clock rollback (F1, F2).** Blocked, but kid tries it because every Screen Time bypass guide leads with it.
5. **Hand parent phone with a fake bug claim (K1, K2).** Social engineering scales infinitely; eventually works.

### The 1–2 attacks that ACTUALLY succeed on a v1 OpenWarden

1. **In-app browser inside an allowlisted social/game app (D1).** **This is the daily-driver bypass.** OpenWarden v1 plan does not block Discord/Roblox WebViews. Either blocklist these apps or implement a network-level filter that catches the WebView's traffic (DNS filter, planned for v2). Recommended v1 mitigation: explicit blocklist guidance — "if you allow Discord, you allow the whole web."
2. **Social engineering the parent (K1).** Over a six-month horizon, the kid will get an exception granted under false pretense and not have it revoked. OpenWarden should default time-limited exceptions: "unblock Chrome for 1 hour" expires automatically.

A close third is **Google Cast to a TV (D5/L3)** — content escape with zero OpenWarden tooling. Accepted as out-of-scope but worth a parent note.

---

## "Would have been bypasses but blocked by..." — restriction audit

| Hypothetical bypass | Blocked by |
|---|---|
| Settings → Reset → Erase all data | `DISALLOW_FACTORY_RESET` |
| Safe mode → disable DPC → uninstall | `DISALLOW_SAFE_BOOT` |
| Dev Options → USB debug → `adb shell dpm remove-active-admin` (Chronolink) | `DISALLOW_DEBUGGING_FEATURES` |
| Sideload free VPN APK | `DISALLOW_INSTALL_UNKNOWN_SOURCES_GLOBALLY` |
| Configure custom VPN to escape DNS filter | `DISALLOW_CONFIG_VPN` |
| Add secondary Google account → Family Link bypass | `DISALLOW_MODIFY_ACCOUNTS` |
| Create second user profile (no OpenWarden there) | `DISALLOW_ADD_USER` |
| Pull APKs / push tools via MTP | `DISALLOW_USB_FILE_TRANSFER` |
| Clock rollback / timezone trick | `DISALLOW_CONFIG_DATE_TIME` |
| NFC-bump a new DPC config from a friend's phone | `DISALLOW_OUTGOING_BEAM` |
| `fastboot flashing unlock` | Bootloader locked + (recommended add) `DISALLOW_OEM_UNLOCK` |
| Fastboot wipe → fresh setup with new account | FRP locked to parent's Google account |

**Restrictions NOT in the current list that this exercise says you should add:**

- `DISALLOW_OEM_UNLOCK` — defense-in-depth for B1 even though bootloader is already locked.
- `DISALLOW_USER_SWITCH` — closes the Guest user E3 hole.
- `DISALLOW_REMOVE_USER` — already noted in openwarden-research §2 but not on the active list above.
- `DISALLOW_AIRPLANE_MODE` — prevents J1-style isolation from parent sync (only set if parent wants visibility prioritized; harms genuine "I'm on a plane" UX).
- `DISALLOW_MOUNT_PHYSICAL_MEDIA` — defense for B6 hypothetical SD shenanigans.
- `DISALLOW_CONFIG_TETHERING` — prevents kid from sharing their cellular as a hotspot (lateral move).
- Force-set `setGlobalPrivateDnsMode(...)` to OpenWarden-controlled resolver to defeat C2 DoH inside browsers (defense for v1, not v2 — pull this forward).

**Defensive design changes implied by the catalog (rank-ordered):**

1. **Implement install-approval flow in v1, not v2.** D8 is too easy via web Play Store.
2. **Blocklist Discord/Roblox/Snap WebViews or wrap them.** D1 is the daily-driver bypass.
3. **Signed bundle must include `policy_seq` + `not_before`/`not_after`** with strict monotonic enforcement. Defeats C8/H1.
4. **Fail-closed everywhere.** Signature parse error, missing policy file, clock anomaly → strict baseline, never "unrestricted". Defeats G2, H2, F3.
5. **Pin parent pubkey at DO provisioning; rotation requires recovery phrase.** Defeats H3.
6. **Use monotonic clock + signed parent timestamps for window evaluation.** Defeats F3 even if DISALLOW_CONFIG_DATE_TIME is bypassed by future CVE.
7. **Recovery-phrase use is logged + delayed.** Defeats K2.
8. **Watchdog alert on "child silent N hours".** Detects J1.
9. **Post-OTA self-test + parent alert on FGS rebind failure.** Detects N1.

---

## References

- [Bitdefender: How Kids Bypass Family Link 2025](https://www.bitdefender.com/en-us/blog/hotforsecurity/family-link-bypass-android-2025)
- [Chronolink ADB Family Link bypass](https://github.com/rifting/chronolink)
- [Cloudwards: How to Hack Screen Time](https://www.cloudwards.net/how-to-hack-screen-time/)
- [Tom's Guide: 7-year-old hacks iOS Screen Time](https://www.tomsguide.com/us/ios-screen-time-hack-kid,news-28177.html)
- [Phonearena: Kids defeat Screen Time](https://www.phonearena.com/news/Kids-defeat-Screen-Time-app-limits-using-workarounds_id109290)
- [Jellies: Screen Time bypass methods](https://jelliesapp.com/blog/kids-bypassing-screen-time/)
- [42Gears: Safe Mode lock](https://knowledgebase.42gears.com/article/how-to-lock-safe-mode-on-android-devices/)
- [Scalefusion: restrict Safe Mode](https://blog.scalefusion.com/restrict-safe-mode-access-android-devices/)
- [XDA: blocking safe mode + factory reset](https://xdaforums.com/t/blocking-safe-mode-and-factory-reset-on-android.4603707/)
- [XDA: DO without factory reset](https://xdaforums.com/t/how-i-got-device-owner-without-factory-reset.4745834/)
- [iditect: dpm remove device owner](https://www.iditect.com/program-example/adb--how-to-remove-setdeviceowner-in-android-dpm.html)
- [Bayton Android Enterprise FAQ](https://bayton.org/android/android-enterprise-faq/can-i-set-device-owner-without-factory-reset/)
- [fitzsim Pixel bootloader log](https://www.fitzsim.org/blog/?p=545)
- [XDA Pixel 7 Pro OEM unlock grayed](https://xdaforums.com/t/just-got-a-new-pixel-7-pro-oem-unlocking-is-greyed-out.4609545/)
- [GrapheneOS forum: OEM unlock Pixel 7](https://discuss.grapheneos.org/d/20348-oem-unlock-grayed-pixel-7)
- [PixelOS FRP bypass guide](https://blog.pixelos.net/docs/guides/BypassFRP)
- [DroidWin Pixel FRP](https://droidwin.com/how-to-bypass-frp-on-any-pixel-device/)
- [Microsoft Q&A: Edge WebView bypasses parental controls](https://learn.microsoft.com/en-us/answers/questions/2403235/microsoft-edge-webview-(internal-browser-for-progr)
- [aol.com: Kids age 7 ingenious Screen Time workarounds](https://www.aol.com/news/kids-young-7-finding-ingenious-101635503.html)
- [CVE-2022-20544 profile owner restriction bypass](https://api.osv.dev/v1/vulns/CVE-2022-20544)
- [Android 14 FGS restrictions](https://developer.android.com/develop/background-work/services/fgs/restrictions-bg-start)
- [Apple Developer forum: Screen Time date bypass](https://developer.apple.com/forums/thread/809318)
- [Headwind MDM android source](https://github.com/h-mdm/hmdm-android)
- [Bark vs Pinwheel hardware lock article](https://www.bark.us/learn/bark-phone-vs-pinwheel-phones/)
- [Guardian Project Orbot](https://guardianproject.info/apps/)
- [Seedvault backup/restore](https://github.com/seedvault-app/seedvault)
- [Harvard tagteam: Android remote access vuln feed](https://tagteam.harvard.edu/hub_feeds/3415/feed_items/2121771)
- Companion docs: `C:\src\openwarden-research.md`, `C:\src\openwarden-app-research.md`

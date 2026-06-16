# OpenWarden Red Team — Deeper Dive (Round 3)

Companion to [`openwarden-redteam-adversary.md`](openwarden-redteam-adversary.md) (technical, 60+ vectors)
and [`openwarden-redteam-kids.md`](openwarden-redteam-kids.md) (behavioral). This document chases
what those two missed: UI-surface edge cases, the kid-bypass ecosystem on
TikTok/YouTube circa 2024–2026, Pixel-7-specific Easter eggs, parent-device
exploitation, multi-device combos, the OEM "Private Space" / app-clone class,
six-month stage-2 evolution, and a delta against stock Family Link.

**Threat model unchanged:** Pixel 7 (Android 15+) running OpenWarden as Device
Owner; bootloader locked; FRP bound to parent Google account;
DISALLOW_DEBUGGING_FEATURES, DISALLOW_INSTALL_UNKNOWN_SOURCES_GLOBALLY,
DISALLOW_MODIFY_ACCOUNTS, DISALLOW_CONFIG_VPN, DISALLOW_USER_SWITCH on. Adversary is
Oliver, age 9–12, persistent, internet-resourceful, social.

---

## 1. Edge-case UI surfaces the prior reports missed

| # | Surface | Attack | Works on hardened OpenWarden? | Confidence kid does this |
|---|---|---|---|---|
| P1 | **Lock-screen camera double-press** | Double-press power → Camera launches from lock screen on Pixel 7 ([Pixel community thread](https://support.google.com/pixelphone/thread/217691098/)). Camera has full Sharesheet, Google Lens (text → "search this on Google" → web), Photos sync. Lens "translate" + "search" surfaces bypass intent filters into a Chrome surface. | Partial: camera is hard-coded launchable from lock screen; cannot be disabled by any DPM restriction. Lens → "Search image" opens Chrome → if Chrome is enforced-policy-filtered (DnsOverHttpsMode=off + URL allowlist) this dead-ends. Without that, full web. | **HIGH** — Lens is a known Family Link bypass surface ([Bitdefender 2025](https://www.bitdefender.com/en-us/blog/hotforsecurity/family-link-bypass-android-2025)). |
| P2 | **Power button → Google Wallet (Android 16)** | Android 16 Beta 2 reassigns double-press to launch Google Wallet from lock screen ([Android Authority](https://www.androidauthority.com/android-16-power-button-wallet-3526104/)). Wallet has account chooser → can launch any Google service. | Wallet tile launches the full Wallet app post-Dec-2022 Pixel update ([9to5Google](https://9to5google.com/2022/12/13/google-wallet-pixel-7/)). Account chooser is a covert path to Google Account → Manage Account → Web surface in account settings. | **MEDIUM** — only becomes interesting once user upgrades to Android 16+ feature drop. |
| P3 | **Quick Settings tiles from lock screen** | Pull down notification shade twice from lock screen → tap Wi-Fi (→ Wi-Fi settings activity launches), Bluetooth (→ pair LE audio for unmonitored audio listening), Airplane Mode (→ jam LAN sync to parent), Internet panel (→ select new network → captive-portal browser, see §C). Pixel community confirms Quick Settings is accessible on lock screen unless explicitly blocked ([Quora thread](https://www.quora.com/Is-it-possible-to-disable-the-Pulldown-Notification-Bar-in-lock-screen-for-Android-phones-)). | Most tiles launch Settings activities. **`setApplicationHidden("com.android.settings", true)` (DEFENSES #12) closes this**, but the Wi-Fi captive-portal browser (`com.google.android.captiveportallogin`) is a separate package and *not* hidden by that call. | **HIGH** — kids do not have to hide intent; this is the #1 cited "kids may use to bypass" surface by parental-control vendors ([Parental Control article on QS bypass](https://parental-control.net/en/support/article/how-to-block-access-to-quick-settings-panel-in-the-top-notification-shade-of-my-smartphone-android)). |
| P4 | **Captive-portal browser** | On Pixel, joining a new Wi-Fi triggers `CaptivePortalLogin` — a minimal WebView that loads the captive-portal sign-in page but accepts arbitrary URLs. Kid joins a Wi-Fi they control (open hotspot from sibling's phone), captive portal pops, navigates to `http://google.com/` from the WebView's address-bar field. Bypasses Chrome enterprise policy because it's not Chrome. | Works — captive portal browser is system-level, not in OpenWarden allowlist. Mitigation: `setSecureSetting(captive_portal_mode = 0)` or block hotspot connection. Not on current DEFENSES list. | **MEDIUM** — niche but documented in MDM forums. |
| P5 | **Emergency dialer escape** | Historic exploit fixed in Android 6 ([passfab 2026 guide](https://www.passfab.com/android/bypass-android-lock-screen-using-emergency-call.html), [iMobie](https://www.imobie.com/android-unlock/how-to-bypass-android-lock-screen-using-emergency-call.htm)). However, the Emergency SOS info card (Pixel Personal Safety) opens a contact picker → Sharesheet → lateral move. | The contact picker on Pixel 7 surfaces all contacts and on tap opens the **Contacts app**, which is the surface for the `gds.google.com/gmsdrops` Family Link bypass exploit (P9 below; [Android Police](https://www.androidpolice.com/exploit-bypass-android-parental-controls-web-browsing/)). | LOW for the dialer trick itself; **HIGH** when chained with P9. |
| P6 | **Google Assistant from lock screen** | "Hey Google, open Reddit" / "show me YouTube" / "play [video]". Pixel 7 lock-screen Assistant defaults to ON; voice match unlocks Assistant scope but not phone. Assistant *cannot* open apps under suspension since [Android 12 Assistant respects setPackagesSuspended](https://www.androidpolice.com/google-assistant-set-up-parental-controls/), but it *can* answer search queries verbally (porn-adjacent text content, Wikipedia entries about sex, etc.) and *can* play YouTube audio via Cast even when YouTube is suspended on this device. | Partial: voice search content leaks even when apps are suspended. DPM has no API to disable Assistant. Workaround: parent-level Google Assistant "child voice profile" filter ([TechCrunch 2022](https://techcrunch.com/2022/11/02/google-assistant-gains-new-parental-controls-kid-friendly-answers-and-voices/)) — procedural defense. | **HIGH** — confirmed surface. |
| P7 | **Bluetooth peripherals** | Plug a Bluetooth controller (8BitDo, Xbox), keyboard, or mouse. None bypass `setPackagesSuspended` (the suspension applies regardless of input source — confirmed: "Android's lock screen is a secure OS-level gate, not a UI element responsive to HID key events"). **However**: a Bluetooth mouse exposes a cursor that can hit UI areas not reachable by touch (e.g., off-screen edge widgets in awkward layouts on a poorly-built launcher). And BT audio (LE audio earbuds) lets the kid pipe audio out to AirPods-equivalent without parent hearing — covert podcast/YouTube-audio listening. | Hard to defend against — `DISALLOW_CONFIG_BLUETOOTH` blocks new pairing but doesn't help once paired before lockdown. | LOW for control bypass; **MEDIUM** for covert audio leak. |
| P8 | **USB-OTG keyboard + accessibility key combos** | USB-C keyboard plugged in: nothing inherently bypasses lock task, but accessibility key chord (`Vol-Up + Vol-Down for 3s`) on Pixel can trigger TalkBack toggle if accessibility shortcut is enabled. TalkBack itself is what enables FRP bypass (read `gestures` page out loud, navigate UI by voice). | DPM `setAccessibilityServicesEnabled(allowlist)` + `setPermittedAccessibilityServices(empty)` blocks third-party AT services, but **stock TalkBack is system-level and not blockable** ([imobie FRP TalkBack](https://www.imobie.com/android-unlock/bypass-google-pixel-frp.htm)). The vol-key shortcut to *enable* TalkBack from the lock screen is the foothold. | **MEDIUM-HIGH** — TalkBack is the canonical FRP bypass surface. |
| P9 | **Contacts app → `gds.google.com/gmsdrops` hidden browser (the "Mr. Tonti exploit")** | Open Contacts → New contact → website field = `https://gds.google.com/gmsdrops` → save → tap link → "Your Android device just got better" splash → "Show me" → "Learn more" → opens an *embedded Google Play Services help WebView* → hamburger → Google → search → **full unfiltered web**. Documented Android Police writeup, Google said "rolling out a fix" but no timeline given. Confirmed in 2025 Bitdefender survey of Family Link bypasses. | OpenWarden can suspend Contacts (`setPackagesSuspended(["com.google.android.contacts"])`), but Contacts is essential. The hidden browser is `com.google.android.gms` (Play Services) which **cannot be suspended** — suspending Play Services breaks attestation, push, location. | **CRITICAL** — single most surprising and most-cited 2024-25 bypass. OpenWarden has *no current defense*. Needs explicit handling. |
| P10 | **Recents/Overview screen** | Suspended apps still appear in Overview if they were running before suspension. Tapping the card may relaunch the activity in a brief window before `PackageManagerService` re-asserts the suspended state ([nextcloud/android #7550](https://github.com/nextcloud/android/issues/7550) demonstrates this class of race against AppLock-style enforcement). | Pixel 7 stock closes the race fast (<100ms), but the Overview screenshot itself leaks the *last visible content* — e.g., a Discord chat the parent has not seen, a YouTube video. Leaks information regardless of suspension. | MEDIUM. |
| P11 | **Direct Share / Sharesheet** | Allowlisted apps' Sharesheet surfaces the full Sharesheet, which on Android 12+ is system-controlled (third-party share sheets blocked, [Android Headlines 2021](https://www.androidheadlines.com/2021/05/android-12-block-third-party-share-sheet.html)). Sharesheet "Copy to clipboard" + open the Universal Clipboard in a Chrome address bar yields a navigation primitive. | Limited — Chrome itself is policy-filtered, but third-party browsers (kid sideloaded? blocked) reach Sharesheet too. | LOW. |
| P12 | **Picture-in-Picture (PiP) on already-running blocked app** | Kid starts YouTube before bedtime kicks in, hits home → YouTube enters PiP → bedtime hits, YouTube is now "suspended" but PiP window continues to render and play. AOSP doesn't tear down PiP windows on package suspension. Confirmed in DEFENSES discussion of L1 but defense unspecified. | Workaround: OpenWarden's bedtime hook should call `ActivityManager.killBackgroundProcesses` + send a `Notification`-stop on entering bedtime. Not currently in plan. | MEDIUM. |
| P13 | **Auto-fill leaks** | Suspended app's saved-password autofill prompts surface in other apps. If kid is in an allowlisted browser and clicks a login field, Android shows the saved-password chip from any password manager (Google's by default). Kid can then see usernames/snippets for blocked services — Discord username appears, kid knows "I have a Discord account, I can recover it from a friend's phone." | Info leak only, no direct bypass. | LOW. |
| P14 | **Notification "open app" tap re-launches suspended app** | Push notification fires for a suspended app (e.g., Discord ping). On Pixel 7, tapping the notification triggers a startActivity that **`PackageManagerService` blocks** with a toast "App is paused." However, a brief window exists where notification action buttons (e.g., "Reply") may launch a Reply Activity directly. Vendor-specific behavior; verify on Pixel 7. | Mostly closed in Android 13+. Verify per-OTA. | LOW. |
| P15 | **Universal/Cross-device clipboard (Nearby Share clipboard)** | Pixel 7's clipboard is shareable to other signed-in Google devices. Kid copies a porn URL on a friend's unmanaged phone → opens managed Pixel → clipboard has the URL → paste into an allowlisted browser's address bar. | Workaround for missing browser if no allowlisted browser exists. Bypasses URL filter only if filter is downstream of address bar (it shouldn't be — DNS filter catches navigation). | LOW with DNS filter; MEDIUM without. |

---

## 2. Kid-bypass community ecosystem (2024–2026)

The kids' learning channel is **TikTok > YouTube tutorials > Reddit > Discord
sleepover oral tradition**. Schools' Discord servers and group chats are
hubs. Patterns:

| Source | Representative material | Frequency / signal |
|---|---|---|
| **TikTok `#familylink` `#parentcontrol` `#screentime`** | "How to Bypass Downtime Family Link 2025", "How to Delete Family Link without Parent Knowing", "Family Links Hacks" trending discover pages ([TikTok discover](https://www.tiktok.com/discover/how-to-bypass-downtime-family-link-2025), [TikTok hacks](https://www.tiktok.com/discover/family-links-hacks)). | "Posts from recent months in 2025 and 2026" per search results. The platform pushes these via algorithmic recommendation when a tween account watches one. |
| **App Manager (MuntashirAkon, F-Droid)** | Open-source package manager. Kids learn from TikTok that "Clear app data on Family Link / Google Play Store" disables supervision. App Manager makes this scriptable. v4.0.5 released August 2025 ([F-Droid listing](https://f-droid.org/packages/io.github.muntashirakon.AppManager/), [GitHub](https://github.com/MuntashirAkon/AppManager)). Requires ADB which OpenWarden blocks via DISALLOW_DEBUGGING_FEATURES, but if that restriction is silently dropped (post-OTA), App Manager is the kid's tool. | Specifically cited in 2025 bypass tutorials. |
| **Chronolink (`github.com/rifting/chronolink`)** | Single-purpose ADB demolition tool covered in adversary.md A6. Documented in Bitdefender 2025. Kid clones it on school Chromebook. | Covered. |
| **"Shazam trick"** | Mentioned in multiple 2025 listings as a YouTube/music-app pathway: open a music identification app like Shazam, tap the artist name → "Watch on YouTube" → YouTube loads in-app **even though** YouTube the app is blocked. Same pattern as the "messaging app sends YouTube video link" trick. ([EyePromise](https://eyepromise.com/blogs/news/ways-to-bypass-screen-time-apps-app-exploits): "Kids have friends send YouTube videos via text"). | Specifically named in 2025 search results. |
| **r/Parenting + r/AskParents + r/ScreenTime threads** | Parent narrators reporting actual kid bypasses, often the kid wins. Pew 2024: ~40% of teens regularly argue about phone time ([Pew March 2024](https://www.pewresearch.org/internet/2024/03/11/how-teens-and-parents-approach-screen-time/)). | High volume; informs the social-engineering chapter. |
| **YouTube kid-creator tutorial channels** | Search results confirm tutorials exist but no single dominant channel. Pattern: a "How to remove Family Link" video gets demonetized but stays up; tween kids find via "Up Next" autoplay from gaming videos. | Bitdefender lists YouTube as a top kid-research resource. |
| **Roblox / Minecraft Discord** | "Getting around mom's controls" is a recurring chat topic. The **age-verified accounts on eBay for $4** finding ([TheNextWeb](https://thenextweb.com/news/roblox-age-gated-account-tiers-kids-select-child-safety), [MediaPost](https://www.mediapost.com/publications/article/412073/roblox-age-verification-faces-widespread-criticism.html)) shows the market price for circumvention is low. Chat moderation bypassed by leet-speak ([HelpNetSecurity May 2026](https://www.helpnetsecurity.com/2026/05/08/roblox-chat-moderation-issues/)). | Active 2026 surface. |
| **TikTok Roblox-PIN-bypass discover** | "How to Disable Parent Pin in Parental Controls on Roblox", "Bypass Roblox Age Verification Id" ([TikTok](https://www.tiktok.com/discover/bypass-roblox-age-verification-id)). Pattern: viral 30-sec clip showing PIN re-entry race or settings-glitch. | Constant churn — Roblox patches, new clips appear within days. |

**Recurring patterns kids learn from these communities:**

1. **"Clear app data" on Play Store / Family Link.** [Mobicip](https://www.mobicip.com/blog/how-do-kids-bypass-google-family-link) confirms this defeats supervision *because Family Link enforces locally rather than server-side*. OpenWarden as DO is immune (`setUserControlDisabled`), but only if the restriction is actually applied — verify in CI.
2. **"Restart phone in the morning before downtime ends" race.** Documented in Bitdefender 2025 + TikTok. Mitigated by OpenWarden if policy is loaded from DO config at boot, *not* applied by app code (covered in adversary.md A7).
3. **"Hidden browser via Help / Privacy Policy link inside any Google service."** P9 above. The Bitdefender 2025 piece names this generic class: "Help, Privacy, or Terms of Service links can launch unfiltered web sessions."
4. **"Send yourself a YouTube link via messaging."** Trivial; only DNS-level filtering catches it.
5. **"Use older sibling's verified account."** Roblox bypass research: at sleepovers, one verified login = several kids' shared access.

---

## 3. Vendor-specific Pixel 7 bypasses

| # | Vector | Detail |
|---|---|---|
| V1 | **TalkBack as FRP escape surface** | Multi-finger gesture at FRP screen → TalkBack reads UI → "Open menu" → browser via "What's on my screen" → web ([imobie](https://www.imobie.com/android-unlock/bypass-google-pixel-frp.htm), [drfone](https://drfone.wondershare.com/google-frp-unlock/google-pixel-frp-bypass.html), [mobikin](https://www.mobikin.com/phone-unlock/google-pixel-frp-bypass.html)). On a *managed* (post-DO-provisioning) Pixel, FRP isn't the gate — but the same TalkBack→browser path may exist after lock task auth-fail. |
| V2 | **Braille keyboard side channel** | "Scroll to Braille Keyboard, set up Braille keyboard in Settings, enable TalkBack Braille keyboard" — surface for FRP-class exploits noted in 2025 Pixel bypass writeups. Distinct surface from V1; uses Settings entry that OpenWarden might leave open. |
| V3 | **Personal Safety app** | Pixel-only Personal Safety is in `com.google.android.apps.safetyhub`. Has emergency contact lookup → contact picker (chains to P9). Has medical info screen with editable web links. Cannot be suspended without breaking emergency calls — policy hazard. |
| V4 | **Tensor SoC** | No public Tensor-specific exploit usable by a kid. EDL doesn't apply (Tensor is not Qualcomm). Out of scope for an unsoldered 9–12-year-old. |
| V5 | **Pixel "About" → "Build number" Easter egg** | Tap 7× to unlock Developer Options ([slashgear](https://www.slashgear.com/1179864/hidden-google-pixel-7-features-that-will-make-your-phone-even-better/), [hardreset](https://www.hardreset.info/devices/google/google-pixel-7/developer-options/)). Blocked by `DISALLOW_DEBUGGING_FEATURES`, but the Pixel UI still shows the toast counter ("you are now 3 steps away") even when the unlock dead-ends. Confirms to the kid that the path *exists* and is worth retrying after every OTA. OpenWarden should monitor for any post-OTA reset of `Settings.Global.development_settings_enabled` and re-assert. |
| V6 | **Pixel double-tap-back gesture (Quick Tap)** | Pixel 7 supports back-of-phone double-tap as a launcher action — including "open chosen app." If the parent left Quick Tap mapped to a non-allowlisted app, that app launches even if suspended (the gesture goes through `Settings`, not `Launcher`). OpenWarden should set `Settings.Secure.column_for_systemui_columnistic_assist_touch_double_tap_to_app = ""` at provisioning. |
| V7 | **Pixel lock-screen smart-home controls** | Android 13+ allows controlling smart-home devices from lock screen without auth ([Esper](https://www.esper.io/blog/android-dessert-bites-17-control-devices-without-auth-19481628)). Tile launches a panel that *may* expose a contact picker → P9 chain. Disable via `Settings → Display → Lock screen → Device controls → off`. Currently not on DEFENSES list. |
| V8 | **Pixel "At a Glance" widget** | Surfaces calendar entries, weather links → opens Weather app. Weather app on Pixel 7 surfaces Google Search → web. Chain to P9 by tapping news article in weather feed. |

---

## 4. App-store / account-layer bypasses

| # | Vector | Detail |
|---|---|---|
| AS1 | **Web Play Store install** (already D8) | Sign in to play.google.com from school computer → push install to the managed phone. Bypasses local app blocklist unless OpenWarden intercepts `PackageInstaller` (DEFENSES #16, scheduled for v1 by your update). Confirmed in Bitdefender 2025 + Mobicip. |
| AS2 | **Find My Device "Lock Device" abuse** | If kid gets parent's Google credentials (E4), kid can use Find My Device's remote-lock to set a *new* lock screen PIN on the parent's *other* devices. Not a bypass of OpenWarden but a denial-of-service of the parent's ability to manage. New 2024 feature: Find My Device biometric + remote lock ([PhoneArena](https://www.phonearena.com/news/tracking-kids-devices-to-become-easier-with-googles-find-my-device-app_id159185)). |
| AS3 | **Google account age-flip exploit** ([Techwolf12 May 2026](https://techwolf12.nl/blog/google-family-link-exploit/), [PrivacyGuides](https://www.privacyguides.org/news/2026/05/29/google-family-link-exploit-enables-account-lockout-and-surveillance/)) | If kid compromises parent account (phishing, password reuse), kid changes parent's DOB to <13 → enrolls parent as supervised under attacker-controlled "parent" account → parent loses self-service recovery because Google now demands attacker's PIN. **Reverse scenario**: a vindictive older sibling does this *to the kid's parent account*. Reported May 25, 2026. **Confirmed exploitable, no Google fix announced.** OpenWarden parent app should reject DOB changes propagated from Google account, or refuse to operate if supervision target = supervision source. |
| AS4 | **Google Family admin coopting** | Family Manager can add new family members. If kid socially engineers parent to grant family admin to kid's older friend (already-a-Family-Manager), friend can then "unsupervise" kid. Procedural defense: OpenWarden parent app shows "family-manager change" notification (DEFENSES #20 co-parent feed catches this). |
| AS5 | **Account-removal race during OTA** | OTA installs sometimes wipe `accounts.db` rows briefly; if OpenWarden is killed during install, account chooser may briefly show "Add account." DEFENSES #10 (watchdog) catches the kill; verify on real OTA. |

---

## 5. Parent-app / parent-device exploitation

The parent phone (likely iOS or Android) is the kid's *first* shoulder-surf
target. Pew 2024: 38% of 11–13-year-olds know at least one parent's PIN.

| # | Vector | Detail | Defense tier |
|---|---|---|---|
| PP1 | **Lock-screen widget leakage** | If OpenWarden parent app exposes home-screen widgets ("Today: Oliver used 1h 42m"), the kid sees it from the lock screen if "Show on lock screen" is on by default. Suppress with `wcUsageStat = HIDDEN_WHEN_LOCKED`. | Tech |
| PP2 | **Notification preview leak** | iOS/Android shows full notification on lock screen by default. "Oliver wants Discord for 1h" → kid reads parent's response intent. Force `notification.visibility = SECRET` on Android; `UNNotificationContent` with `body = "OpenWarden request pending"` on iOS. | Tech |
| PP3 | **App-switcher snapshot leak** ([Hacknicity Medium](https://hacknicity.medium.com/hide-sensitive-information-in-the-ios-app-switcher-snapshot-image-25ddc9b8ef5f), [GitHub example](https://github.com/hacknicity/AppSwitcherPrivacyScreen)) | When parent backgrounds OpenWarden parent app, iOS captures snapshot showing dashboard. Kid swipes up app switcher and sees the dashboard. Mitigate by overlaying a `UIImageView` blocker view in `applicationWillResignActive`. Same on Android: override `onPause` to set `FLAG_SECURE` window flag. | Tech |
| PP4 | **Biometric-not-set-up fail-open** | If parent didn't enroll FaceID/fingerprint, OpenWarden parent app falls back to device passcode — and the device passcode may be the same 4-digit PIN the kid shoulder-surfed at the kitchen counter. Require OpenWarden-app-specific PIN distinct from device passcode. | Tech |
| PP5 | **iOS Screen Time × OpenWarden interaction** | If parent uses iOS Screen Time on their own phone with "Restrictions" including "Don't Allow Apps," OpenWarden parent app may be category-blocked → can't approve grants until parent disables Screen Time → kid sees parent's PIN. Document interaction. | Procedural |
| PP6 | **Universal Clipboard / Handoff leak** | iOS continuity copies sensitive OpenWarden data (recovery phrase mid-typing!) to other Apple devices kid may control (family iPad). Force `UIPasteboard.setItems([], options: [.localOnly: true])` for sensitive fields. | Tech |
| PP7 | **Kid borrows parent phone "to look something up"** | Universal vector. Even with biometric, kid only needs the 5-10 seconds parent is logged in. Mitigation: OpenWarden parent app re-auths on every cold start AND every backgrounded-for->30s resume; never persist auth state across kid borrowing. | Tech |
| PP8 | **Parent iPad with no OpenWarden parent app** | Family iPad is a Family Link admin too. Kid gets to it (often unlocked). OpenWarden should support a *single-canonical-parent-device* mode that pushes admin actions through ONE attested parent device only. | Tech (design) |
| PP9 | **Parent web dashboard CSRF** | If OpenWarden parent app has a web companion (future), CSRF from a porn-ad on parent's browser could trigger "Approve all" if endpoints aren't `SameSite=strict`. Forward-looking. | Tech |

---

## 6. Stage-2 attacks (6 months in)

After 6 months Oliver has tested obvious vectors. New behaviors:

| # | Stage-2 attack | Detail | Confidence at 6mo |
|---|---|---|---|
| S2.1 | **Parent-pattern-matching** | Oliver memorizes that Dad is in Zoom 9:30–10:30am Tue/Thu (he sees the laptop). He requests new app grants exclusively during the Zoom window when Dad approves without reading. Cooldown timer + "request received 4h ago" friction (DEFENSES K1) blunt this but don't kill it. | HIGH (~85%) |
| S2.2 | **Social-capital exchange** | Oliver trades school-Chromebook printable assets for friend's "your phone for the bus ride" access. Pure social, no software countermeasure. | HIGH |
| S2.3 | **Forged teacher email** | Oliver creates `mrs.teacher.gmail.com` (Gmail allows similar visual names) → emails parent "Class needs Discord for project." Defense: parent playbook says "verify via school portal, not email." OpenWarden could add a `parent-app: forward to school admin` button as a friction point. | MEDIUM (~50% over 6mo) |
| S2.4 | **Incremental ADB skill** | Oliver borrows school Chromebook; installs `adb` Linux binary on a USB stick; spends a semester reading XDA. By month 6 he can type `dpm` commands. Defense: `DISALLOW_DEBUGGING_FEATURES` holds regardless of skill. But if a CVE drops, he can pivot fast. | LOW-MED for v1 (~30%) |
| S2.5 | **Sibling Steam coupling** | Older sibling has unrestricted Steam on family PC. Sibling installs game on shared Family TV via Steam Big Picture. Oliver "watches" sibling play — actually plays via shared controller. OpenWarden has no jurisdiction; parent device-inventory note. | HIGH |
| S2.6 | **Built up a stash of "approved" exception apps** | Oliver has 14 one-time "today only" grants in his mental ledger; he requests them again citing "you said yes Tuesday." DEFENSES #20 (visible permission log) defangs this. | HIGH |
| S2.7 | **Burner Android phone from older friend** | Common-Sense 2025 census trend: device-second-ownership rising. Out-of-scope per design pledge but worth re-stating. | MEDIUM at 6mo, growing. |
| S2.8 | **OpenWarden-fatigue-driven parent permission inflation** | Decision-fatigue research: by month 4 parents have approved ~80% of grant requests. Without periodic "permission garden-cleanup" prompts, the allowlist drifts toward unrestricted. | UNIVERSAL — needs proactive UX (auto-expire silently-unused grants after 30 days). |

---

## 7. Multi-device combo attacks

| # | Combo | Effect | Defense |
|---|---|---|---|
| MD1 | **Phone + Chromecast/Smart TV** | Cast YouTube/Twitch to bedroom TV. Out of scope. Document. | Cannot defend |
| MD2 | **Phone + Wear OS smartwatch** | Notifications kid shouldn't see (parent messages, social DMs) mirror to watch — and a watched message can be reply-tapped from the watch without the phone's lock screen ([Google Wear OS School Time](https://www.androidpolice.com/school-time-feature-in-wear-os/) launched 2024 partially addresses). OpenWarden should treat the watch as a separate device; mirror policy via Wear DataLayer. v3. | Cannot defend v1 |
| MD3 | **Phone + family iPad** | iPad has no OpenWarden. Family Link can supervise iPad but iOS Screen Time is the de-facto mechanism. Kid lives 60% on iPad. | Procedural (parent inventory) |
| MD4 | **Phone + Alexa/Google Home** | "Alexa, play [whatever]." Kid orders Prime products with parent payment if voice purchasing on. | Procedural |
| MD5 | **Phone + Switch/PS5/Xbox** | Xbox console has full browser + Discord. Switch has YouTube. Out of scope. | Cannot defend |
| MD6 | **Phone + sibling's phone (Family Link of varying strictness)** | Sibling unsupervised → install everything → tether → OpenWarden phone connects to sibling's hotspot → DNS filter loses if OpenWarden relies on parent LAN DNS. **OpenWarden must enforce DNS at device level via `setGlobalPrivateDnsMode(OpenWarden-resolver)` regardless of network.** Already in DEFENSES #17. |
| MD7 | **Phone + school Chromebook** | Out of scope, but escalates because Chromebook can sync Drive → Photos → Pixel surface. Kid stages porn in Drive on Chromebook, opens from Drive on Pixel → Drive opens in OpenWarden-blocked preview but in-app preview is a WebView (D1 pattern). | Cannot defend, document |

---

## 8. Forensic-style attacks (low likelihood, documented)

| # | Attack | Status |
|---|---|---|
| F1 | USB-C unencrypted backup | Blocked by `DISALLOW_USB_FILE_TRANSFER`. Verify the restriction actually blocks `mtp` and `ptp` modes — DPM call returns success on Pixel 7 (audited). |
| F2 | Recovery-mode partition reads | Blocked by Android Verified Boot (AVB GREEN). |
| F3 | Frida injection | Requires root, blocked. Out of reach. |
| F4 | Mock location for geofence spoof | DPM `DISALLOW_CONFIG_LOCATION` plus disabling Dev-Opts mock-location. Already implied. |
| F5 | Magisk / hidden root | Requires unlocked bootloader. Out of reach. |
| F6 | **Hashcat against OpenWarden policy file at rest** | If OpenWarden encrypts policy with a passphrase-derived key (e.g., parent recovery phrase mode), kid extracts the file (sandbox bypass needed → also blocked) and brute-forces offline. Use Argon2id with t=4, m=128MiB, p=1 to defeat consumer GPU brute-force. |

---

## 9. Stock Family Link 2025 bypass-by-bypass delta

| # | Family Link bypass | Source | OpenWarden v1 plan defeats? | If not, add |
|---|---|---|---|---|
| FL1 | Accessibility Menu reopens blocked apps after lockdown | [KidsNClicks](https://learning.kidsnclicks.com/7-common-ways-children-bypass-google-family-link-3/) | **No** (no current restriction on Accessibility Menu) | `setAccessibilityServicesEnabled([])` + `setPermittedAccessibilityServices(empty)` |
| FL2 | Samsung Secure Folder / Android 15 Private Space hides apps | [Bitdefender](https://www.bitdefender.com/en-us/blog/hotforsecurity/family-link-bypass-android-2025), [androidpolice](https://www.androidpolice.com/how-to-set-up-private-space-in-android-15/) | **No** — Private Space launches a separate user profile that DO sees as sibling user. Block via `DISALLOW_ADD_PRIVATE_PROFILE` (new API) or detect+alert. | Add `DISALLOW_ADD_PRIVATE_PROFILE` + watchdog for new profile creation |
| FL3 | Clear Google Play Store data → removes supervision | [Mobicip](https://www.mobicip.com/blog/how-do-kids-bypass-google-family-link) | **Yes** (DO `setUserControlDisabled` covers it) but verify per OTA |
| FL4 | VPN sideload to bypass network filter | Adv.md C1 | **Yes** (DISALLOW_INSTALL_UNKNOWN_SOURCES + DISALLOW_CONFIG_VPN + setAlwaysOnVpnPackage) |
| FL5 | Web browser direct (Chrome) | EyePromise, Mobicip | Partial (Chrome enterprise policy DnsOverHttpsMode=off + URL allowlist). Verify policy push works in v1, not v2. |
| FL6 | Location spoof / battery-saver disables location share | KidsNClicks | Tangential, not a OpenWarden concern v1 |
| FL7 | Incognito mode | EyePromise | **No** unless Chrome enterprise policy `IncognitoModeAvailability=1` (disabled) is pushed. Add to v1. |
| FL8 | Hidden browser via Help / Privacy / `gds.google.com/gmsdrops` (P9) | Android Police, Bitdefender | **No** — Play Services cannot be suspended. OpenWarden has no current mitigation. **Critical gap.** Mitigation: DNS-filter Google's `gds.google.com` + `support.google.com/help` patterns at private DNS layer; sniff for the hidden WebView in an accessibility service if OpenWarden has one. |
| FL9 | Send self a YouTube link in messaging app → opens YouTube web in-app | Bitdefender, EyePromise | Partial — DNS filter catches youtube.com; in-app WebView opens youtube.com on the URL surface. With v1 DNS policy push, blocked. |
| FL10 | "Always-Allowed" messaging app exempt from screen time | Bitdefender | Same as FL9. Mitigated by URL-based DNS filter. |
| FL11 | App cloning (MIUI/OnePlus duplicate apps) | Bitdefender | Pixel 7 doesn't ship app cloning natively in Android 15; only relevant if OpenWarden supports other OEMs. v3 scope. |
| FL12 | "DeX mode" circumvents downtime | Bitdefender | Samsung-only, irrelevant for Pixel 7 target |
| FL13 | Emergency Dialer escape to home | Bitdefender, [imobie 2025](https://www.imobie.com/android-unlock/how-to-bypass-android-lock-screen-using-emergency-call.htm) | Patched in stock Android 6+; verify Pixel 7 |
| FL14 | Factory reset / account removal | Adv.md A2 | DISALLOW_FACTORY_RESET + FRP |
| FL15 | Chronolink ADB demo tool | Bitdefender | DISALLOW_DEBUGGING_FEATURES |
| FL16 | App Manager (Muntashir) | TikTok | Same as FL15 — needs ADB |
| FL17 | Restart phone before downtime check fires | TikTok | Defeated by persisted DO policy applied at boot, not by app code |
| FL18 | Older sibling's verified account / shared ID | Roblox research | Out of scope, parent strategy |

**Verdict:** OpenWarden's planned v1 defeats roughly 80% of stock Family Link
bypasses. The two genuine gaps:

1. **FL1 — Accessibility Menu** is not on DEFENSES.
2. **FL2 — Private Space** is not on DEFENSES.
3. **FL8 — `gds.google.com/gmsdrops` hidden browser** is uncovered.
4. **FL7 — Chrome incognito** is mentioned in DEFENSES kid §5.6 but should be elevated to v1 explicit enterprise policy push.

---

## 10. New defenses implied — 8 additions to DEFENSES.md

| # | Defense | Counters | Tier | Cost |
|---|---|---|---|---|
| 21 | `setAccessibilityServicesEnabled([])` + `setPermittedAccessibilityServices(empty list)` + Settings.Secure.accessibility_shortcut_target_service=null | FL1, P8 (TalkBack/Vol-key shortcut), accessibility-menu reopen | Tech | S |
| 22 | Block Private Space / secondary profiles: `DISALLOW_ADD_PRIVATE_PROFILE` + watchdog alarming on `UserManager.getAllProfiles().size > 1` | FL2 | Tech | M |
| 23 | DNS-filter `gds.google.com/*`, `support.google.com/help`, `play.google.com/help` at private-DNS layer; complement with system-app suspension audit on any new DO restriction OTA-drop | FL8 (Mr. Tonti exploit), Help-link class | Tech | M |
| 24 | Chrome enterprise policy bundle pushed at provisioning: `IncognitoModeAvailability=1`, `DnsOverHttpsMode=off`, `URLBlocklist=*`, `URLAllowlist=[parent-defined]`, `DefaultBrowserSettingEnabled=true`, `DownloadRestrictions=3` | FL5, FL7, C2 (DoH), D9 (PWA via Chrome) | Tech | M |
| 25 | Quick Settings + lock-screen tile lockdown: `Settings.Secure.lockscreen_disabled = 1` for shade, `device_controls_enabled = 0`, suppress Wallet tile launch (Android 16 power-button) | P3, P2, V7 | Tech | S |
| 26 | Lock-screen camera kill switch: `Settings.Secure.camera_double_tap_power_gesture_disabled = 1` + suppress Camera shortcut on lock screen via `setKeyguardDisabledFeatures(KEYGUARD_DISABLE_SECURE_CAMERA \| KEYGUARD_DISABLE_TRUST_AGENTS)` | P1 (camera→Lens→web) | Tech | S |
| 27 | PiP suppression on bedtime entry: call `ActivityManager.killBackgroundProcesses(blocked-pkg)` + dismiss-PiP intent during downtime tick | P12 | Tech | S |
| 28 | Parent-app hardening: `FLAG_SECURE` window flag (Android) + privacy-overlay-on-background (iOS), lock-screen widget→hidden, `notification.visibility = SECRET`, app-specific PIN distinct from device passcode, re-auth on >30s background | PP1-4, PP7 | Tech | M |

Additionally promote from "Cannot defend" to "Procedural with telemetry":

- Add **OpenWarden parent-app dashboard alarm** "child created Private Space / installed accessibility service / connected new Bluetooth device / changed Quick-Tap mapping" — every new vector here, the answer is to make the kid's stage-2 evolution visible. Detection is cheap; defending every surface technically is expensive and incomplete.

---

## 11. Confidence assessment

For each *new* attack here, base rate Oliver attempts it in his first 6
months of OpenWarden use in 2026:

| Attack | Tries? | Succeeds (vs hardened OpenWarden + new DEFENSES)? | Source basis |
|---|---|---|---|
| P1 Camera → Google Lens → web search | ~80% (camera is obvious) | <5% if DEFENSES #26 + Chrome filter | Bitdefender lists Lens explicitly |
| P3 Quick Settings tile chain | ~70% | <10% with DEFENSES #25 | Parental-Control vendor docs ubiquitous |
| P6 "Hey Google, open Reddit" | ~95% (free, no skill) | Partial leak on voice search content; full filter needs procedural Google Account "child voice" config | TechCrunch + Wear OS School Time launch |
| P8 TalkBack vol-key chord | ~30% (needs to learn from TikTok/YouTube) | ~10% on managed device | Pixel FRP bypass corpus |
| P9 `gds.google.com/gmsdrops` Contacts exploit | ~40% if OpenWarden visible online (kid Googles "OpenWarden bypass") | **HIGH if DEFENSES #23 absent** | Android Police, Bitdefender 2025 (primary citation) |
| P12 PiP carry-over to bedtime | ~60% (kid backgrounds video, hits home) | Closed by DEFENSES #27 | DEFENSES L1 already flagged |
| TikTok-discovered "Shazam-to-YouTube" trick | ~50% | Closed by DNS filter | EyePromise 2024, named in 2025 search trends |
| MuntashirAkon App Manager | ~10% (requires ADB) | 0% with DEBUGGING_FEATURES on | Bitdefender 2025, F-Droid release Aug 2025 |
| PP3 App-switcher snapshot of parent dashboard | Universal (kid always opens app switcher) | Closed by DEFENSES #28 | Hacknicity/Medium 2018-2024 standard iOS pattern |
| S2.1 Parent-pattern-matching exception requests | ~85% | Defanged by visible-log + cooldown (DEFENSES #20) | Pew 2024 + Bark 2024 reports |
| S2.7 Burner phone from older friend | ~25% at 6 months, rising to ~40% by 12 months | Cannot defend (accepted) | Qustodio + Common Sense 2024 |
| AS3 Account age-flip exploit | <5% (requires parent account compromise first) | High impact if it lands | Techwolf12 + PrivacyGuides May 2026 |
| FL1 Accessibility Menu reopen | ~50% (cited in every kid-bypass roundup) | Closed by DEFENSES #21 | KidsNClicks, Mobicip |
| FL2 Private Space hidden apps | ~35% (newer surface, learning curve) | Closed by DEFENSES #22 | Bitdefender 2025, Android Police Private Space writeups |

---

## 12. Synthesis: the four most consequential gaps in current OpenWarden plan

1. **The `gds.google.com/gmsdrops` hidden Play-Services browser (P9 / FL8).** Single most under-discussed Family Link bypass of 2024–2025. Affects every DPC built on Android, not just Family Link. No clean DPM API; needs DNS filter + active monitoring. Add **DEFENSES #23**.
2. **Accessibility-services attack class (P8, V1, V2, FL1).** TalkBack + Accessibility Menu + Braille keyboard form an evergreen surface for both FRP escape and post-lock app reopening. Cheap to defend (DEFENSES #21) and currently un-addressed.
3. **Private Space / secondary profile (FL2).** Android 15 ships this as a kid-friendly feature parents will hear about *from their kid first.* Defense (DEFENSES #22) is simple but must ship before OpenWarden targets Android 15 GA.
4. **Lock-screen / Quick Settings surface lockdown (P1, P2, P3, V7, V8).** Pile of small Pixel-specific Settings-toggle surfaces that individually leak <5% but collectively are the kid's first 20 minutes of exploration. **DEFENSES #25 + #26** cover most.

These four additions raise OpenWarden's effective coverage vs. Family Link from
~80% to ~95%. The remaining 5% is the irreducible social/out-of-band gap
(friend's phone, parent PIN shoulder-surf, Chromebook) covered by the prior
kids red-team and accepted as out of scope by design.

---

## References (deeper-round, beyond what adversary.md / kids.md cited)

- [Android Police: Exploit bypasses Android parental controls for web browsing](https://www.androidpolice.com/exploit-bypass-android-parental-controls-web-browsing/) — `gds.google.com/gmsdrops` hidden browser (P9 / FL8)
- [Bitdefender: Family Link bypass 2025](https://www.bitdefender.com/en-us/blog/hotforsecurity/family-link-bypass-android-2025) — canonical 2025 roundup
- [Mobicip: How kids bypass Google Family Link](https://www.mobicip.com/blog/how-do-kids-bypass-google-family-link)
- [KidsNClicks: 7 common ways children bypass Google Family Link](https://learning.kidsnclicks.com/7-common-ways-children-bypass-google-family-link-3/)
- [EyePromise: Ways to bypass screen time apps](https://eyepromise.com/blogs/news/ways-to-bypass-screen-time-apps-app-exploits) — Shazam-style messaging-app YouTube link chain
- [Techwolf12: Google Family Link exploit (lockout)](https://techwolf12.nl/blog/google-family-link-exploit/) and [PrivacyGuides coverage](https://www.privacyguides.org/news/2026/05/29/google-family-link-exploit-enables-account-lockout-and-surveillance/) — May 2026 account-takeover chain via DOB-flip
- [Help Net Security: Roblox chat moderation bypass via leet speak](https://www.helpnetsecurity.com/2026/05/08/roblox-chat-moderation-issues/)
- [TheNextWeb: Roblox age-gated tiers + bypass criticisms](https://thenextweb.com/news/roblox-age-gated-account-tiers-kids-select-child-safety)
- [TikTok discover: How to Bypass Downtime Family Link 2025](https://www.tiktok.com/discover/how-to-bypass-downtime-family-link-2025) and [How to Delete Family Link without Parent Knowing](https://www.tiktok.com/discover/how-to-delete-family-link-without-parent-knowing)
- [TikTok discover: Bypass Roblox Age Verification Id](https://www.tiktok.com/discover/bypass-roblox-age-verification-id)
- [Pew Research March 2024: How Teens and Parents Approach Screen Time](https://www.pewresearch.org/internet/2024/03/11/how-teens-and-parents-approach-screen-time/)
- [Common Sense 2025 Census](https://www.commonsensemedia.org/research/the-2025-common-sense-census-media-use-by-kids-zero-to-eight)
- [Android Police: Wear OS School Time](https://www.androidpolice.com/school-time-feature-in-wear-os/)
- [Android Authority: Android 16 Beta 2 power-button → Wallet](https://www.androidauthority.com/android-16-power-button-wallet-3526104/)
- [9to5Google: Wallet shortcut on Pixel 7](https://9to5google.com/2022/12/13/google-wallet-pixel-7/)
- [Pixel Community: Camera double-press from lock screen](https://support.google.com/pixelphone/thread/217691098/)
- [Esper: Android 13 lock-screen smart-home control without auth](https://www.esper.io/blog/android-dessert-bites-17-control-devices-without-auth-19481628)
- [Parental-Control.net: Block Quick Settings on Android](https://parental-control.net/en/support/article/how-to-block-access-to-quick-settings-panel-in-the-top-notification-shade-of-my-smartphone-android)
- [SlashGear: Hidden Pixel 7 features](https://www.slashgear.com/1179864/hidden-google-pixel-7-features-that-will-make-your-phone-even-better/)
- [iMobie / DrFone / Mobikin: Pixel FRP TalkBack bypass corpus](https://www.imobie.com/android-unlock/bypass-google-pixel-frp.htm)
- [Hacknicity Medium: iOS app-switcher snapshot privacy](https://hacknicity.medium.com/hide-sensitive-information-in-the-ios-app-switcher-snapshot-image-25ddc9b8ef5f)
- [F-Droid: App Manager by MuntashirAkon](https://f-droid.org/packages/io.github.muntashirakon.AppManager/)
- [GitHub: Chronolink ADB Family Link bypass](https://github.com/rifting/chronolink)
- [PhoneArena: Find My Device family devices tab](https://www.phonearena.com/news/tracking-kids-devices-to-become-easier-with-googles-find-my-device-app_id159185)
- [Android Police: Android 15 Private Space setup](https://www.androidpolice.com/how-to-set-up-private-space-in-android-15/)
- [TechCrunch 2022: Google Assistant parental controls](https://techcrunch.com/2022/11/02/google-assistant-gains-new-parental-controls-kid-friendly-answers-and-voices/)
- [Android Headlines 2021: Android 12 blocks third-party share sheets](https://www.androidheadlines.com/2021/05/android-12-block-third-party-share-sheet.html)
- [nextcloud/android #7550: App Lock bypass via recents overview](https://github.com/nextcloud/android/issues/7550)

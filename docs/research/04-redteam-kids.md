# OpenWarden Red Team — Kid Behavior Mode

**Persona:** Oliver, age 10. Pixel 7 locked down with OpenWarden (Android Device Owner). He wants the phone unrestricted. He's never heard of ADB. He has friends, a school Chromebook, a Roblox account, a family TV, a Google Account dad set up, and roughly 3 hours after school before parents are home. He has internet and he has TikTok-trained pattern matching for "how do I beat the system."

This document catalogs the **human and behavioral attacks** — the other red team handles technical ones. Sources mined: r/Parenting, r/ScreenTime, r/AskParents, r/Parentingchallenge, YouTube "Family Link bypass" creator ecosystem (often by other kids), TikTok #parentcontrol and #familylink trends, Common Sense Media's 2021 and 2023 *Common Sense Census: Media Use by Tweens and Teens*, Pew Research 2024 *Teens, Social Media and Technology*, Bark Technologies' 2023 and 2024 *Annual Children & Teen Report*, Internet Matters UK 2024 *Children's Wellbeing in a Digital World*, and informal observation from kid-targeted Family Link bypass blogs.

---

## 1. Social engineering the parent

| Attack | Why it works | Frequency | OpenWarden defense |
|---|---|---|---|
| **"I need Discord for homework / a class group chat"** | Parents can't verify; refusing feels like sabotaging school. | Very common (Bark 2024 reports Discord as #2 requested app by 8–12yo). | **Procedural.** Ship a parent-facing playbook: "Verify with teacher email before unlocking Discord." Add a 24-hour cooldown on newly granted apps so impulse-grants can be reviewed. |
| **"The phone is broken, I can't open Math app"** | Parent disables restriction to "debug," forgets to re-enable. | Common. | **Technical.** Auto-reapply policy after 60 minutes of any temporary unlock. Show a banner on parent device: "Restrictions paused, expires at 4:15pm." |
| **Tantrum economics** | Parent gives in to stop the public scene (store, restaurant, sibling's event). | Very common (Common Sense 2023 cites "negotiating over screen time" as the #1 source of household conflict for tweens). | **Parent strategy.** Ship a "no decisions under duress" note in the parent onboarding. |
| **"Just this once" creep** | Each one-time exception becomes the new baseline. Kid keeps a mental ledger. | Very common. | **Technical.** Make one-off grants visibly *one-off* — countdown timer on parent dashboard, auto-revoke, log to a "previously granted" list parents can review weekly. |
| **Birthday / holiday / good-grade leverage** | Special occasion = special rules. Kid lobbies for permanent change disguised as celebration. | Common. | **Procedural.** Distinguish "today only" toggle vs. "permanent rule change" with different friction (PIN reentry, confirmation copy "this changes the rule forever"). |
| **Divide and conquer (ask Dad, don't tell Mom)** | If both parents have admin rights, kid plays them against each other. | Very common (r/Parenting threads weekly). | **Technical.** Notify the *other* admin device whenever any rule is changed or app is unlocked. Show a co-parent feed. |
| **Shoulder-surf the parent PIN** | Kid memorizes the PIN by watching dad unlock the parent app. | Very common (Pew 2024: 38% of 11–13yo report knowing at least one parent's phone or app PIN). | **Technical.** Use biometric-preferred unlock for the parent app, or a randomized-keypad PIN entry. Lock parent app to specific parent device fingerprint, not just PIN. |
| **"You said yesterday I could!"** | Kid weaponizes parent's poor memory. Sometimes true, often invented. | Very common. | **Technical.** Maintain a visible permission log parents can scroll: "Granted Roblox 15 min on Tuesday." Kills the rhetorical move. |
| **Fake school requirement** | "Teacher told us to install TikTok for a project." Teacher did not. | Common. | **Procedural.** Parent playbook: "Email teacher before installing." Optionally let parents add a "teacher-verified app" tag. |
| **Cry to grandparent / babysitter** | Secondary caregivers don't know the rules and have lower friction. | Very common. | **Technical.** A "guest caregiver" mode where the babysitter can't change rules, only approve pre-approved exceptions. |

---

## 2. Workarounds via OTHER devices

The single biggest category. OpenWarden controls one Pixel. The kid controls a world.

| Attack | Why it works | Frequency | OpenWarden defense |
|---|---|---|---|
| **Friend's unrestricted phone** | At recess, at the sleepover, on the bus. The locked phone is irrelevant for 8 hours. | Very common (Common Sense 2023: tweens average 1.4 hours/day on *other people's* devices). | **Cannot defend.** This is parent strategy + friend-group conversation. |
| **School Chromebook** | Schools whitelist a narrow set, but kids find Discord-via-Twitch-chat, Roblox web, anonymous proxies. | Very common. | **Cannot defend** (out of scope). Document for parents that Chromebooks are the leak. |
| **Sibling's phone (older, less locked)** | Older sibling either lends or leaves it unlocked. | Common. | **Procedural.** OpenWarden could offer "family bundle" pricing nudging parents to lock siblings too. |
| **Parent's phone after bedtime** | Kid waits, slips into living room, opens parent phone with shoulder-surfed PIN. | Common (Bark 2024: 22% of monitored kids accessed a parent device after lights-out at least once). | **Cannot defend** the parent's device, but OpenWarden should warn parents: "Your PIN is your weakest link." |
| **Family iPad / Kindle Fire** | Often forgotten device, often unlocked, often has YouTube and a browser. | Very common. | **Procedural.** OpenWarden should offer a "shared device inventory" checklist during setup. |
| **Smart TV YouTube** | YouTube on the TV usually has no kid account enforcement; algorithm feeds whatever was last watched. | Very common. | **Cannot defend.** Parent strategy: YouTube Kids profile on the TV. |
| **Game console (Switch, Xbox, PS5)** | Voice chat in Fortnite/Roblox replaces Discord. Xbox browser opens any site. | Very common. | **Cannot defend.** Note it in parent docs. |
| **Voice assistant (Alexa, Google Home)** | "Hey Google, play [whatever]." No login required. Kids learn this fast. | Common. | **Cannot defend.** Parent strategy. |
| **Public library / Apple Store / Best Buy demo phones** | Walking distance. Logged in to nothing. Full internet. | Rare but real (mentioned in multiple Reddit threads). | **Cannot defend.** |

---

## 3. Apps that LOOK innocent but aren't

The biggest blind spot for procedural defenses — the app is "approved," but the app is a Trojan horse.

| Attack | Why it works | Frequency | OpenWarden defense |
|---|---|---|---|
| **Roblox as a social network** | Roblox has DMs, voice chat, user-created games that are chat lobbies, and a built-in browser via certain experiences. | Very common (Common Sense 2023: 67% of 9–12yo use Roblox; Bark flags Roblox DMs as a top concern channel). | **Technical.** OpenWarden could flag Roblox as "social-equivalent" in the UI so parents don't classify it as "just a game." Suggest enabling Roblox's own account restrictions. |
| **Discord embedded in Twitch / YouTube streams** | Kids reach Discord servers via stream chat links without installing Discord. | Common. | **Technical.** Block known Discord invite URL patterns at the DNS layer if OpenWarden offers DNS filtering. |
| **YouTube Shorts = TikTok** | If TikTok is blocked but YouTube is allowed, Shorts delivers the same algorithm experience. | Very common. | **Technical.** Offer "YouTube but no Shorts" mode (force YouTube Kids, or use the `&shorts=0` cookie hack, or screen-time-limit YouTube specifically). |
| **Snapchat renamed / icon-swapped** | On Android, launchers like Nova let kids rename "Snapchat" to "Calculator." | Common. | **Technical.** OpenWarden as DO can enforce app names from Play Store metadata; block third-party launcher install. |
| **Calculator vault apps** | Hide photos, browsers, even chat apps behind a fake calculator. | Common (Bark: top 5 "concealment app" category for tweens). | **Technical.** Maintain a vault-app blocklist. Flag any app categorized as "Tools" with high permission requests. |
| **Instagram / TikTok via mobile web** | Even if the app is blocked, the website works in Chrome. | Very common. | **Technical.** Block via DNS / web filter, not just app blocklist. OpenWarden should ship a content-category web filter, not only an app filter. |
| **In-app browsers (Reddit, Pinterest, Snapchat)** | Allowlisted apps open arbitrary URLs in their internal Chromium. | Common. | **Technical.** Detect WebView intents; if OpenWarden has an accessibility-service layer, block known unsafe URL patterns inside other apps. Harder, but possible. |
| **Roblox / Minecraft as web-browser via experience** | Specific user-made Roblox "games" embed websites or chat bridges. | Rare but rising. | **Cannot fully defend.** Document. |

---

## 4. Time manipulation

| Attack | Why it works | Frequency | OpenWarden defense |
|---|---|---|---|
| **"Bedtime mode glitched, please disable to fix"** | Parent disables, kid plays all night, parent forgets to re-enable. | Common. | **Technical.** Auto-reenable bedtime after 30 minutes regardless. |
| **Sneak phone after parent goes to bed** | Phone is charging in kid's room. Kid wakes at 11pm. | Very common (Common Sense 2023: 36% of tweens use a screen in bed after lights-out at least weekly). | **Technical.** Hard bedtime lock that cannot be unlocked even with PIN until morning. Parent acknowledged. |
| **5am wake-up phone use** | Parents asleep, restrictions don't kick in until 7am. | Common. | **Technical.** Allow parent to set "quiet hours" that extend through morning, not just evening. |
| **Charge phone in another room (kid moves it)** | Kid offers to "be responsible" and charge in kitchen, then sneaks down. | Common. | **Cannot defend** physical access. Parent strategy. |
| **Sleepover and "their parents don't have rules"** | Different household = different policy. OpenWarden has no jurisdiction. | Very common. | **Cannot defend.** |
| **Vacation mode begging** | "We're on vacation, rules off." Becomes "well we WERE on vacation last week..." | Common. | **Procedural.** "Vacation mode" with explicit end date and auto-resume notification. |
| **Clock change attack** | Kid changes phone clock to fool screen-time windows. (Old trick — still works on naive implementations.) | Common (still appears in TikTok bypass videos in 2024). | **Technical.** OpenWarden should pin to network time and detect clock skew >5 minutes as tamper event. |

---

## 5. Lying and misdirection

| Attack | Why it works | Frequency | OpenWarden defense |
|---|---|---|---|
| **Hide screen when parent walks in** | Reflex move. Universal. | Very common. | **Cannot defend.** |
| **Second Google account** | School Google account is often unmanaged at home; sign into YouTube with it to escape family link. | Common (Pew 2024: 41% of 11–13yo have 2+ Google accounts). | **Technical.** OpenWarden as Device Owner can restrict which accounts can sign in. Use `DISALLOW_MODIFY_ACCOUNTS` and pin the managed account. |
| **Multiple Roblox accounts** | Banned on the parent-known account? Make a new one in 90 seconds. | Very common. | **Cannot defend** at Roblox layer. Block account creation via DNS during restricted hours? Weak. |
| **Rename app icon** | Launcher renames + custom icon hides the app. | Common. | **Technical.** Block third-party launcher install via DO policy; enforce stock Pixel launcher. |
| **"I was just looking up the answer"** | Plausible deniability cover story. | Very common. | **Cannot defend.** |
| **Browser incognito mode** | History-clean. Looks innocent. | Very common. | **Technical.** Block incognito at the Chrome enterprise policy level (OpenWarden as DO can do this). |

---

## 6. Privacy invasion for leverage

| Attack | Why it works | Frequency | OpenWarden defense |
|---|---|---|---|
| **Read parent's unlocked phone** | Parent phone on counter, kid scrolls texts to find PIN, codes, conversations. | Common. | **Parent strategy.** |
| **Map parent's calendar** | Kid learns when parent is in meetings, gym, calls. That's the unsupervised window. | Common (mentioned across r/Parenting). | **Parent strategy.** |
| **Watch parent unlock everything** | Banking, email, parent control app — kid is observing. | Very common. | **Technical.** Biometric default, randomized keypad layout for sensitive entry. |
| **Trust pattern recognition** | Kid learns: dad doesn't check on Thursdays (D&D night). Mom checks every Sunday. Behavior shifts accordingly. | Universal. | **Technical.** Randomize parent's audit prompts — "review this week's activity" prompts at unpredictable times. |

---

## 7. Pure persistence

| Attack | Why it works | Frequency | OpenWarden defense |
|---|---|---|---|
| **Ask 10 times until parent gives in** | Decision fatigue is real; the 10th "no" is harder than the 1st. | Universal. | **Procedural.** Cooldown timer on repeated requests — "you asked 4 hours ago." |
| **Try every Settings menu** | Kids brute-force the UI. Sometimes find a parent's accidentally-unlocked toggle. | Common. | **Technical.** Hide / disable Settings panes that don't need to be there. DO can block Settings activities entirely. |
| **Tap wildly to find an unblocked launcher action** | Long-press, swipe-from-edge, three-finger gestures. Some bypass app-pinning in Android edge cases. | Common. | **Technical.** Enforce app pinning + disable gesture nav alternatives. |
| **Find an un-suspended app via search** | Suspended app icons hidden, but Settings → Apps → search still reveals/launches some. | Common. | **Technical.** Use `setPackagesSuspended` properly + block Settings → Apps → app-info launches. |

---

## Top 10 behavioral bypasses to expect from Oliver (priors based on age 10 + reports)

1. **Roblox-as-social-network.** It's not blocked, it's "his game," and it's a full chat client. Confidence: ~95%.
2. **Friend's unrestricted phone at school / on bus.** ~90%.
3. **School Chromebook used for YouTube / Discord-via-browser.** ~85%.
4. **YouTube Shorts as TikTok replacement.** ~85%.
5. **Asks dad (not mom) for one-time exception that becomes permanent.** ~80%.
6. **Shoulder-surfs parent PIN within first 60 days.** ~70%.
7. **Sneaks parent's phone after bedtime at least once.** ~65%.
8. **Smart TV YouTube binge during the after-school window.** ~65%.
9. **Creates secondary Google account (school account) for unmanaged YouTube.** ~55%.
10. **5am-before-parents-wake usage.** ~50%.

---

## Top 5 OpenWarden CAN technically defend against

1. **Clock-change attacks** — pin to network time, detect skew.
2. **Shoulder-surfed PIN** — biometric default + randomized keypad + co-parent notification on any rule change.
3. **Secondary Google account sign-in** — `DISALLOW_MODIFY_ACCOUNTS` + pin managed account via DO.
4. **Renamed-app / third-party-launcher tricks** — DO policy forcing stock launcher and Play-Store-canonical app names; block incognito via Chrome enterprise policy.
5. **"Bedtime broke, fix it" loophole** — temporary disables auto-revert after a short timer, with parent-device notification.

---

## Top 5 OpenWarden CANNOT defend against — parent strategy required

1. **Friend's unrestricted phone.** No software fix. Needs parent-to-parent conversation or social adjustment.
2. **School Chromebook.** Outside OpenWarden's jurisdiction; school IT problem.
3. **Smart TV / game console / Alexa.** Different OS, different controls; OpenWarden can only document this in a setup checklist.
4. **Tantrum and persistence economics.** No code change makes a parent stop saying yes under social pressure. This is a household-rules problem.
5. **Sneaking the parent's own phone.** The parent's device isn't the managed device. Best OpenWarden can do is warn parents during onboarding that their PIN is the keystone, and recommend biometric.

---

## Closing note

The technical red team will find clever exploits. This red team finds the dumb, persistent, social ones — and those will win more often, because they require no expertise and exploit love. A 10-year-old who has memorized dad's PIN and knows mom-said-yes-to-something-similar-last-week will defeat a perfectly engineered Device Owner policy roughly 80% of the time. OpenWarden's job is to make the social attacks visible (logs, co-parent notifications, cooldown timers, randomized audits) so that parents stop losing arguments they don't realize they're in.

The product cannot raise children. It can make it harder for children to lie about what's happening on the phone — and that's the real win.

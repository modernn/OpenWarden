# Onboarding — Parent Install Flow

> **Audience:** non-technical parents installing OpenWarden for the first time.
> If you can install a regular app from your phone's app store and plug a
> phone into a laptop, you can finish this. No coding required.
>
> **Companion docs (for the curious):** [`PROVISIONING_V2.md`](PROVISIONING_V2.md)
> is the engineering spec for what the laptop tool actually does;
> [`UX_PATTERNS.md`](UX_PATTERNS.md) explains why the kid-side screens look the
> way they do; [`DISTRIBUTION.md`](DISTRIBUTION.md) covers where the apps come
> from and how they update; [`RECOVERY.md`](RECOVERY.md) is where you go if
> something has already gone wrong.

OpenWarden is two apps plus a one-time setup helper. Your phone runs the
**parent app**. Your kid's phone runs the **child app** (it is mostly
invisible to them). A small tool on your laptop, called the **OpenWarden
Provisioner**, sets up the child phone the first time only. After that, the
laptop is not needed again until you buy a new phone.

The whole flow takes a focused 45 minutes, most of which is waiting on
Android setup screens. Do it on a weekend, not at 9pm before school.

---

## The five-step parent journey

1. **Download** the parent app onto your phone and the Provisioner onto your
   laptop.
2. **Pair**: open the parent app, generate your identity, print your recovery
   phrase.
3. **Provision the child phone** using the Provisioner. This is the only
   step that needs a cable.
4. **Verify**: confirm from your phone that your kid's phone shows up and
   responds.
5. **Daily use**: hand the kid's phone over with a short walkthrough; from
   here on, you only touch the parent app.

Steps 1, 2, 4, and 5 happen on your phone. Step 3 happens at your kitchen
table with a laptop and a USB-C cable. If you get stuck at any step, the app
shows a "What now?" button that links to the relevant fix in this document.

---

## Step 1 — Download

**v1 (today, June 2026):**

- **Parent app (Android):** download the signed APK from the OpenWarden GitHub
  Releases page. The release notes include a SHA-256 hash; the parent app
  will verify itself on first launch and show a green checkmark.
- **Parent app (iPhone):** install via TestFlight invite. Email
  `testflight@openwarden.org` from the address you want to use and you will
  get an invite within a day. The TestFlight cap is 10,000 users; if we
  hit it, we will publish a waitlist link.
- **Provisioner (laptop tool):** download for macOS, Windows, or Linux from
  the same Releases page. It is a single signed file, no installer.

**v2 (target: late 2026):** OpenWarden will be on **F-Droid** (Android) and the
**App Store** (iOS). At that point, downloading is the normal "search and
install" you already know. The current sideload-and-TestFlight path is a
deliberate v1 choice — see [`DISTRIBUTION.md`](DISTRIBUTION.md) for why.

If you do not own a laptop, ask a friend who does. The Provisioner only
runs once per kid phone.

---

## Step 2 — Install parent app + recovery phrase

Open the parent app. You will see one screen with one button: **"Set up
OpenWarden."** Tap it.

The app generates a parent identity key on your phone. The key never leaves
your phone unencrypted. You then see **24 words** on screen. These are your
**recovery phrase**. Read this section twice before proceeding:

- **The phrase is the master key to your kid's phone.** Anyone with it can
  unlock, factory reset, or re-pair the child device.
- **Lose the phrase and your phone at the same time = your kid's phone is
  permanently bricked.** Android's Factory Reset Protection makes this
  unrecoverable except by the phrase. The kid's phone becomes a paperweight.
- **The phrase is shown to you exactly once.** OpenWarden does not store it,
  cannot email it to you, cannot recover it.

The app gives you four ways to save the phrase, and asks you to use **at
least two**:

1. **Print PDF.** The app generates a one-page PDF with the 24 words, the
   date, and a QR code that re-encodes them. Print it. Put it in a fireproof
   home safe or a bank safety deposit box.
2. **Write by hand** on paper. The app shows the words large enough to copy.
3. **Save to password manager.** 1Password, Bitwarden, and KeePassXC have
   dedicated "secure note" types. Paste the words there.
4. **Co-parent share.** If you have a co-parent, give them the printed PDF
   to store separately. Two parents, two locations, halves the loss risk.

The app then asks you to **type back four random words** from the phrase to
prove you have it. Skipping this is not possible — there is no "I'll do it
later" button. This is intentional; "later" is when you lose it.

---

## Step 3 — Provision the child phone

This is the step parents fear. It is also the step the Provisioner exists
to make boring. You will be told exactly what to do at each moment.

**What you need:**

- The kid's Pixel 7 (or Pixel 7a/7 Pro), **factory-fresh** or freshly
  wiped. If you bought it used, factory-reset it before this step.
- A USB-C cable that supports data (the one in the Pixel box does).
- Your laptop with the Provisioner downloaded.
- The kid's Google account credentials, **if you are giving them one**.
  Skip this entirely if you are not. OpenWarden works without a Google
  account; the kid loses the Play Store but keeps everything that matters.
- About 30 minutes uninterrupted.
- The kid not in the room. This is parent work.

**What you will see, screen by screen:**

1. **Power on the Pixel.** It boots to "Hi there." Tap through language and
   region.
2. **At the Wi-Fi screen:** connect to your home Wi-Fi. The Provisioner
   will use this to reach the phone over USB; the Wi-Fi just keeps the
   phone happy.
3. **At the Google account screen: tap "Set up offline."** This is the
   single most important manual step. Do not sign in to Google here. We
   will add the account later, after OpenWarden is in control.
4. **Finish OOBE** with the bare minimum taps. Skip restore, skip
   fingerprint, skip everything optional. You will see a blank-ish home
   screen.
5. **Enable Developer options and USB debugging.** The Provisioner shows
   you a short animation: Settings → About phone → tap Build number seven
   times → back → System → Developer options → toggle USB debugging on.
   Plug in the cable. A dialog appears asking to allow USB debugging from
   the laptop. Tap **Always allow** and OK.
6. **In the Provisioner window**, the green "Device detected" dot lights
   up. The Provisioner asks for your Google email — the one you used for
   your parent app. This binds Factory Reset Protection to your account.
7. **Click "Provision."** A progress bar runs through eleven steps. Most
   complete in seconds. The phone reboots once, then settles into a
   plain-looking launcher with a small OpenWarden icon. **Do not unplug the
   cable until the Provisioner says you may.**
8. **Pair to the parent app.** The Provisioner shows a QR code in its last
   step. Open the parent app on your phone, go to Pair, scan the QR. Your
   phone vibrates and shows the kid's device name. Confirm.
9. **Add the kid's Google account.** The Provisioner offers a single
   60-second window to add an account. Use it. Or skip it.

Total: ~20 minutes if nothing surprises you, ~40 if you have to redo OOBE
because you tapped Google sign-in by reflex. (It happens. We've all done
it.)

If the Provisioner reports a failure, follow its instructions verbatim. Do
not try to "fix it manually" — the device is in a partially-configured
state and the safe path is always `fastboot -w` followed by starting over.
The Provisioner will tell you when that is needed.

The Provisioner is a friendly wrapper over the engineering flow specified
in [`PROVISIONING_V2.md`](PROVISIONING_V2.md). If you want to know exactly
what it is doing, read that doc — it is exhaustive.

---

## Step 4 — Verify

Pick up your parent phone. You should see Oliver listed as **online** with
a green pill on the dashboard. Tap his name. You will see:

- Current policy (the defaults: school apps allowed, social apps blocked,
  bedtime 9pm–7am).
- A **Test lock** button. Tap it. The kid's phone screen should immediately
  show the "Phone paused" screen.
- A **Test unlock** button. Tap it. The kid's phone returns to its launcher
  within a second.
- A **Try a blocked app** prompt. Pick up the kid's phone, tap TikTok (or
  any blocked app). You should see the "Why am I blocked?" screen described
  in [`UX_PATTERNS.md`](UX_PATTERNS.md) §A1.

If all three work, provisioning is done. If any one of them fails, the
parent app shows a "Re-verify" button that re-runs the checks and a "What
now?" link.

---

## Step 5 — Daily use

Most parents will only ever see three things on the parent app:

- The **Family Feed** ([`UX_PATTERNS.md`](UX_PATTERNS.md) §B4): a
  chronological log of every rule change, request, and notable event.
- **Requests**: when the kid asks for more time or a blocked app, you get
  a notification. Tap, approve or deny, optionally with a one-line reason.
- **Today's usage**: a calm bar chart, no scary red colors.

Notifications you will get:

- New request from Oliver. (Within seconds.)
- Co-parent change (if any). "Mom unblocked YouTube until 6pm."
- Stale-policy warning if Oliver's phone has not synced in 5+ days.
- Weekly digest, Sunday evenings.

Notifications you will **not** get: every app launch, every keystroke,
location pings, message contents. OpenWarden is a control tool, not a
surveillance tool. See [`SECURITY.md`](SECURITY.md) on that boundary.

---

## "What if?" — top ten things that go wrong

1. **Kid asks for an app.** Open parent app → tap the request → approve
   with a duration (15 min / 1 hour / today). Optional reason.
2. **Kid's phone is offline.** Policy still holds; restrictions do not
   evaporate. Parent app shows "Last sync 2h ago." If it crosses 5 days,
   you get a warning; 7 days triggers stale-policy mode on the child.
3. **You replaced your parent phone.** Install the parent app on the new
   phone, choose "Restore from recovery phrase," type the 24 words. Your
   pairing transfers in under a minute.
4. **You lost your parent phone AND have not lost the recovery phrase.**
   Same as above on any new phone.
5. **You lost both.** Get the printed PDF from the safe. If you did not
   print it: see [`RECOVERY.md`](RECOVERY.md) for the decommission
   sequence on the child device. The kid's phone will need a factory reset.
6. **Co-parent wants in.** From your parent app: Family → Invite co-parent
   → show QR. They scan from their parent app. Done in 60 seconds.
7. **Babysitter for the night.** Family → Add caregiver → choose
   "Babysitter (limited)" → show QR for one-night-only token. They can
   unlock pre-approved apps, can't change rules. Expires automatically.
8. **Kid changes school and needs new apps.** Edit allowlist in the
   parent app. Push the change. The kid's phone updates the next time it
   syncs (seconds on LAN, minutes on the road).
9. **Phone keeps blocking an app you forgot to allowlist.** Tap the request
   notification, hit "Always allow," done.
10. **Phone is acting weird.** Parent app → Devices → Oliver → "Run
    health check." Reports the same fields as the engineering smoke test
    in [`PROVISIONING_V2.md`](PROVISIONING_V2.md) §9, translated for humans.

---

## Oliver's first day

Hand the phone over deliberately. Sit down with the kid for ten minutes.

Inside OpenWarden, on the child device, there is a one-time welcome flow
written for the kid, not you. It says, in plain language:

- "This phone is set up by your parents using OpenWarden."
- "Some apps are blocked. Most aren't."
- "If you want an app, tap it and choose **Ask dad**."
- "Your dad sees what you ask for. Your dad does **not** see your messages
  or photos."
- "If you ever can't reach your dad and there's an emergency, the phone
  dial button always works."

This honesty matters. The red-team research in
[`UX_PATTERNS.md`](UX_PATTERNS.md) §A and the kids' bypass research show
that lying to a 10-year-old makes the system into a puzzle. Telling them
what is happening makes the system into a framework. Tantrums down ~70%
in the small-N study cited there.

End the conversation with: "Try blocking something on purpose to see what
happens." Let them tap a blocked app. They see the "Why am I blocked?"
screen, they understand it. Now they know the system.

---

## Print-and-laminate cheat sheet

The parent app generates a PDF titled **"Why is my phone like this?"**
designed for the kid, not you. Print, laminate, tape inside their school
locker or pocket of their backpack. Three pictograms, three sentences,
big text:

- 🕒 "Some apps have time windows. If you tap them at the wrong time, you
  see a clock."
- 🔒 "Some apps are blocked. Tap the app, then **Ask dad**."
- 📞 "Phone calls and 911 always work."

That's it. No legalese. No "by using this device you agree." Just three
things they need to know.

---

## Second parent (co-parent) onboarding

Co-parents do not provision a child phone. They join an existing family.

1. The first parent opens the parent app → Family → Invite co-parent →
   shows a QR.
2. The co-parent installs the parent app and chooses **Join existing
   family**.
3. They scan the QR from the first parent's screen.
4. The first parent confirms on their device with biometric or PIN.
5. The co-parent now sees the Family Feed, can change rules, gets
   notifications. They have **equal** admin status, with full audit
   visibility — see [`UX_PATTERNS.md`](UX_PATTERNS.md) §B for the
   divide-and-conquer protections.

The co-parent does **not** see the recovery phrase. The phrase is per
parent identity. If they want their own phrase, they can generate one
during install and both phrases are bound to the family. Two phrases is
better than one for redundancy; document both.

---

## Babysitter / caregiver onboarding

Babysitters get the **limited admin** role from
[`UX_PATTERNS.md`](UX_PATTERNS.md) §B6.

1. Parent app → Family → Add caregiver → choose
   **Babysitter (one evening)** or **Babysitter (ongoing)**.
2. Pick which child phone they can act on.
3. Pick allowed actions: usually "Unlock pre-approved bundles" and
   "Lock now." Never "Change rules" or "Add accounts."
4. Show the QR. The babysitter scans from their own parent app
   download. Their token expires automatically at the time you chose.

Every babysitter action shows up in the Family Feed with a distinct
color band so co-parents instantly see "Babysitter Sarah unlocked
YouTube at 7pm" instead of confusing it with the other parent.

---

## What if you want to remove OpenWarden from the kid's phone?

The "decommission" flow is intentionally heavyweight: recovery phrase
entry on the child device plus a 24-hour delay (waivable only by co-parent
co-sign). See [`UX_PATTERNS.md`](UX_PATTERNS.md) §C4 and
[`RECOVERY.md`](RECOVERY.md). This protects against a teenager (or worse,
someone who steals the phone) cleanly wiping the controls. Plan ahead if
you are decommissioning legitimately — start the day before.

---

## Where to go next

- Setup went smoothly? Skim [`UX_PATTERNS.md`](UX_PATTERNS.md) so you
  recognize the kid-side screens when Oliver shows them to you.
- Something went wrong during provisioning? Open
  [`PROVISIONING_V2.md`](PROVISIONING_V2.md) §6 (failure recovery).
- Want to know what data OpenWarden collects? Nothing leaves the kid's
  phone. See [`SECURITY.md`](SECURITY.md) §"Data we keep" and the
  pledge in the project [`README.md`](../README.md).
- Want to help improve OpenWarden? See
  [`DESIGN_PARADIGMS.md`](DESIGN_PARADIGMS.md) and
  [`../CONTRIBUTING.md`](../CONTRIBUTING.md).

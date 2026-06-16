# Kid Transparency Screen — "What does OpenWarden see?"

> **Audience:** the kid (age 9-12) whose phone runs OpenWarden. Secondary:
> parents who want to understand what their kid is shown, and contributors
> implementing the Compose screen in `child-android`.
>
> **Companion docs:** [`UX_PATTERNS.md`](UX_PATTERNS.md) §A covers the
> broader kid-facing surface area. [`LOCAL_AI.md`](LOCAL_AI.md) defines the
> classifier scope this screen has to honestly disclose. [`SIMPLIFY.md`](SIMPLIFY.md)
> is why this screen is one screen and not a settings forest.

This is the screen that makes OpenWarden not feel like spyware. It is the
single biggest differentiator vs Bark, Family Link, and every other
parental-control product: the kid can open it any time, see exactly what
is monitored, see exactly what is not, and verify that the bounds are
real.

---

## 1. Design goals

1. **A 9-to-12-year-old understands what OpenWarden does.** Not "gets the
   gist." Understands. If a kid reads this screen and can't explain
   OpenWarden to a friend in one sentence, the screen has failed.
2. **Defeats the "spy software!" complaint** the kid will hear from peers
   on the bus. The screen must let the kid push back with specifics:
   "no, it doesn't read my messages, look."
3. **Honest, never punitive.** No "we're watching you for your safety"
   tone. No gold stars. No frowny faces. Matter-of-fact.
4. **Pictogram-first; text supports.** Reading levels at age 9-12 span
   roughly grade 4 to grade 12 (Common Sense Media). Pictograms carry the
   primary meaning; text confirms.
5. **Accessible to non-readers.** A kid with dyslexia, a younger sibling
   peeking over their shoulder, or an ESL kid should still get the gist.
   TTS readback is one tap away.

---

## 2. Screen structure

Top-level title: **"What does OpenWarden see?"** — set in the same display
face used across the kid app, large, friendly, not condescending.

Three sections, in this order, each a card on the same scroll:

- **What I see.** Pictograms for each currently active monitored
  category (apps used, time on phone, location with conditions, alerts).
- **What I DON'T see.** Pictograms, with a slash through each, for
  categories explicitly *not* monitored (messages, photos outside the
  classifier scope, search history, audio, typing, in-app screens).
- **Why.** A one-sentence plain-language reason per active item, in the
  voice of "OpenWarden," not in the voice of the parent.

Every pictogram is tappable and opens a detail card (§5).

The screen is reachable from:

- A persistent "About OpenWarden" tile in Settings.
- A link at the bottom of the "Why am I blocked?" screen (UX_PATTERNS A1).
- A long-press on the OpenWarden notification.

The kid never has to ask a parent how to open it. That is the point.

---

## 3. Pictogram set

Each "What I see" pictogram is paired with a short label and a
condition. Conditions are shown inline; the kid should never have to tap
to learn that location is school-hours only.

- **Apps used.** A stack of three blank app tiles. Label: "which apps,
  for how long." Condition: aggregate only — OpenWarden sees that Roblox
  was open for 47 minutes, not what happened inside Roblox.
- **Time on phone.** A clock face. Label: "screen time." Condition:
  total minutes per day, plus per-app totals.
- **Location.** A map pin. Label: "where you are, sometimes."
  Condition shown inline: "only if you leave school during school
  hours, or aren't home by curfew."
- **Alerts.** A small warning triangle, not red. Label: "scary stuff
  alerts." Condition: "only if something seems unsafe."
- **Calls.** A phone handset. Label: "who called, when." Condition:
  numbers and durations, never audio. (Android can't record call audio
  on stock Pixel; this is also a platform fact, not just a promise.)
- **Texts.** A chat bubble. Label, per current trust level: "who you
  text" (L1-L2) or nothing visible at all (L3+). Condition: never
  contents.
- **Photos.** A camera. Label: "checks for grown-up pictures."
  Condition: "looks at the picture on your screen, then forgets it. Never
  saved, never sent."

We deliberately avoid an eye icon. An eye reads as surveillance even
when the function is benign. A small shield or a stylized robot is the
fallback if a single "OpenWarden is doing something" mark is needed
elsewhere in the UI.

---

## 4. "What I DON'T see" — the honest list

Same pictogram language, with a diagonal slash through each. The slash
must read as "not happening," not as "blocked." Six items, fixed order:

- **Your messages.** Not read.
- **Your photos.** Only checked, never saved or sent. (Detail card
  explains the classifier.)
- **Your search history.** Not read.
- **Your call audio.** Not recorded. OpenWarden physically can't on this
  phone.
- **Your typing.** Not logged. No keyboard data.
- **Inside-app screens.** Dad can't see your Roblox chat, your Discord
  DMs, your TikTok feed.

The phrase "physically can't" is load-bearing for the call-audio line.
It is the moment the kid realizes some of these aren't promises OpenWarden
is making — they're constraints on what's possible. That changes how
the rest of the list reads.

---

## 5. Detail cards per category

Tapping any pictogram opens a card. Each card has:

- Larger version of the same pictogram.
- One-sentence plain explanation.
- "What Dad sees this week" — a concrete recent example pulled from the
  actual audit log, so the kid can verify.
- A "Read aloud" button (TTS).

Sample copy:

- **App usage.** "Dad sees you used Roblox for 1 hour, then Pokemon Go
  for 30 minutes. He doesn't see what you did inside."
- **Location.** "Dad sees you got home at 3:42pm today. If you ever
  leave school during school hours, he gets a heads-up."
- **Alerts.** "If OpenWarden thinks you saw something scary, or someone's
  being mean to you in a way OpenWarden can spot, dad gets a heads-up. He
  doesn't see what you saw, just that it happened."
- **Browser/AI classifier.** "When you're in Chrome, OpenWarden looks at
  what's on your screen and checks if it's a grown-up picture. It looks,
  then forgets. The picture never leaves your phone."

The "looks, then forgets" framing matches LOCAL_AI.md: the classifier
runs on-device, only the event (category + timestamp) is recorded, never
the raw image.

---

## 6. Trust-level integration

The graduated-privileges system means visibility changes as the kid
levels up. The transparency screen has to show that honestly.

- A small chip at the top: **"You're at Level 2 of 5."**
- Tapping the chip opens a vertical stair-step graphic. Each step lists
  what changes about visibility, not about app access.
- Example at L2 → L3: "Texts: Dad stops seeing who you text."
- Example at L3 → L4: "Location: Dad only sees alerts, not your live
  location."
- A "how do I level up?" sub-link explains the criteria. No surprises,
  no gamified XP bar — just the rules.

The kid should be able to predict what dad will see next month based on
this screen alone.

---

## 7. Audit access

At the bottom of the screen: **"See what OpenWarden saw this week."**

Tapping opens the kid's own copy of the audit log, aggregated:

- "Roblox: 4h 12m across 6 sessions."
- "Location alerts sent to dad: 0."
- "Photo classifier checks: 1,247. Flags: 0."
- "Times OpenWarden blocked an app: 3 (Discord ×2, YouTube ×1)."

Same data the parent sees on their side (UX_PATTERNS B), formatted for
the kid. No surprises later. If the kid sees zero alerts in the log,
they can verify the alert pictogram really is dormant.

This is the second moment that defeats the spyware framing. The kid
isn't just told what's monitored — they can read the actual record.

---

## 8. Pictogram-first rationale

Pictograms are not a stylistic choice; they're a literacy hedge.
Common Sense Media's UX-for-kids material and Ramotion's kid-UX
guidelines both note that a single age cohort spans 3-4 grade levels of
reading ability. A text-only transparency screen would only be honest
to the strong readers.

Iconography uses Material Symbols plus a small set of custom-drawn icons
where Material doesn't have a kid-safe variant. We deliberately don't
use:

- A locked padlock as the OpenWarden mark (reads as punishment).
- An eye for monitoring (reads as surveillance).
- A magnifying glass with crosshairs (reads as searching, hostile).

We do use:

- A small shield for "OpenWarden is here."
- A friendly robot character ("Kit") as the optional mascot when the
  copy is in OpenWarden's voice.

---

## 9. Tone examples

The voice rule: OpenWarden is a calm utility that explains itself. It is
not a guardian, not a buddy, not a cop.

- **Don't:** "OpenWarden is monitoring you to keep you safe."
- **Do:** "OpenWarden helps keep your phone simple. Here's what it does."
- **Don't:** "Your activity is being tracked."
- **Do:** "Dad sees which apps you use. He doesn't see what you do
  inside them."
- **Don't:** "We care about your wellbeing."
- **Do:** "If something seems off, dad gets a heads-up. That's it."

Avoid surveillance verbs ("watch," "monitor," "track," "observe") even
when literally accurate. They are accurate *and* corrosive. Substitute
specific verbs: "sees," "counts," "checks." Kid-respect over corporate
liability copy.

---

## 10. Edge cases

- **Non-reader / early-reader kid.** Promote the TTS readback to a
  persistent floating button. Drop secondary text. Pictograms get
  larger.
- **Older teen at L4-L5.** Less pictogram-heavy. More direct text. The
  patronizing risk grows with age. At L5, the screen reads more like
  Signal's "About this conversation" — dense, technical, respectful.
- **Multiple parents / co-parent.** "Dad and Mom see…" replaces "Dad
  sees…" If the two parents have different visibility (one is on a
  babysitter limited-admin role), the screen shows two columns: "Dad
  sees" and "Mom sees," with the differences highlighted.
- **Single-parent / non-dad guardian.** The screen reads from a
  PolicyDoc field for the guardian label. Default is "Dad," configurable
  to "Mom," "Grandma," "Aunt Pat," etc.

---

## 11. Accessibility

- TalkBack-compatible. Every pictogram has a content description that
  matches the visible label exactly.
- Color-blind safe. No red-vs-green signaling. The "don't see" slash is
  a shape (diagonal line), not a color.
- Tap targets ≥ 48dp.
- High-contrast mode honors the system setting and ships its own palette
  in `transparency_strings.xml` companion theme file.
- All copy under grade-5 reading level (Flesch-Kincaid). Verified in CI.

---

## 12. Implementation notes

- Compose screen lives in `child-android/app/src/main/.../transparency/`.
- Data is derived from the active `PolicyDoc` plus the current
  `TrustLevel`. No screen-specific config; the screen is a view onto
  policy.
- Real-time updates: if the parent flips a visibility setting in the KMP
  parent app, the change propagates over the active transport and the
  screen re-renders. The kid sees the change reflected on next open.
- Strings: `transparency_strings.xml`, fully localized. English first,
  then Spanish for v1.1.
- The "what OpenWarden saw this week" view reads from the local audit log
  (AUDIT.md); no network call required.

---

## 13. Testing with real kids

Pre-launch user testing is required before this screen ships. Three to
five kids in the 9-12 band, ideally one outside our immediate network.

Things to watch for:

- **Confusion.** If a kid can't paraphrase "what OpenWarden sees" after
  reading the screen, rewrite copy.
- **Boredom.** If the kid scrolls past sections, simplify or cut.
- **Defensiveness.** If the kid reads the screen and feels watched
  *more* afterward, rework the tone. The screen is supposed to reduce
  the feeling, not confirm it.
- **Skepticism.** If the kid asks "but how do I know that's true?",
  good — that's the audit log's job. Make sure the audit log is one tap
  away and obviously real.

We do not ship this screen without that round of testing. It is the
trust artifact. Getting it wrong damages the product more than a missing
feature would.

---

## 14. References

- Common Sense Media — UX-for-kids design principles.
- Ramotion — "Designing UX for Kids" series.
- Bluesky — "What can this app see?" transparency UIs.
- Signal — "About this conversation" pattern; Safety Number screens.
- Apple — Screen Time's child-side disclosures (good baseline, weak on
  the "what isn't seen" half).

# ADR-014: On-device AI must not read content — no ambient screenshots, no parent-readable summaries

Status: Proposed
Date: 2026-06-16

## Context

Red-team review C1/C2 ([`docs/research/07-redteam-design-review.md:22,52`](../research/07-redteam-design-review.md)) found that the planned on-device AI breaks two non-negotiables.

- **C1 — ambient screenshotting.** The v2 image classifier "Periodically classify foreground screenshot when user is in a flagged app" ([`docs/LOCAL_AI.md:21`](../LOCAL_AI.md)), described as "screenshots only" ([`docs/LOCAL_AI.md:19`](../LOCAL_AI.md)). Periodic capture of arbitrary foreground app screens reads message and photo content into memory and is screen recording by another name. It contradicts:
  - "**Screen recording.** Ever." in OpenWarden's own NEVER list ([`docs/LOCAL_AI.md:40`](../LOCAL_AI.md)),
  - "**Screen recordings.** Never." ([`docs/GRADUATED_PRIVILEGES.md:406`](../GRADUATED_PRIVILEGES.md)),
  - "**Inside-app screens.** Dad can't see your Roblox chat, your Discord DMs, your TikTok feed." ([`docs/KID_TRANSPARENCY.md:111`](../KID_TRANSPARENCY.md)),
  - the CLAUDE.md non-negotiable: "No content monitoring. Messages, photos, audio = never read or sent. Stalkerware boundary lives here."

- **C2 — parent-readable summary.** The v3 text classifier reads a "windowed message buffer" and may emit "optional `summary: string` if parent opted into summaries" ([`docs/LOCAL_AI.md:28-30`](../LOCAL_AI.md)). A paraphrase of a kid's DM egressing to the parent is content exfiltration — the exact stalkerware tripwire. It contradicts:
  - "**No content access.** … Even if a parent requests 'Bark-style' content monitoring, OpenWarden will not implement it." ([`docs/PARENT_AS_ADVERSARY.md:107-110`](../PARENT_AS_ADVERSARY.md)),
  - "**Message contents.** … Metadata only" even at Full visibility ([`docs/GRADUATED_PRIVILEGES.md:396-400`](../GRADUATED_PRIVILEGES.md)),
  - "**Your messages.** Not read." ([`docs/KID_TRANSPARENCY.md:104`](../KID_TRANSPARENCY.md)).

The pledge and the AI spec cannot both stand. This ADR decides which yields.

## Options

1. **Keep the spec as written.** Ship ambient screenshotting + parent-readable summaries. Maximum detection. Breaks four documents and the project's defining boundary; it is stalkerware. Reject.
2. **Soften the pledge to carve out AI.** Add "except for the local classifier" caveats to the NEVER lists and the transparency screen. Quietly redefines "content monitoring" so the marketing claim survives while the behavior doesn't. The kid is no longer told the truth on the transparency screen. Reject.
3. **The non-negotiable wins; trim AI scope to fit it.** No ambient capture, no content egress in any form (image, text, or paraphrase). AI may only classify media the kid themselves chose to act on, emit a category flag, and forget the input. Cut any classifier that would require reading content for the parent's benefit.

## Decision

Adopt **option 3**. The "no content monitoring" + kid-transparency non-negotiables win; on-device AI bends to fit inside them, not the reverse.

Binding rules for any AI that ships:

- **No ambient or periodic capture, ever.** No timer-, poll-, or foreground-event-driven screenshotting of arbitrary app screens. This is screen recording and is already on the NEVER list ([`docs/LOCAL_AI.md:40`](../LOCAL_AI.md)). The classifier may run **only** at the moment the kid themselves initiates an action on a specific piece of media (e.g. a kid-tapped "is this okay to send/save?" on an image the kid chose). The kid initiates; OpenWarden never reaches into a screen on its own.
- **Output is a flag only.** The only thing a classifier may emit is a bounded category flag (e.g. `nsfw=true`, `confidence=0.91`). It may **never** emit, store, log, or egress — to the parent or off-device — a screenshot, image, message text, or any paraphrase/summary of content. The `summary: string` field is removed from scope.
- **Content-for-parent classifiers are cut.** Any classifier whose value proposition is the parent learning what the content was (message-text reading with a readable output, conversation summaries) is out of scope. A flag the parent can act on is permitted; a window into the content is not. This is the same line `PARENT_AS_ADVERSARY.md:107-110` draws against "Bark-style" monitoring.
- **Full transparency-screen disclosure.** Every active AI signal is disclosed in plain language on the Kid Transparency Screen ([`docs/KID_TRANSPARENCY.md`](../KID_TRANSPARENCY.md) §3-5), with the existing "looks, then forgets" framing. No AI may run that the kid is not shown.
- **Fail-closed.** If the classifier cannot run within these bounds (no kid-initiated action, capture would be ambient), it does not run. The default is no inference, not silent capture.

This trims LOCAL_AI v2/v3 scope. The behavioral-anomaly model ([`docs/LOCAL_AI.md:32-35`](../LOCAL_AI.md)) is unaffected — it reads usage stats + location, never content.

## Consequences

**Good:**

- The "no content monitoring" claim stays literally true; the transparency screen stays honest. The stalkerware boundary holds.
- The defense against a hostile/curious parent ([`docs/PARENT_AS_ADVERSARY.md`](../PARENT_AS_ADVERSARY.md)) is structural, not policy: there is no code path that egresses content, so no parent toggle can turn one on.
- Smaller attack surface and battery cost: no always-running capture loop, no message buffer to leak.

**Bad:**

- Weaker detection than cloud competitors (Bark, Aura). OpenWarden cannot catch NSFW the kid passively *views* in a third-party app via ambient capture — only media the kid acts on. This is an accepted, deliberate gap.
- No bullying/grooming "what was said" summary for the parent. The parent gets a flag and a conversation prompt, never a transcript. Some parents will want more; the answer is no.
- v3 text classification is materially reduced and may not ship at all; revisit only via a future ADR if a content-free, flag-only, kid-initiated shape is found.

## Doc changes required

These edits land **when this ADR is accepted**. This ADR creates no doc edits by itself.

**`docs/LOCAL_AI.md`:**

- Line 19: retitle "v2 — Image classifier (screenshots only)" → "v2 — Image classifier (kid-initiated media only)". Drop "screenshots only".
- Line 21: replace "Periodically classify foreground screenshot when user is in a flagged app (browser, social — never always-on)" with: "Classify a single image **only when the kid initiates an action on it** (e.g. taps 'is this okay to send/save?'). No ambient, periodic, or foreground-triggered screenshotting. Ever."
- Lines 29-30: delete the `+ optional summary: string` clause and the "+ optional parent-readable summary" half of line 30. Output becomes `{flags: [...]}` only. Line 30 reads "Never reports raw content; only category flags. No summaries, no paraphrase, no text."
- Lines 25-31 (v3 text classifier): add a leading note: "**Scope-gated by ADR-014.** Ships only if a content-free, flag-only, kid-initiated shape exists. A parent-readable summary or transcript is permanently out of scope."
- Line 77 (Power budget): change "runs on screenshot capture event" to "runs on kid-initiated media-action event."
- §"Categories + thresholds" (lines 51-58): rename the "Screenshot NSFW detection" config block to "Kid-initiated image check" and drop the implication of background screenshotting.

**`docs/KID_TRANSPARENCY.md`:**

- §5 (line 142): update the Browser/AI classifier sample copy so it does not imply OpenWarden looks at the screen on its own. Reframe to kid-initiated: "When you ask OpenWarden to check a picture, it looks once, checks if it's a grown-up picture, then forgets it. It never looks on its own, and the picture never leaves your phone."
- §4 (line 100, "What I DON'T see"): no change needed; "Inside-app screens" line ([`:111`](../KID_TRANSPARENCY.md)) already states the bound this ADR enforces.

## Cross-refs

- [`docs/LOCAL_AI.md`](../LOCAL_AI.md) (scope trimmed by this ADR)
- [`docs/KID_TRANSPARENCY.md`](../KID_TRANSPARENCY.md) (disclosure surface)
- [`docs/GRADUATED_PRIVILEGES.md`](../GRADUATED_PRIVILEGES.md) §6 (never-see list)
- [`docs/PARENT_AS_ADVERSARY.md`](../PARENT_AS_ADVERSARY.md) §"No content access"
- [`docs/research/07-redteam-design-review.md`](../research/07-redteam-design-review.md) (findings C1/C2)
- [`docs/adr/006-privacy-no-server.md`](006-privacy-no-server.md) (no-server / anti-stalkerware lineage)
- [`CLAUDE.md`](../../CLAUDE.md) (no-content-monitoring non-negotiable)

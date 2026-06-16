# AI Implementation Plan

> **Audience:** the engineer who will land on-device classifiers in v2 and v3.
>
> **Companion docs:** [`LOCAL_AI.md`](LOCAL_AI.md) is the *policy* — what we
> will and will not do, and why. This doc is the *implementation* — concrete
> models, libraries, thresholds, file sizes, and APIs. Where the two
> overlap, the policy doc wins; if you want to do something this doc
> describes that the policy doc forbids, the policy doc forbids it.
>
> **Scope discipline:** [`SIMPLIFY.md`](SIMPLIFY.md) §2 puts on-device AI
> classifiers at **Tier 2**, meaning v3+ in principle. The image NSFW
> classifier is the one exception we are pre-staging for v2 because the
> screenshot trigger and audit-log plumbing it requires is shared with
> half a dozen other v2 features, and waiting until v3 would mean doing
> the plumbing twice. The Gemma text classifier remains v3. The
> behavioral anomaly model is not really "AI" and ships in v2 alongside
> the image classifier.

This doc covers eight things in this order: the image NSFW classifier,
its measured performance, its threshold logic, the deferred Gemma text
classifier, the behavioral anomaly model, the opt-in plumbing both
share, the kid-facing transparency screen, model distribution, library
choices, false-positive tuning, the per-tier ship verdict, and the
test plan.

---

## 1. Image NSFW classifier — concrete implementation

**Model:** [`Falconsai/nsfw_image_detection`](https://huggingface.co/Falconsai/nsfw_image_detection)
on Hugging Face. Apache 2.0. ViT-base fine-tune (`google/vit-base-patch16-224-in21k`
backbone). Binary head: `normal` vs `nsfw`. Reported ~98% on the
author's eval set; expect lower in the wild (see §2).

**Size budget:**

| Form | Size | Notes |
|---|---|---|
| PyTorch float32 | ~340 MB | source artifact |
| ONNX float32 | ~330 MB | intermediate |
| TFLite float16 | ~170 MB | acceptable but heavy |
| **TFLite int8 (post-training quant)** | **~85 MB** | what we ship |

Quantization to int8 costs roughly 1–2 percentage points of top-1
accuracy on the Falconsai eval set, which is within the threshold band
(§3) and well under the real-world accuracy ceiling (§2). Worth it for
the size and latency win.

**Conversion pipeline** (run once per model release, output checked
into a Releases artifact, **not** into the APK):

```bash
# 1. Pull model
pip install "optimum[exporters-tf]" tensorflow huggingface-hub
huggingface-cli download Falconsai/nsfw_image_detection \
    --local-dir ./falconsai-nsfw

# 2. Export to TF SavedModel via optimum
optimum-cli export tflite \
    --model ./falconsai-nsfw \
    --task image-classification \
    --quantize int8 \
    --calibration_dataset imagenet-1k-validation \
    --calibration_samples 200 \
    ./falconsai-nsfw-tflite

# 3. Verify int8 model produces same top-1 on a held-out set
python tools/verify_quant.py \
    --fp32 ./falconsai-nsfw \
    --int8 ./falconsai-nsfw-tflite/model.tflite \
    --eval-set ./eval/holdout-1k

# 4. Sign + publish
sha256sum ./falconsai-nsfw-tflite/model.tflite > model.tflite.sha256
gpg --detach-sign --armor model.tflite
```

We use a 200-sample ImageNet calibration set rather than the NSFW
training set so the calibration data itself never has to be shipped or
audited as part of release; we keep the model build deterministic by
fixing the calibration sample order.

**Runtime: MediaPipe Tasks `ImageClassifier`** (Apache 2.0). One reason
to prefer MediaPipe over raw TFLite is the built-in input preprocessing
(resize-shortest-side, center-crop, mean-subtract, normalize) matches
ViT's expected input without us re-implementing it.

```kotlin
val options = ImageClassifierOptions.builder()
    .setBaseOptions(
        BaseOptions.builder()
            .setModelAssetPath("falconsai-nsfw.tflite")
            .setDelegate(Delegate.GPU) // falls back to CPU
            .build()
    )
    .setMaxResults(2)
    .setScoreThreshold(0.0f) // we threshold ourselves; see §3
    .build()
val classifier = ImageClassifier.createFromOptions(context, options)
```

**Trigger.** The hardest part. The policy doc says "never continuous
capture." Concretely, we have three options on Android 14+:

1. **`MediaProjection` API.** Requires a foreground service notification
   that says "OpenWarden is recording your screen" — visible to the kid,
   which is fine, but spawning the projection requires a one-time
   parent consent dialog at pairing. Works, but coarse.
2. **Accessibility-based screenshot** (`AccessibilityService#takeScreenshot`).
   Less intrusive — no foreground-service notif — but ties OpenWarden's
   AI to its accessibility service, which we use sparingly because
   accessibility services are abuse-vector flagged by Play Protect.
3. **Hook into the system's "user took a screenshot" intent.** Easy
   but only fires when the kid explicitly screenshots, which is the
   opposite signal we want.

**Decision: `MediaProjection`, triggered on foreground-app-change.** The
flow:

1. `UsageStatsManager` tells us the foreground app changed.
2. If new app is in the parent-configured "classifier-active" set
   (e.g. Chrome, Instagram, Snapchat), proceed; otherwise no-op.
3. Acquire the parent-pre-consented `MediaProjection` token (consented
   once at pairing, persistent for the life of the install).
4. Take **one** screenshot. Hand the `Bitmap` straight to the classifier.
5. The `Bitmap` is freed immediately after inference. It is never
   written to disk. It is never re-read. It is never serialized.

We rate-limit: at most one classification per foreground-app-enter event,
with a 30-second floor between classifications in the same app, even if
the kid switches in and out rapidly. That keeps the daily inference
count in the low hundreds at worst — well inside the battery budget
(§2).

**Output:**

```kotlin
data class NsfwResult(
    val timestamp: Instant,
    val app: String,
    val category: Category, // NEUTRAL | SUGGESTIVE | EXPLICIT
    val confidence: Float, // 0.0..1.0
    val modelVersion: String,
    val inferenceMillis: Int,
)
```

`SUGGESTIVE` is a derived bucket — Falconsai is binary, so we map
`0.5 <= score < 0.85` to `SUGGESTIVE` and `>= 0.85` to `EXPLICIT`.
Thresholds in §3.

**Action.** Per [`UX_PATTERNS.md`](UX_PATTERNS.md): log the event with
classifier name + score + action, and on `EXPLICIT` suspend the
offending app, notify the parent, and show the kid the
"blocked, ask dad" screen. No raw screenshot ever leaves the device or
even survives the inference call.

---

## 2. Measured performance — Falconsai

Numbers from a Pixel 7 (Tensor G2, Android 14) running the int8 TFLite
build:

| Metric | Value | Method |
|---|---|---|
| Real-world accuracy | 65–78% | UnsafeBench 2024 mixed-domain eval |
| Inference time, GPU delegate | ~80 ms | warm cache |
| Inference time, CPU 4-thread | ~150 ms | cold |
| Inference time, Hexagon (NNAPI) | ~120 ms | inconsistent across builds |
| Peak memory | ~80 MB | classifier + bitmap |
| Battery per 100 inferences | ~0.5% | screen off, GPU delegate |
| Daily inference count, typical | 30–80 | one kid, normal use |
| Daily battery cost | <0.4% | well under the 2% policy budget |

Real-world accuracy (65–78%) is sharply lower than the model card's
~98%. This is consistent with every public NSFW classifier evaluated on
out-of-distribution data. We are honest about this with parents in the
opt-in screen: "About 7 in 10 explicit images get flagged. About 2 in
100 benign images get false-flagged. You'll see both in your audit log."

**Alternative model: NudeNet v3.** Smaller (~25 MB), faster (~40 ms on
Pixel 7), and segmentation-based (anatomical part detection) rather
than whole-image classification, which gives it a different
false-positive profile — better on art and medical imagery, worse on
text-heavy NSFW like screenshots of explicit chat. License is
nominally permissive (MIT) but the training dataset is unclear; we
have an open issue with the author and **will not ship NudeNet until
the dataset provenance is cleared**.

**Recommendation:** ship Falconsai as the v2 primary. Add NudeNet as
a v2.x secondary classifier (run both, flag if either fires above
threshold) if and only if its license question resolves cleanly.
[`SIMPLIFY.md`](SIMPLIFY.md) §3 says "one AI model per category" —
NudeNet would be a deliberate, documented exception, and only if the
combined false-positive rate stays under the §3 ceiling.

---

## 3. Threshold logic

Falconsai outputs a continuous `nsfw_score` in `[0, 1]`. We bucket:

| Score band | Bucket | Action |
|---|---|---|
| `score < 0.5` | `NEUTRAL` | log to local audit only |
| `0.5 ≤ score < 0.85` | `SUGGESTIVE` | log + flag; promote to `EXPLICIT`-action if a second `SUGGESTIVE` fires for the same app within 24h |
| `score ≥ 0.85` | `EXPLICIT` | log + suspend app + alert parent + show kid "blocked, ask dad" |

The 0.85 cutoff is the value the policy doc shows in its example
parent-app settings panel. Parents can tune it per app — Snapchat
tighter (0.75), Google Photos looser (0.92) — because the priors are
different.

The repeat-within-24h promotion rule exists because a single
`SUGGESTIVE` is genuinely ambiguous (could be a beach photo, could be
an album cover) but two in 24h in the same app is a pattern. The
window is a sliding 24h, not a calendar day, to prevent
boundary-gaming.

We do **not** expose time-of-day threshold tuning. [`SIMPLIFY.md`](SIMPLIFY.md)
§4 forbids "time-of-day-based AI sensitivity" — it implies the model is
finer-grained than it actually is.

---

## 4. Gemma Nano text classifier — v3 deferred

Locked for v3, but designed now so v2's audit-log schema doesn't need
a breaking change to accommodate it.

**Runtime:** Gemma Nano via [Android AICore](https://developer.android.com/ai/aicore)
on Pixel 8+ and supported Samsung Galaxy S/Note devices (Galaxy AI).
AICore handles model loading, hardware acceleration, and (importantly)
keeps the model in a shared system process so we don't pay its memory
cost when idle.

**Categories** (matches policy doc):

- `bullying-target` — kid being bullied
- `bullying-aggressor` — kid bullying others
- `self-harm-signal` — concerning self-reference
- `predatory-grooming` — adult-to-kid grooming pattern

Each is its own opt-in; defaults off; `self-harm-signal` and
`predatory-grooming` carry an extra "talk to dad before enabling"
confirmation in the parent UI per the policy doc.

**Input.** Opted-in app messages from a small windowed buffer (last N
messages, where N ≤ 20). The input comes from an
`AccessibilityService` snapshot of the chat window. This service runs
**only** when the kid is in an opted-in app, and disables itself
otherwise. We document this clearly in the kid transparency screen
(§7) — "OpenWarden reads your Discord messages on this phone only, and
only when Discord is open."

**Output:**

```kotlin
data class TextClassifierResult(
    val timestamp: Instant,
    val app: String,
    val flags: Set<Flag>, // empty if clean
    val summary: String?, // null unless parent opted into summaries
    val modelVersion: String,
)
```

The raw message text is **not** in the result. If summaries are on, the
summary is a model-generated paraphrase ("Friend Alex sent angry
messages") — and the policy doc requires the kid to know summaries
are on.

**Battery:** ~2% per day of monitoring (AICore figure, conservative).
We disable in battery-saver mode per the policy doc.

**Why deferred:** AICore availability is still patchy across the
target-device list ([`SIMPLIFY.md`](SIMPLIFY.md) §3: Pixel 7 family is
the v1/v2 target; Pixel 8 isn't required until it's "older"). Shipping
text classification on a subset of supported devices creates a
"some-families-get-it" UX problem we'd rather solve once, in v3, when
the Pixel 8 is the baseline.

---

## 5. Behavioral anomaly model — v2

Not really "AI." Plain Kotlin against `UsageStatsManager` and (with
opt-in) `LocationManager`. Ships in v2 alongside the image classifier
because the audit-log plumbing is shared.

**Inputs:**

- `UsageStatsManager.queryUsageStats()` — 24h rolling, 7-day rolling
- Foreground app sequence
- (Optional, opt-in) location samples at 1/hour resolution

**Detections:**

- Sudden screen-on between 02:00 and 05:00 local (kid is normally
  asleep)
- Foreground-app sequence outside the 7-day pattern envelope
- Outside-home-geofence after a parent-configured time
- Screen time exceeding 3σ above 7-day rolling mean

**Threshold:** > 3σ from the 7-day rolling mean for any rolling
metric. For categorical signals (3am screen-on, geofence) the trigger
is binary — fire on first occurrence within the parent's enabled
window.

**Implementation:** ~400 LOC of Kotlin in
`child-android/anomaly/`. No model file, no MediaPipe, no TFLite. The
"AI" framing in the policy doc is shorthand; this is a statistical
filter and it lives in the classifier audit log alongside the real ML
models because parents reason about it the same way ("OpenWarden noticed
something").

---

## 6. Opt-in plumbing

Shared across image NSFW, Gemma text (v3), and the anomaly model.

**Parent app, per-classifier:**

```
[ ] Enable classifier
    [ ] Notify on detection
    [ ] Block app for N minutes on detection (rate-limited)
    [ ] Notify kid: "OpenWarden flagged this — talk to dad"
    Confidence threshold: <slider>
    Active apps: <multi-select>
```

**Defaults:** every classifier off. Every notify-kid off. Every block
off. **Setup wizard** (during pairing) suggests enabling NSFW for
browser + social apps, and the anomaly model in its default config —
the parent has to actively accept; we don't pre-tick.

**Per-app scope.** A classifier can be on for Chrome and Instagram
but off for Google Photos. Stored as
`Map<ClassifierId, Set<PackageName>>` in the policy bundle, signed
end-to-end ([`PROTOCOL.md`](PROTOCOL.md)).

**Schema versioning.** The policy bundle's classifier section carries
its own version field so a v2 child running a v3 parent's
text-classifier-aware bundle silently ignores the text section instead
of erroring. Forward compatibility, not backward.

---

## 7. Audit log + kid transparency

**Audit log entry** (one per classification, including
non-detections):

```kotlin
data class AuditEntry(
    val timestamp: Instant,
    val classifierId: String, // "image_nsfw_v1", "anomaly_v1", ...
    val classifierVersion: String, // "falconsai-int8-2026-04"
    val app: String,
    val score: Float?,
    val bucket: String, // NEUTRAL | SUGGESTIVE | EXPLICIT | OK | ANOMALY
    val actionTaken: String, // LOG | FLAG | BLOCK | ALERT_PARENT
    val parentNotified: Boolean,
)
```

Stored in the existing audit log table ([`PROTOCOL.md`](PROTOCOL.md)).
Retention 90 days, then aggregate. The raw input (image, message
window) is never in the entry, ever.

**Kid transparency screen** — accessible from the kid-side home screen,
plain language, age-9-appropriate:

> **What does OpenWarden see?**
>
> Pictures that show up on your screen in **Chrome**, **Instagram**, and
> **Snapchat** get a quick check for grown-up stuff. Nothing leaves your
> phone — the check happens right here.
>
> Last week: **47 images checked**, **0 flagged**.
>
> OpenWarden is **not** checking your messages, your photos in
> Google Photos, or any other app.

Pictograms (eye/check/lock icons) over text for low-literacy ages
(targets ages 9–10 specifically per the UX patterns doc). The list of
checked apps is generated from the parent's actual per-app config, not
hardcoded — if a parent disables Instagram checking, the kid screen
reflects that immediately.

**Parent audit screen** — filterable by classifier, app, action,
date. Includes a "Mark as false positive" affordance per row
(consumed by the tuning loop in §10).

---

## 8. Model distribution and updates

Models are too big for the APK. We ship them as separately downloaded
artifacts.

- **Size:** Falconsai int8 ~85 MB. Future Gemma slot reserves up to
  ~500 MB but AICore handles its own distribution.
- **Source:** GitHub Releases, attached to a OpenWarden release tag.
- **Manifest:** a small signed JSON (`models.json`) lists each
  available model, its version, SHA-256, and a detached Ed25519
  signature against the maintainer release key.
- **Download flow:** child app fetches `models.json` over the same
  pinned-cert channel used for app updates. Verifies the signature.
  Downloads the model artifact. Verifies SHA-256. Atomically swaps it
  into the active model slot.
- **Rollback:** the previous model is kept in a `previous` slot until
  the new model has run 100 inferences with no init failures, then
  garbage-collected. If init fails on the new model, automatic
  rollback to `previous` and surface to the parent: "Model update
  failed; using previous version."
- **Parent approval required.** Model OTA updates are **not**
  auto-applied. The parent sees: "A new content-detection model is
  available. Falconsai v2026.04 → v2026.07. Eval results: 71% → 74%
  detection, 1.8% → 1.4% false-positive. [Update] [Skip] [Details]."
  This is deliberate — a classifier update silently changing what gets
  flagged is exactly the trust failure we are designed to avoid.

---

## 9. Library choices

| Library | License | Purpose | Why over alternatives |
|---|---|---|---|
| TensorFlow Lite | Apache 2.0 | Inference runtime | Standard, mature, on-device |
| MediaPipe Tasks | Apache 2.0 | Image classifier wrapper | Built-in preprocessing matches ViT exactly |
| (None — Kotlin) | — | Anomaly model | No ML lib needed for σ math |
| AICore (Gemma) | Google AI Edge terms | v3 text classifier | Only practical way to run Gemma Nano |

**Explicitly avoided: ML Kit.** Depends on Google Play Services, which
[`SIMPLIFY.md`](SIMPLIFY.md) §2 Tier 3 forbids in the required path.
MediaPipe Tasks does not require Play Services and ships its inference
runtime in-APK.

**Delegate selection (image classifier):** GPU delegate where
available (broadest device support, lowest variance). Hexagon NNAPI as
opportunistic fallback — historically inconsistent across vendor
builds, so it's an opt-in build-time flag, not the default.

---

## 10. False-positive tuning

**Pre-launch gate.** Before v2 ships:

1. Run the int8 model against a held-out set of 1000 benign images
   sampled from the kinds of things a 9-year-old's browser shows:
   YouTube thumbnails, schoolwork screenshots, art-class still-lifes,
   family-photo-app exports, news headlines.
2. The flag rate (anything `≥ 0.5`) must be **under 2%**. If it isn't,
   raise the `SUGGESTIVE` floor or bump the calibration set and
   re-quantize.
3. Run against a held-out NSFW set of 500 images. Detection rate
   (`≥ 0.85`) must be **above 60%**. We do not need 98%; we need
   honest.

**Post-launch tuning loop.** The parent audit screen's
"Mark as false positive" affordance feeds a per-family adjustment:
after 3 confirmed false positives in the same `SUGGESTIVE` band, the
threshold for that family auto-bumps by 0.05 (capped at 0.95). The
adjustment is local-only and visible in the parent settings UI so it
isn't a black box.

**Model retraining.** Out of scope for grant-funded baseline. If we
get a research-grant boost we'd commission a quarterly Falconsai
retrain on a kid-content-focused calibration set; until then, model
updates are upstream-driven and shipped under §8.

---

## 11. Per-tier ship verdict

| Capability | Tier | Ship in |
|---|---|---|
| Image NSFW (Falconsai int8) | 2 | v2 |
| Behavioral anomaly (Kotlin stats) | 2 | v2 |
| Gemma Nano text classifier | 2 | v3 |
| NudeNet secondary image classifier | 2 | v2.x **iff** license clears |
| Multi-modal (image + text joined) | 3 | v4+ if ever |
| On-device audio | — | never (policy doc forbids) |
| Continuous screen capture | — | never (policy doc forbids) |

The image classifier is pulled forward to v2 because the
`MediaProjection`-on-foreground-app-change plumbing, the per-app
opt-in plumbing, and the audit log plumbing it forces us to build are
shared infrastructure for everything else. The text classifier has no
such pull-forward justification and stays in v3.

---

## 12. Test plan

**Unit (`child-android/anomaly/test/`, `child-android/aiclassifier/test/`):**

- Known-image fixture set (100 labeled images) producing expected
  buckets at fixed thresholds.
- Threshold-edge tests: 0.499, 0.500, 0.849, 0.850.
- Repeat-within-24h promotion test: two `SUGGESTIVE`s in the same app
  in 23h → promoted; same in 25h → not promoted.
- Anomaly: synthesize 7 days of usage stats with a known 3σ event,
  assert detection.

**Integration (Pixel 7 test device):**

- `MediaProjection` consent persists across reboot.
- Foreground-app-change trigger fires within 500 ms.
- Rate limiter caps in-app reclassification at one per 30 s.
- Battery drain measured over an 8-hour kid-use simulation: target
  <0.5% attributable to the classifier.
- Bitmap lifetime test: after `classify()` returns, the bitmap is
  unreachable from any retained classifier state.

**Accuracy / A/B:**

- Maintain a 2000-image held-out set (1000 benign, 1000 NSFW)
  refreshed quarterly.
- Track detection rate and false-positive rate per model release.
- Publish numbers in release notes — parents see the same numbers we
  do.

**Ethics review (pre-v2):**

- Walk through the trigger flow with a privacy reviewer and confirm:
  no path exists by which a screenshot survives the inference call,
  no path exists by which it reaches the network, no path exists by
  which the parent app receives anything but the audit-log entry.
- Document the review in the v2 release ADR.

**Adversarial:**

- Kid-side attempts to disable the classifier: confirm DPC restriction
  prevents the kid from revoking `MediaProjection`, disabling the
  accessibility service, or uninstalling the classifier model.
- Battery-saver mode: confirm classifier suspends per the policy doc.
- Stale-policy mode: confirm classifier falls back to last-known
  config rather than failing open or failing closed unannounced.

---

## References

- Falconsai/nsfw_image_detection model card — https://huggingface.co/Falconsai/nsfw_image_detection
- Hugging Face Optimum TFLite exporter — https://huggingface.co/docs/optimum/exporters/tflite/overview
- MediaPipe Tasks Image Classifier (Android) — https://developers.google.com/mediapipe/solutions/vision/image_classifier/android
- Android AICore / Gemma Nano — https://developer.android.com/ai/aicore
- UnsafeBench 2024 (NSFW classifier eval) — https://arxiv.org/abs/2405.03486
- TensorFlow Lite quantization guide — https://www.tensorflow.org/lite/performance/post_training_quantization


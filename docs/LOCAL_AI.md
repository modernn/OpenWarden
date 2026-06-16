# Local AI

OpenWarden can use on-device AI to flag concerning content **without ever exfiltrating that content**. The model runs locally; only the *event* (category + severity + timestamp) reaches the parent. The raw image, message, or screenshot stays on the child device.

**Defaults:** off. Every classifier is opt-in per-category by the parent. Kid is shown which classifiers are active in plain language.

## Why local AI

| Approach | Privacy | Cost | Latency |
|---|---|---|---|
| Cloud AI (OpenAI, Bark backend) | poor — content uploads | high (per-request) | seconds + bandwidth |
| **On-device AI (OpenWarden)** | strong — content never leaves | free | <1s |
| No AI | strongest | free | n/a |

Cloud parental controls (Bark, Aura) require uploading messages and screenshots to a vendor. That's the model OpenWarden rejects.

## Models we'll use

### v2 — Image classifier (screenshots only)
- **MediaPipe Image Classifier** (TensorFlow Lite) with a NSFW model (e.g. NSFW-detector / GantMan models, or a custom-trained model)
- Periodically classify foreground screenshot when user is in a flagged app (browser, social — never always-on)
- Output: `{is_explicit: bool, confidence: float, categories: [...]}`
- ~5MB model, runs on CPU/NPU in <200ms on a Pixel 7

### v3 — Text classifier (kid's messages, opt-in)
- **Gemma Nano via Android AICore** (Pixel 7+, free, on-device)
- Categories: bullying-target, bullying-aggressor, self-harm signal, predatory-grooming-pattern
- Input: small windowed message buffer from explicitly opted-in apps (kid + parent both consent)
- Output: `{flags: [...]}` + optional `summary: string` if parent opted into summaries
- Never reports raw content; only flags + optional parent-readable summary

### v3 — Behavioral anomaly model
- Simple statistical model (no ML magic): "kid normally uses Discord 4-6pm; sudden 3am session = flag"
- "Phone normally home by 9pm; outside home geofence after 10pm = flag"
- Runs on usage stats + location only; no content

## What we will NEVER do

- **Keystroke logging.** Ever.
- **Screen recording.** Ever.
- **Continuous audio capture.** Ever.
- **Sending content (image, text, audio) off device.** Ever.
- **Classifying messages without the parent + kid both knowing.**

These are stalkerware features. OpenWarden doesn't ship them.

## Categories + thresholds

The parent configures, in the parent app:

```
[x] Screenshot NSFW detection
    [x] Notify me on explicit content
    [x] Block app for 60 min on detection (auto-rate-limited)
    [ ] Notify Oliver: "OpenWarden flagged this — talk to dad"
    Confidence threshold: 0.85
    Active apps: browser, instagram

[ ] Message bullying-detection (off)
[ ] Self-harm signal (off — talk to dad before enabling)
[x] Usage anomaly
[x] Geofence anomaly
```

Every classifier defaults off. Every event log entry records *which classifier fired*, so parent can audit + retune.

## Auditability

- Open weights or open models only
- Model file hash pinned in code; OTA model updates require parent confirm
- Every classification (even non-flags) is logged locally with timestamp + classifier version
- Parent can pull this log via OpenWarden UI
- "Why did OpenWarden flag this?" screen for kid: shows classifier name + threshold (NOT the input content)

## Power budget

- Image classifier runs on screenshot capture event, not continuous
- Text classifier runs on message-send/receive event in opted-in apps
- Anomaly model runs once per hour
- Target overhead: <2% daily battery
- All inference suspended in battery-saver mode

## Building it

- TensorFlow Lite (Apache 2)
- MediaPipe Tasks (Apache 2)
- Gemma weights via AICore (Apache-2-style terms via Google AI Edge)
- No proprietary model bundles
- Reproducible builds for the APK
- Verify-before-deploy: ship a "model dry run" command in dev builds to test classifications without enforcement

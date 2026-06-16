# Security Policy

OpenWarden is security software for protecting children. We take vulnerabilities
seriously and appreciate responsible disclosure.

## Reporting a vulnerability

**Do not open a public issue for a security vulnerability.**

Report privately, via either:
- GitHub **Private Vulnerability Reporting** (this repo → *Security* tab →
  *Report a vulnerability*), or
- Email **me@larson.link** with subject `OpenWarden security`.

Please include: affected component (`child-android`, `parent-kmp`, `proto`,
protocol/crypto), a description, reproduction steps or PoC, and impact. We aim to
acknowledge within a few days. Coordinated disclosure is welcome; we will credit
reporters who want it.

## In scope

- The child DPC enforcement surface and bypasses (a child escaping policy is the
  primary threat — see the threat model below).
- Signed policy bundle / event-log cryptography (Ed25519, RFC 8785 JCS, libsodium
  sealed box, replay/rollback protection).
- Provisioning, recovery phrase handling, and the self-decommission path.
- The LAN sync transport.

## Out of scope

- Anything requiring physical possession of an **unlocked, rooted** device with
  the recovery phrase (that party already holds root authority).
- Issues in optional third-party integrations themselves (report upstream).
- Social-engineering of a parent.

## Threat model & known issues

This is a young project; the design has known, tracked gaps:
- Threat model and defenses: [`docs/ATTACKS.md`](docs/ATTACKS.md),
  [`docs/DEFENSES.md`](docs/DEFENSES.md), [`docs/SECURITY.md`](docs/SECURITY.md).
- Adversarial design review (open gaps): [`docs/research/07-redteam-design-review.md`](docs/research/07-redteam-design-review.md).
- Crypto/lifecycle decisions: [`docs/adr/`](docs/adr/) (esp. 013–017).

If your finding is already listed there, it may be a known gap — a PR or a
sharper analysis is still very welcome.

## Our security pledges (non-negotiable)

- **No telemetry / no phone-home.** We cannot exfiltrate user data because we
  collect none.
- **No content monitoring.** Messages, photos, audio are never read or sent.
- **Fail-closed.** Every error path defaults to *more* restriction.

A "fix" that violates these is not a fix we will accept.

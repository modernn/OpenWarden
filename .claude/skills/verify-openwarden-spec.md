---
name: verify-openwarden-spec
description: Verify implementation matches spec docs (PROTOCOL.md, CRYPTO.md, etc.). Runs test vectors + conformance checklist. Use after implementing any spec-described feature.
---

# /verify-openwarden-spec

Verifies that current code implementation matches the canonical specs in `docs/`.

## What it does

1. Loads test vectors from `docs/test-vectors/`
2. Runs them against current implementation
3. Reports per-spec conformance: PROTOCOL, CRYPTO, PROVISIONING_V2
4. Surfaces discrepancies

## When to use

- After implementing any function described in PROTOCOL.md or CRYPTO.md
- Before claiming a feature is "done"
- After updating a spec doc (verify code still matches)

## Conformance checks

- PROTOCOL.md §9 test vectors (signed bundles, sealed-box)
- CRYPTO.md §12 BIP39 → key derivation
- PROVISIONING_V2.md §9 /health endpoint shape

## Output format

```
Spec conformance:
  PROTOCOL.md: 27/30 pass
    FAIL: bundle-replay-rejection (entry_3 was accepted; should be REGRESSION)
    FAIL: jcs-canonicalization (ordering off)
  CRYPTO.md: 12/12 pass
  PROVISIONING_V2.md: /health endpoint missing field 'fail_closed'
```

## Sample invocation

```
/verify-openwarden-spec
/verify-openwarden-spec --only protocol
```

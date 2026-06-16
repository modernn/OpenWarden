---
name: codex-second-opinion
description: Get OpenAI Codex review on current work. Use for crypto/protocol decisions, when stuck after 3 attempts, or for high-stakes UX. Spawns codex-rescue subagent.
---

# /codex-second-opinion

Delegates to OpenAI Codex via `codex:rescue` subagent for independent review.

## What it does

1. Bundles current diff / proposal
2. Spawns `codex:rescue` w/ context
3. Codex independently analyzes
4. Returns findings + recommendations
5. Claude reviews Codex's output, decides whether to incorporate

## When to use

- Crypto or protocol design decision (high stakes)
- Stuck after 3 failed attempts on same problem
- Adversarial review needed (use `impeccable-codex-debate` for full debate)
- Sanity check on architectural choice

## When NOT to use

- Routine bug fixes — Claude can handle
- Simple refactors — Codex doesn't add value
- Style nits — ktlint catches these

## Output format

Codex's report relayed back. Then Claude states: agree / disagree / partially incorporate.

## Sample invocation

```
/codex-second-opinion
```

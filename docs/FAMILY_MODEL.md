# Family Model — Multi-Child + Multi-Parent Data Design

> **Audience:** maintainers shaping the v1 schema with v3 in mind. Anyone
> reviewing PRs that touch identity, policy, or the co-parent feed.
>
> **Companion docs:** [`SIMPLIFY.md`](SIMPLIFY.md) (what v1 ships),
> [`UX_PATTERNS.md`](UX_PATTERNS.md) §B (co-parent feed),
> [`ARCHITECTURE.md`](../ARCHITECTURE.md) (three planes),
> [`PROVISIONING_V2.md`](PROVISIONING_V2.md) (per-kid provisioning),
> [`RECOVERY.md`](RECOVERY.md) (BIP39 root key flow).

**Locked product decisions:**

- v1 ships **single kid + one-or-two parents only.**
- The data model on disk and in signed bundles is the **full multi-kid /
  multi-parent model from day one**, so v3 isn't a destructive
  migration.
- Per [`SIMPLIFY.md`](SIMPLIFY.md) §4, multi-kid policies and roles
  beyond `[admin, babysitter]` are out of scope for v1 UI. The schema
  may carry them, the surface may not expose them.

The single biggest mistake we can make is shipping a schema that
implicitly assumes one kid (e.g. a top-level `kid_pubkey` on the family
doc) and then having to fork every signed format in v3. This doc exists
so v1 already has `kid_id` everywhere it needs to be.

---

## 1. Family entity model

```
Family
├── Parents (1..N)
│   ├── Parent (Ed25519 identity, BIP39 root)
│   │   ├── role: ADMIN | LIMITED (babysitter)
│   │   └── visibility prefs per kid
│   └── ...
├── Children (1..N)
│   ├── Child (Pixel device, child Ed25519 identity)
│   │   ├── age + birthday
│   │   ├── trust level
│   │   └── PolicyDoc (signed)
│   └── ...
└── PairingState (which parent ↔ which child)
```

The `Family` is the top-level aggregate. `PairingState` is a sparse
matrix (`parent_id × kid_id → trust state`), not implicit on either
side, so revocations don't require touching the parent or kid records.

---

## 2. Identity hierarchy

- **Family root:** derived from the family's BIP39 phrase. Never
  directly signs commands; only signs parent-identity attestations and
  family-graph mutations.
- **Parent identities:** one Ed25519 keypair per parent, all derived
  from the same family root via BIP32-style indexed derivation
  (`m/openwarden'/parent'/i`). This means a recovered phrase regenerates
  every parent identity deterministically.
- **Child identities:** one Ed25519 keypair per child Pixel,
  **generated on the child device at provisioning, NOT derived from
  the family root.** Independent for security: a leaked family phrase
  must not let an attacker impersonate the child device to the parent.
- **Parent-child trust:** pinned at pairing. Child stores
  `{family_root_pub, [parent_pub...]}`; parent stores
  `{child_pub, child_id}`. Both ends verify on every command.

---

## 3. PolicyDoc per kid

Each kid has an **independent** PolicyDoc, signed by any admin parent.

- `family_id`, `kid_id` are required fields on every doc.
- Trust level is per-kid (see [`SIMPLIFY.md`](SIMPLIFY.md) Tier 0).
  Oliver age 10 and stepdaughter age 14 don't share a graduated
  privileges curve.
- Visibility settings are per-kid: which parents see which signals.
- Birthday is per-kid; used for age-graduation rule triggers.

In v1, there is exactly one PolicyDoc with `kid_id = oliver`. The code
path is *identical* to the v3 case where there are three. The UI
flattens "kids" to a single record; the storage layer never does.

---

## 4. Co-parent feed scoping

- **v1:** single kid → flat feed, no per-kid filter.
- **v2:** multi-kid → filter chips per kid (`All`, `Oliver`,
  `Mia`...). Feed entries always carry `kid_id`; the v1 flat view is
  just the v2 "All" view with the chip row hidden.
- **v3:** per-kid co-parent visibility prefs. Example: mom sees
  Oliver's full activity but only flagged events for Mia (her bio kid
  vs. stepdaughter, by family agreement). Encoded as
  `visibility[parent_id][kid_id] → {full, flags_only, none}`.

The v1 feed structure already stores `kid_id` on every entry — no
schema change required at v2.

---

## 5. Parent role model

- **Admin:** full control. Can modify rules, decommission devices,
  add/remove other parents (with co-sign rules in §15).
- **Limited (babysitter):** can trigger pre-approved exception
  bundles, lock-now, and view current status. Cannot change rules or
  modify the family graph. Per [`UX_PATTERNS.md`](UX_PATTERNS.md) §B6.
- **Co-parent:** Admin-equivalent. Not a distinct role; the term
  describes the typical second-admin case.

**Roles are per-kid.** Dad can be Admin of Oliver and Limited of the
stepdaughter. This is the schema reality even though v1 collapses it to
one kid. Per [`SIMPLIFY.md`](SIMPLIFY.md) §4, additional roles
(grandparent read-only, teacher, tutor) stay out of v1.

---

## 6. Permission delegation

- **Babysitter pairing:** a `LIMITED` role with a `expires_at`
  timestamp set by the inviting admin. The child device honors the
  expiry without needing fresh contact from the parent.
- **Grandparent (future):** read-only Admin. Sees the feed, cannot
  change rules. Schema is just `role: ADMIN` with
  `capabilities: [read]`.
- **Teacher / school-mode (deferred):** bounded read on a
  school-hours geofence. The schema can express
  `role: LIMITED, window: school_hours, scope: geofence:school`, but
  this is explicitly post-v3. Listed here only so we don't paint over
  the seam.

---

## 7. Data model implications

- Every PolicyDoc carries `family_id` and `kid_id`.
- Every command carries `from_parent_id` and `to_kid_id`.
- The child device verifies:
  1. Command signature matches a parent pubkey.
  2. That parent pubkey is in the child's pinned
     `{family_root_pub, [parent_pub...]}` set.
  3. The parent's role for *this kid* (`to_kid_id`) permits this
     action.
- The child enforces role checks locally. A `LIMITED` parent's
  rule-change command is rejected at the child even if it arrives over
  a valid transport.

---

## 8. Signature graph

```
family_root_key (BIP39-derived)
  ├── signs → parent_identity_attestation (for each parent)
  └── signs → family_graph_root (versioned)

parent_identity (per parent)
  ├── signs → PolicyDoc(s)
  ├── signs → command(s) (unlock, lock-now, etc.)
  └── signs → counter-command(s)

child_identity (per child Pixel)
  └── signs → event_log_entries (audit, ack, status)
```

Child verifies each command in two steps:

1. Verify signature with `from_parent_id`'s pubkey.
2. Verify `from_parent_id` is bound to `family_root_pub` via a
   parent-identity attestation pinned at pairing.

**New parent join:** the new parent's attestation must be signed by an
existing admin (not only the family root). This means the family root
can be cold-stored after initial setup; ongoing additions ride on the
warm admin keys.

---

## 9. Adding a parent (mom joins)

1. Dad invites mom: parent app generates a QR with the family ID and a
   one-time join token (not the BIP39 phrase).
2. Mom installs the OpenWarden parent app.
3. Mom enters the family BIP39 phrase **on her own device** plus her
   name and role (default: `ADMIN`).
4. Mom's parent identity is deterministically derived from the phrase
   at the next free index.
5. Dad (existing admin) signs a parent-identity attestation for mom's
   pubkey. The signed attestation is appended to the family graph.
6. The updated family graph propagates via the store-and-forward layer
   to all paired children and parents.
7. The co-parent feed shows: "Mom joined the family. Signed by dad."

The BIP39 phrase never leaves mom's device. The attestation is what's
broadcast.

---

## 10. Adding a kid (sibling joins, v3 flow)

Out of scope for v1 UI but the schema must already support it.

1. Buy a new Pixel for the sibling.
2. In the parent app: **Add kid** → name, age, birthday.
3. Run provisioning per [`PROVISIONING_V2.md`](PROVISIONING_V2.md) on
   the new device. The new kid Pixel generates its own Ed25519
   identity at provisioning.
4. A new PolicyDoc is created with a new `kid_id` and an independent
   trust level.
5. The family graph gains a `Child` record; admins co-sign.
6. The feed begins offering a per-kid filter once `len(kids) > 1`.

No existing PolicyDocs change. No keys rotate. The new kid is purely
additive.

---

## 11. Solo parent model

Likely the most common configuration.

- One `ADMIN` parent, zero co-parents.
- No co-parent feed surface (per [`UX_PATTERNS.md`](UX_PATTERNS.md)
  §B5 — degrade gracefully; the parent's own actions still write to a
  personal audit log).
- Family root = the solo parent's BIP39 phrase.
- Same schema, fewer rows. No code path is conditional on "is there a
  co-parent?" — the feed simply renders one parent's actions.

---

## 12. Multi-family / shared custody

Divorced or separated parents where a kid lives between two
households.

- **Option A:** separate OpenWarden installs per household. Each parent
  operates an independent family. Two PolicyDocs, two sets of rules,
  no cross-household visibility.
- **Option B:** shared OpenWarden with both parents as admins in one
  family. Both parents see the co-parent feed of each other's
  actions, including the friction this implies.
- **Recommendation:** Option B with an explicit "shared custody"
  mode, opt-in by both parents, with the transparency about each
  other's actions made unmistakable in the onboarding. The
  data model is the same as the standard two-admin case; the
  difference is purely UX framing.

Custody-scheduled role variations (mom-admin Mon-Thu, dad-admin
Fri-Sun) are explicitly **not** modeled. Per
[`SIMPLIFY.md`](SIMPLIFY.md) §4: no "custody-scheduled stepparent."
Both parents are full admins or they use Option A.

---

## 13. v1 simplification rules

A staged unlock from v1 (simple) to v4 (full):

- **v1:** family has exactly 1 admin parent + 1 kid.
- **v1.1 / v2:** 2 admin parents + 1 kid + babysitter role.
- **v3:** N admin parents + N kids.
- **v4:** shared-custody mode, school/teacher role.

In every version the on-disk schema is the same. The UI is what
expands.

---

## 14. Migration safety

- The family graph is versioned: `family_doc.v = 1`.
- Adding a second parent does not bump the version; it just appends to
  an existing array.
- A schema bump is reserved for *truly* new fields (e.g. when we
  introduce `visibility[parent_id][kid_id]` in v3).
- All PolicyDocs already carry `family_id` and `kid_id` in v1, so a
  v3 device reading a v1 PolicyDoc reads it unchanged.
- Backward-compat rule: **a newer parent app must be able to verify
  signatures produced by older versions** for the lifetime of any
  paired child that hasn't been re-provisioned.

---

## 15. Failure modes

- **Parent leaves family (divorce, departure):** remaining admins
  jointly sign a revocation command. The departing parent's
  attestation is tombstoned (not deleted, per
  [`UX_PATTERNS.md`](UX_PATTERNS.md) §B3) and child devices reject
  further commands from that key.
- **Last admin protection:** the family graph refuses to revoke the
  final admin. There must always be at least one signing authority
  besides the cold BIP39 root.
- **Lost parent device (no co-admin):** the family BIP39 phrase plus
  a 24-hour delay can decommission the lost identity and provision a
  replacement. Per [`UX_PATTERNS.md`](UX_PATTERNS.md) §C4 and
  [`RECOVERY.md`](RECOVERY.md).
- **Compromised parent identity:** any other admin can revoke
  immediately (no 24h delay), and the kid device honors the revocation
  on next sync. The compromised parent receives an audit alert that
  cannot be suppressed.

---

## 16. Storage impact

- Family graph (parents + kids + attestations): ~10 entries even for a
  blended family. Tens of KB.
- PolicyDoc per kid: ~5KB.
- Event log per kid: ~1MB / month rolling.

Negligible on both parent and child devices. No storage-driven reason
to denormalize.

---

## 17. References

- **iCloud Family Sharing:** closed-source but instructive on the
  "family is the unit, members are roles" model.
- **Family Link family group model:** the negative example. Treats
  each parent as an independent admin with no shared timeline — the
  exact gap the co-parent feed fills.
- **ActivityPub federation model:** multi-actor, signed-message
  inspiration for the per-parent signing graph.
- Internal: [`UX_PATTERNS.md`](UX_PATTERNS.md) §B,
  [`SIMPLIFY.md`](SIMPLIFY.md) §3-4,
  [`ARCHITECTURE.md`](../ARCHITECTURE.md),
  [`PROVISIONING_V2.md`](PROVISIONING_V2.md),
  [`RECOVERY.md`](RECOVERY.md).

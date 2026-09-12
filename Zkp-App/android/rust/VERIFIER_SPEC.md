# ZKAuth Verifier Specification (v6.0)

> **Status:** Normative for any party verifying ZKAuth proof bundles.
> Companion documents: PROTOCOL.md (trust/replay semantics), THREAT_MODEL.md
> (assumptions). **Standing constraint:** until issue #8's A-01+A-02 ship,
> proofs are demo-grade — do NOT authorize identity on them.

## 1. Verifying Key Pinning (MUST)

- Verifiers MUST pin the **circuit VerifyingKey digest** — NOT trust any
  key distributed with proof bundles or client builds.
- The VK digest MUST be recorded **separately from `PROOF_VERSION`**:
  - `PROOF_VERSION = "6.0"` = proof-format/semantics version
  - `VK_DIGEST = <hex>` = circuit-specific verification key fingerprint
  - Any circuit change ⇒ new VK digest ⇒ explicit migration entry
    (see MIGRATION_v6.md pattern). A format-version bump WITHOUT a VK
    change (or vice-versa) is a documentation bug.
- Current VK digest: **TBD** — MUST be generated from
  `outer.data.verifier_only` and recorded here before any external
  verifier deployment.

## 2. Public Input Layout (26 field elements, Goldilocks)

Exact `register_public_inputs` order in `passport_security.rs`:

| Index | Field | Meaning | Verifier policy |
|---|---|---|---|
| 0–3 | `root` | Poseidon HashOut — identity commitment | §3.2 |
| 4–7 | `nullifier` | Poseidon HashOut — domain-scoped token | §3.4 |
| 8 | `claim_type` | 0=is_adult, 1=nationality, 2=is_human | §3.3 |
| 9–12 | `dg1_anchor` | Poseidon HashOut — DG1 binding | §3.2 ⚠️ |
| 13 | `valid_until` | u64 unix secs (32-bit range-checked) | §3.5 |
| 14–17 | `expected_nat` | Poseidon HashOut — expected nationality hash | §3.3 |
| 18–21 | `hw_binding` | Poseidon HashOut — device-key binding | §3.6 ⚠️ |
| 22–25 | `revocation_id` | Poseidon HashOut — revocation lookup | §3.2 ⚠️ |

**⚠️ Anchors marked ⚠️ are UNCONSTRAINED public inputs** (issue #8 A-03):
a custom prover can set them arbitrarily while producing a valid proof.
Until A-03 ships, verifiers MUST NOT treat `dg1_anchor`, `hw_binding`,
or `revocation_id` as issuer-derived facts. `nullifier` likewise.

## 3. Verification Policy (MUST, in order)

### 3.1 Version gate
`version == "6.0"` AND pinned `VK_DIGEST` matches the key used. Reject otherwise.

### 3.2 Recursive proof verification
plonky2 `verify()` with pinned VK against the 26 PIs. Failure ⇒ reject.
No heuristic or partial verification paths.

### 3.3 Claim policy — verifier-chosen values
- **Nationality (claim 8 = 1):** verifier MUST set its OWN `expected_nat`
  (Poseidon of the accepted nationality bytes, 1..=7 byte encoding) and
  require PI[14–17] to equal it. NEVER accept a prover-supplied
  expected-nationality hash.
- **is_adult (claim 8 = 0):** no expected-value input; freshness (§3.5)
  is the binding constraint.
- **is_human (claim 8 = 2):** ⚠️ currently under-constrained (A-01b) —
  do not authorize anything beyond demo on this claim.

### 3.4 Nullifier / replay
- Store keyed by `(nullifier, verifier_domain)`.
- Suppress duplicates within the **600s window** (per PROTOCOL.md §4.2 —
  semantics: at most one accepted proof per (passport, domain) per window).
- `verifier_domain` MUST be the verifier's own registered domain —
  the proof's nullifier is derived from it prover-side; mismatched-domain
  acceptance enables cross-replay.

### 3.5 Expiry — prover-attested (see PROTOCOL.md §4.1)
- Enforce `now ≤ valid_until` (upper bound only).
- **Freshness is NOT guaranteed by this protocol version** (prover picks
  `valid_until`). High-assurance deployments MUST add verifier challenge
  (P1, #8) before trusting recency.

### 3.6 Device binding — not authenticated (see PROTOCOL.md §7.3)
- `hw_binding` presence ≠ device possession. Transfer-resistance requires
  P2 (device challenge signature). Until then: informational only.

## 4. Response-Field Contract (holder-side result)

| Field | Verifier use |
|---|---|
| `trusted` | MUST be `true` for any authorization path; `false` ⇒ ignore `zk_output` entirely |
| `zk_output` | Present ONLY when `trusted == true` (A-04 gating) |
| `signature_check` | `VERIFIED` = CMS math valid — **not** issuer trust (A-06) |
| `trust_level` | `VERIFIED_ONLY` = diagnostic-only; `SIMULATED`/`NONE` ⇒ reject |
| `integrity_check` | DG1↔SOD hash match — advisory input to §3 policy |
| `success` | legacy flag; **superseded by the predicate fields of A-02** |

Absent fields: `document_number`, `holder_name` — removed (A-05, PII).
Any bundle containing them is from a pre-A-05 build ⇒ reject.

## 5. Minimal Accepting Verifier (pseudocode)

```text
assert version == "6.0"
assert vk_digest == PINNED_VK_DIGEST
assert plonky2.verify(PINNED_VK, public_inputs, proof)
assert pi.claim_type in policy.allowed_claims
if nationality claim: assert pi.expected_nat == H(my_expected_nat)
assert now <= pi.valid_until
key = (pi.nullifier, my_domain)
assert key not in replay_store(window=600s); record key
accept
Anything beyond this (device auth, CSCA, freshness) is future work
tracked in #8 — accepting proofs without those is demo-grade by
definition.


## 6. Bridge Schema Contract (BRIDGE_SCHEMA_DIGEST)

### 6.1 Canonical form
Sorted keys · UTF-8 · unit-separator join · SHA-256 hex.

### 6.2 Digest source of truth
Computed by Rust from **actual emitted keys at runtime** — never hardcoded
(drift-theater). Kotlin pins expected value; mismatch = explicit Failure.

### 6.3 v1 field list (13 keys — sorted)
`bridge_schema_digest, error_msg, input_mode, integrity_check, merkle_root,
nullifier, signature_check, success, trust_level, trusted, zk_output,
zk_proof_ms, zk_proof_status`

**Self-reference note:** `bridge_schema_digest` is itself in the key-set
(value `""` at compute time) — intended, not off-by-one.

### 6.4 Bump protocol
Field add/remove/rename ⇒ digest bump ⇒ spec entry (one line) ⇒ Kotlin
constant update (one line). Same discipline as VK digest.

### 6.5 Published value (v1)
`e04f05fb2a29949481825e02c044bad2a49a0b390205cc28b58484983213f2b3`

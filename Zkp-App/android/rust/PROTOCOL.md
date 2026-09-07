# ZKAuth Proof Protocol — Verifier Contract (v6.0)

> **Status:** Normative. Verifiers MUST enforce every section marked **MUST**.
> Proof format version: `6.0` (see `PROOF_VERSION`)

## 1. Proof Lifecycle

Holder (device) Verifier
───────────────── ─────────

NFC read → DG1 + SOD
SOD verify (ICAO-strict) │
ZK proof generate │
valid_until = now + 300 │
├────── proof bundle ────► 4. verify steps §3
│ 5. expiry + replay §4
◄────── accept/reject ────

**TTL:** `PROOF_TTL_SECS = 300` (5 minutes). Proof generated at T is valid
for `[T, T+300]` in verifier's view — subject to §4 constraints.

## 2. Proof Bundle — Verifier Input Contract

A verifier MUST receive and validate ALL of:

| Field | Type | Purpose |
|---|---|---|
| `version` | `"6.0"` | MUST match; reject otherwise |
| `compressed_proof` | hex (recursive plonky2 proof) | cryptographic core |
| `root` | hex (Poseidon HashOut) | identity commitment |
| `nullifier` | hex (Poseidon HashOut) | domain-scoped replay token |
| `dg1_anchor` | hex | passport-specific binding |
| `valid_until` | u64 (unix seconds) | expiry — §4 |
| `hw_binding` | hex | device binding (public inputs) |
| `revocation_id` | hex | revocation lookup key |
| `claim` | `{type, value}` | asserted claim |

## 3. Verification Steps (MUST, in order)

1. **Version gate** — `version == "6.0"` else reject.
2. **Recursive proof verify** — plonky2 verify against the v6.0 verifier key.
   Any failure ⇒ reject (no heuristic fallbacks).
3. **Public-inputs match** — `root`, `nullifier`, `dg1_anchor`, `hw_binding`,
   `revocation_id`, `valid_until`, `claim` MUST equal the proof's public inputs
   (byte-exact). Mismatch = bundle tampering ⇒ reject.
4. **Trust tier** — holder-side signature check result is advisory. Verifier
   SHOULD independently re-verify the passport SOD when it has the raw DG1/SOD
   (out-of-band), OR accept `trust_level: VERIFIED_ONLY` with the risks in §6.

## 4. Expiry & Replay (H2 — normative)

### 4.1 Expiry
- Verifier MUST enforce: `now ≤ valid_until`.
- `valid_until` is **prover-attested** (inside proof public inputs) — the
  circuit cannot check a wall clock. Expiry enforcement is therefore
  **entirely the verifier's responsibility**.

### 4.2 Replay window
- Verifier MUST maintain a nullifier store keyed by
  `(nullifier, verifier_domain)`.
- **Policy (v6.0 default):** reject any proof whose `nullifier` was seen in
  the last **600 seconds** (2× TTL). Rationale: allows clock skew up to
  TTL while preventing same-proof reuse.
- A proof with an unseen nullifier MUST be recorded with its
  `valid_until`; entries older than 600s MAY be pruned.

### 4.3 Clock source
- Verifier MUST use a monotonic, NTP-synced clock. Holder clock is
  irrelevant (by design).

## 5. Timestamp Limitation (documented)

- `valid_until` is a `u64` unix-seconds value, but the circuit range-checks
  it as **32-bit** → max `4294967295` = **2106-02-07T06:28:15Z**.
- Consequence: protocol invalid after 2106. Migration (64-bit in-circuit
  representation) is a **breaking change** requiring `PROOF_VERSION = 7.0`.
- Verifiers MAY reject `valid_until > 2^32 - 1` outright.

## 6. Trust Levels — Semantics (aligned with H3)

| `trust_level` | Meaning | Verifier guidance |
|---|---|---|
| `VERIFIED_ONLY` | DS signature cryptographically valid (ICAO-strict CMS); **no CSCA chain validated** | Acceptable for low/medium assurance. For high assurance: out-of-band SOD re-verification or wait for CSCA support. |
| `SIMULATED` | Dev fixture — **never** `success: true` | MUST reject in production. |
| `NONE` | Verification failed | Reject. |

`MAXIMUM` is **never emitted** — CSCA/PKD validation is not implemented
(C4c-full backlog: #8).

## 7. Unlinkability Contract (aligned with H1)

- `nullifier` = Poseidon(DG1_hash, verifier_domain) — stable per
  (passport, domain), unforgeable otherwise.
- Salts derive from `device_rng` (≥16 bytes, holder-generated, NOT derived
  from document data). Cross-verifier correlation via leaf values is
  computationally infeasible.
- `hw_binding` = Poseidon(DG1_hash, device_pubkey ≥32B) — binds proof to
  device; prevents proof theft/replay from another device.

## 8. Known Limitations (v6.0)

1. **CSCA chain not validated** — DS cert trusted only cryptographically,
   not hierarchically (see §6).
2. **ECDSA passports** — fail-closed (rejected). RSA-only supported.
3. **SignerIdentifier↔cert matching** — first-cert heuristic (full issuer+
   serial compare in backlog).
4. **ZK blinding** — circuits use `standard_recursion_config`; witness
   hiding not enabled (M2 — see threat-model note in #8).
5. **Public anchors** (`hw_binding`, `revocation_id`, `dg1_anchor`) are
   public inputs, not in-circuit derived from private witnesses (M3).

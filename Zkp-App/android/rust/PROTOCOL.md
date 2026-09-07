# ZKAuth Proof Protocol — Verifier Contract (v6.0-r1)

> **Status:** Normative. Verifiers MUST enforce every section marked **MUST**.
> Proof format version: `6.0` (`PROOF_VERSION`)
>
> **Revision r1:** Corrected trust model for `valid_until` (prover-controlled,
> NOT trusted), clock requirements (wall-clock vs monotonic separation),
> `hw_binding` guarantee (binding ≠ authentication), `VERIFIED_ONLY`
> assurance guidance, and nullifier-window semantics (per-(passport,domain)
> suppression, not proof-reuse detection).

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

**TTL:** `PROOF_TTL_SECS = 300` (5 minutes).

## 2. Proof Bundle — Verifier Input Contract

A verifier MUST receive and validate ALL of:

| Field | Type | Purpose |
|---|---|---|
| `version` | `"6.0"` | MUST match; reject otherwise |
| `compressed_proof` | hex (recursive plonky2 proof) | cryptographic core |
| `root` | hex (Poseidon HashOut) | identity commitment |
| `nullifier` | hex (Poseidon HashOut) | domain-scoped rate/suppression token |
| `dg1_anchor` | hex | passport-specific binding |
| `valid_until` | u64 (unix seconds) | **prover-attested freshness claim** — §4.1 |
| `hw_binding` | hex | device-key binding (public inputs) — §7.3 |
| `revocation_id` | hex | revocation lookup key |
| `claim` | `{type, value}` | asserted claim |

## 3. Verification Steps (MUST, in order)

1. **Version gate** — `version == "6.0"` else reject.
2. **Recursive proof verify** — plonky2 verify against the v6.0 verifier key.
   Any failure ⇒ reject (no heuristic fallbacks).
3. **Public-inputs match** — `root`, `nullifier`, `dg1_anchor`, `hw_binding`,
   `revocation_id`, `valid_until`, `claim` MUST equal the proof's public
   inputs (byte-exact). Mismatch = bundle tampering ⇒ reject.
4. **Trust policy** — apply §6 according to the application's assurance
   requirements. Holder-side `trust_level` is **advisory input**, not
   authentication.

## 4. Expiry & Replay

### 4.1 `valid_until` trust model — ⚠️ PROVER-CONTROLLED

**This is the most important limitation in this protocol.**

`valid_until` is a value **chosen by the prover** and committed inside the
proof's public inputs. The circuit only range-checks it (32-bit). The
circuit CANNOT verify it against a wall clock (no trusted clock in-circuit).

Consequence: a malicious prover can set `valid_until = now + 10 years`.
The verifier's `now ≤ valid_until` check WILL pass.

Therefore:

- `now ≤ valid_until` MUST be enforced — it bounds the **upper** end only.
- **The lower bound (freshness) is NOT enforced by this protocol version.**
- Applications requiring genuine freshness MUST use one of:

  a. **Verifier challenge (recommended, normative for high assurance):**
     verifier sends a random nonce; the proof MUST bind the nonce as a
     public input. Freshness becomes verifier-controlled. **NOTE: this
     requires a circuit change (challenge as public input) — tracked as a
     protocol item in #8; not yet implemented.**
  b. **Out-of-band SOD re-verification:** verifier receives raw DG1+SOD
     over an authenticated channel and re-verifies independently — then
     verifier-side freshness applies.
  c. **Risk acceptance:** accept prover-attested freshness for
     low-stakes use, with the explicit understanding that proof age is
     unbounded.

### 4.2 Replay window — per-(passport, domain) suppression

- `nullifier = Poseidon(DG1_hash, verifier_domain)` — **stable across
  proofs** for the same (passport, verifier_domain). It is NOT a per-proof
  random value.
- Verifier MUST maintain a store keyed by `(nullifier, verifier_domain)`.
- **Semantics (explicit):** during the window, the store suppresses ALL
  proofs sharing a nullifier — i.e., **at most one accepted proof per
  (passport, verifier_domain) per window**. This is a privacy/rate policy,
  not merely byte-identical-proof-reuse detection.
- **Window (v6.0 default): 600 seconds** (2× TTL). Entries older than 600s
  MAY be pruned.
- Rationale: combined with §4.1's freshness gap, the suppression window is
  the practical bound on how long a single (potentially stale) proof can
  keep being useful against one domain.

### 4.3 Clock requirements

- **Wall clock:** Verifier MUST use a trusted wall clock synchronized to
  UTC (NTP-disciplined) for `now ≤ valid_until` validation of Unix
  timestamps.
- **Monotonic clock:** Verifier SHOULD use a monotonic clock for local
  elapsed-time measurements — replay-window bookkeeping, cache TTLs.
- These are different clocks serving different purposes; wall-clock steps
  (NTP corrections) MUST NOT be used to measure the replay window.

## 5. Timestamp Limitation (documented)

- The circuit range-checks `valid_until` as **32-bit** → max
  `4294967295` = **2106-02-07T06:28:15Z**.
- Protocol invalid after 2106. A 64-bit in-circuit representation is a
  **breaking change** requiring `PROOF_VERSION = 7.0`.
- Verifiers MAY reject `valid_until > 2^32 − 1` outright.

## 6. Trust Levels — Semantics

| `trust_level` | Meaning | Verifier guidance |
|---|---|---|
| `VERIFIED_ONLY` | DS signature cryptographically valid (ICAO-strict CMS). **The DS certificate is NOT validated to a CSCA/PKD trust anchor** — this proves "this key signed this SOD", NOT "this key is an authorized passport DS". | **Do not treat as authenticity-equivalent to CSCA-validated passport verification.** Assurance policy is the application owner's decision. For high assurance: out-of-band SOD re-verification (§4.1b) or wait for CSCA support (C4c-full, #8). |
| `SIMULATED` | Dev fixture — **never** emitted with `success: true` | MUST reject in production. |
| `NONE` | Verification failed | Reject. |

`MAXIMUM` is **never emitted** — CSCA/PKD validation is not implemented
(C4c-full backlog, #8).

## 7. Binding Contracts — what each actually guarantees

### 7.1 `dg1_anchor = Poseidon(DG1_hash_fields)`
Binds the proof to a specific passport's DG1 content. Prevents proof from
being re-purposed for a different passport. ✓ (guaranteed by circuit
public-input equality, §3.3)

### 7.2 `nullifier` — see §4.2. Guarantees per-domain suppression, and
unforgeability w.r.t. DG1 (proving a different DG1 requires a different
valid proof). Does NOT prevent the same proof bundle from being replayed
to a *different* verifier domain — that is bounded by each domain's own
store.

### 7.3 `hw_binding = Poseidon(DG1_hash, device_pubkey)` — ⚠️ binding ≠ authentication

`hw_binding` binds the proof to **the device public-key material supplied
by the prover**. What the circuit guarantees: the proof was constructed
with this pubkey in the public inputs.

What it does NOT guarantee: that the **current presenting device**
controls that key. A copied proof bundle (including `hw_binding`) can be
presented by any device — the verifier has no independent reference for
the expected key.

**Proof-transfer resistance therefore REQUIRES verifier-side
authentication of the device key**, e.g.:
- the device signs the verifier's challenge with
  `device_pubkey` (Keystore-backed non-exportable key), or
- device attestation binding the key to verified hardware.

**Neither is implemented in v6.0.** Until then, hw_binding is a
correctness/binding property, not an anti-theft property. Tracked in #8
(device challenge protocol).

## 8. Known Limitations (v6.0)

1. **CSCA chain not validated** — see §6 (C4c-full backlog).
2. **ECDSA passports** — fail-closed (rejected). RSA-only supported.
3. **SignerIdentifier↔cert matching** — first-cert heuristic.
4. **ZK blinding** — `standard_recursion_config`; witness hiding not
   enabled (M2, #8).
5. **Public anchors** — `hw_binding`, `revocation_id`, `dg1_anchor` are
   public inputs, not in-circuit derived from private witnesses (M3, #8).
6. **Prover-controlled `valid_until`** — §4.1 (freshness gap).
7. **Device-key authentication** — §7.3 (transfer resistance requires
   verifier challenge; not implemented).

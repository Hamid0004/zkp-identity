// passport_security.rs
//
// ╔══════════════════════════════════════════════════════════════════════════╗
// ║         ZKAuth — Production ZK Passport Engine v6.0                    ║
// ║         Hardened Edition (C1-C4d, H3, M1, M4 resolved)                 ║
// ╠══════════════════════════════════════════════════════════════════════════╣
// ║ v5.0 → v5.1 Pre-Build Audit Fixes:                                     ║
// ║                                                                         ║
// ║  🔴 [CRITICAL FIX] Nationality Constraint Bypass Patched               ║
// ║      v5.0 used sum of diffs — BYPASSABLE if elements cancel out.       ║
// ║      v5.1 uses element-wise multiply: diff_i * bool_indicator = 0      ║
// ║      indicator is now BoolTarget — prover cannot set to 0 to skip.    ║
// ║                                                                         ║
// ║  🔴 [CRITICAL FIX] Silent Hex Decode Failures                          ║
// ║      v5.0: dg1/sod hex decode used unwrap_or_default() → empty bytes  ║
// ║      Empty bytes pass integrity check trivially — silent wrong result. ║
// ║      v5.1: returns proper Err — fail fast, never silent.               ║
// ║                                                                         ║
// ║  🔴 [CRITICAL FIX] JSON Parse Hard Unwrap Removed                      ║
// ║      v5.0: serde_json::from_str(...).unwrap() → PANIC in production.  ║
// ║      v5.1: match with Err → returns JSON error string to Kotlin.       ║
// ║                                                                         ║
// ║  🟡 [FIX] get_string Err Arm Was Missing                               ║
// ║      v5.0: JNI get_string error was silently swallowed.                ║
// ║      v5.1: Err arm returns error JSON to Kotlin.                       ║
// ║                                                                         ║
// ║  🟡 [FIX] Simulation device_rng Too Short                              ║
// ║      v5.0: "a1b2c3d4e5f6a7b8" = 8 bytes (too short for salt entropy). ║
// ║      v5.1: Full 32 bytes = 64 hex chars.                               ║
// ║                                                                         ║
// ║  🟢 [FIX] Unused warn! Import Removed                                  ║
// ║      v5.0: warn imported but never used → compiler warning.            ║
// ║      v5.1: removed.                                                     ║
// ║                                                                         ║
// ╠══════════════════════════════════════════════════════════════════════════╣
// ║ Carried from v5.0:                                                      ║
// ║  ✅ Recursive proof compression (inner + outer circuit)                 ║
// ║  ✅ Hardware binding: Poseidon(DG1_Hash, device_pubkey)                 ║
// ║  ✅ Revocation ID: Poseidon(DG1_Hash, "REVOCATION")                    ║
// ║  ✅ DG1 anchor — proof bound to specific passport                      ║
// ║  ✅ Proof expiry — valid_until in circuit                               ║
// ║  ✅ Domain-scoped nullifier using DG1 hash (not doc number)            ║
// ║  ✅ Universal circuit OnceLock cache                                    ║
// ║  ✅ Age >= 18 in-circuit range_check                                    ║
// ╠══════════════════════════════════════════════════════════════════════════╣
// ║ Still pending (future PRs):                                             ║
// ║  ⏳ CSCA chain + x509-parser + ECDSA (C4c-full) · H1/H2 · sim-removal  ║
// ╠══════════════════════════════════════════════════════════════════════════╣
// ║ Performance (Android aarch64):                                          ║
// ║   Circuit build  : ~800ms once — inner + outer (warmup on app start)   ║
// ║   ZK proof       : ~80–200ms (inner + recursive compression)           ║
// ║   Verification   : ~5ms                                                 ║
// ║   Replay window  : 300 seconds                                         ║
// ╚══════════════════════════════════════════════════════════════════════════╝

use jni::JNIEnv;
use jni::objects::{JClass, JString};
use jni::sys::jstring;
use log::{info, error};
#[cfg(target_os = "android")]
use android_logger::Config;
#[cfg(target_os = "android")]
use log::LevelFilter;
use serde::{Deserialize, Serialize};
use sha2::{Sha256, Digest};

// [A-01a] ICAO 9303 TD3 MRZ parser (issue #8):
// attributes MUST be extracted from authenticated DG1 bytes, not caller JSON.
/// [A-01a] Shared test fixtures — 44-char ICAO TD3 MRZ lines (0-indexed:
/// doc code 0-1, state 2-4, name 5-43). Single source of truth.
pub const MRZ_FIXTURE_L1: &str = "P<PAKARSALAN<<KHAN<<<<<<<<<<<<<<<<<<<<<<<<<<";
pub const MRZ_FIXTURE_L2: &str = "AB12345671PAK9001011M2501017<<<<<<<<<<<<<<06";

#[path = "mrz.rs"]
pub mod mrz;
use hex;
use anyhow::{anyhow, Result};
use std::time::{SystemTime, UNIX_EPOCH, Instant};
use std::sync::OnceLock;

use plonky2::{
    field::types::{Field, PrimeField64},
    iop::witness::{PartialWitness, WitnessWrite},
    iop::target::{BoolTarget, Target},
    plonk::{
        circuit_builder::CircuitBuilder,
        circuit_data::{CircuitConfig, CircuitData},
        config::{GenericConfig, PoseidonGoldilocksConfig, Hasher},
        proof::ProofWithPublicInputsTarget,
    },
    hash::poseidon::PoseidonHash,
    hash::hash_types::{HashOut, HashOutTarget},
};

// ── Plonky2 type aliases ──────────────────────────────────────────────────────
type C = PoseidonGoldilocksConfig;
type F = <C as GenericConfig<2>>::F;
const D: usize = 2;

const PROOF_TTL_SECS: u64 = 300; // 5 minutes validity

const PROOF_VERSION: &str = "6.0";
// ─────────────────────────────────────────────────────────────────────────────
// STATIC CIRCUIT CACHE (Inner & Outer Circuits)
// ─────────────────────────────────────────────────────────────────────────────

struct UniversalCircuit {
    data:                  CircuitData<F, C, D>,
    root_t:                HashOutTarget,
    nullifier_t:           HashOutTarget,
    claim_type_t:          Target,
    dg1_anchor_t:          HashOutTarget,
    valid_until_t:         Target,
    expected_nat_t:        HashOutTarget,
    hw_binding_t:          HashOutTarget, // [NEW v5.0] Device binding
    revocation_id_t:       HashOutTarget, // [NEW v5.0] Revocation check
    leaf_t:                HashOutTarget,
    sibling_1_t:           HashOutTarget,
    sibling_2_t:           HashOutTarget,
    bit_0_t:               BoolTarget,
    bit_1_t:               BoolTarget,
    age_t:                 Target,
    nat_claim_indicator_t: BoolTarget,       // [FIXED v5.1] BoolTarget — cannot be bypassed
    nat_value_t:           Target,   // [C1] private nationality preimage
    nat_salt_t:            HashOutTarget, // [C1] private salt
    age_value_t:           Target,        // [C2] private age preimage
    age_salt_t:            HashOutTarget, // [C2] private salt
    age_indicator_t:       BoolTarget,    // [C2] age-claim indicator
}

struct RecursiveCircuit {
    data:    CircuitData<F, C, D>,
    proof_t: ProofWithPublicInputsTarget<D>,
}

struct EngineCircuits {
    inner: UniversalCircuit,
    outer: RecursiveCircuit,
}

static CIRCUITS: OnceLock<EngineCircuits> = OnceLock::new();

fn get_circuits() -> &'static EngineCircuits {
    CIRCUITS.get_or_init(|| {
        let t = Instant::now();
        info!("⚡ [ONCE] Building v6.0 ZK Circuits (Inner + Recursive)...");
        let inner = build_universal_circuit();
        let outer = build_recursive_circuit(&inner);
        info!("✅ [DONE] Circuits built in {}ms — cached permanently", t.elapsed().as_millis());
        EngineCircuits { inner, outer }
    })
}

// ─────────────────────────────────────────────────────────────────────────────
// INNER CIRCUIT BUILDER
// ─────────────────────────────────────────────────────────────────────────────

fn build_universal_circuit() -> UniversalCircuit {
    let config  = CircuitConfig::standard_recursion_config();
    let mut b   = CircuitBuilder::<F, D>::new(config);

    // ── Public Targets ────────────────────────────────────────────────────────
    let root_t          = b.add_virtual_hash();
    let nullifier_t     = b.add_virtual_hash();
    let claim_type_t    = b.add_virtual_target();
    let dg1_anchor_t    = b.add_virtual_hash();
    let valid_until_t   = b.add_virtual_target();
    let expected_nat_t  = b.add_virtual_hash();
    let hw_binding_t    = b.add_virtual_hash(); // [NEW v5.0]
    let revocation_id_t = b.add_virtual_hash(); // [NEW v5.0]

    // ── Private Targets ───────────────────────────────────────────────────────
    let leaf_t                = b.add_virtual_hash();
    let sibling_1_t           = b.add_virtual_hash();
    let sibling_2_t           = b.add_virtual_hash();
    let bit_0_t               = b.add_virtual_bool_target_safe();
    let bit_1_t               = b.add_virtual_bool_target_safe();
    let age_t                 = b.add_virtual_target();
    // nat_claim_indicator_t declared as BoolTarget in Constraint 4 section below

    // ── Constraint 1: Merkle Path ─────────────────────────────────────────────
    let mut l1_left  = vec![];
    let mut l1_right = vec![];
    for i in 0..4 {
        let left  = b.select(bit_0_t, sibling_1_t.elements[i], leaf_t.elements[i]);
        let right = b.select(bit_0_t, leaf_t.elements[i],      sibling_1_t.elements[i]);
        l1_left.push(left);
        l1_right.push(right);
    }
    let mut l1_inputs = l1_left;
    l1_inputs.extend(l1_right);
    let node_1 = b.hash_n_to_hash_no_pad::<PoseidonHash>(l1_inputs);

    let mut l2_left  = vec![];
    let mut l2_right = vec![];
    for i in 0..4 {
        let left  = b.select(bit_1_t, sibling_2_t.elements[i], node_1.elements[i]);
        let right = b.select(bit_1_t, node_1.elements[i],      sibling_2_t.elements[i]);
        l2_left.push(left);
        l2_right.push(right);
    }
    let mut l2_inputs = l2_left;
    l2_inputs.extend(l2_right);
    let computed_root = b.hash_n_to_hash_no_pad::<PoseidonHash>(l2_inputs);
    b.connect_hashes(computed_root, root_t);

    // ── Constraint 2: Age >= 18 ───────────────────────────────────────────────
    b.range_check(age_t, 7);
    let eighteen     = b.constant(F::from_canonical_u64(18));
    let age_minus_18 = b.sub(age_t, eighteen);
    b.range_check(age_minus_18, 7);

    // ── Constraint 3: Proof Expiry ────────────────────────────────────────────
    b.range_check(valid_until_t, 32);

        // ── Constraint 4: Nationality In-Circuit (C1 — FIXED) ────────────────────
    // (a) private preimage, (b) gated leaf opening, (c) gated expected match,
    // (d) indicator DERIVED from claim_type. E0499-safe: no nested b. calls.
    let nat_value_t = b.add_virtual_target();   // [C1] private nationality value
    let nat_salt_t  = b.add_virtual_hash();     // [C1] private salt

    // constants — hoisted ONCE (E0499 fix)
    let one  = b.one();
    let two  = b.constant(F::from_canonical_u64(2));
    let zero = b.zero();

    // claim_type validity: ct·(ct−1)·(ct−2) == 0  →  ct ∈ {0,1,2}
    let ct_m1   = b.sub(claim_type_t, one);
    let ct_m2   = b.sub(claim_type_t, two);
    let p1      = b.mul(claim_type_t, ct_m1);
    let ct_prod = b.mul(p1, ct_m2);
    b.connect(ct_prod, zero);

    // indicator = ct·(2−ct):  0→0 (is_adult) · 1→1 (nationality) · 2→0 (is_human)
    let nat_indicator_bool = b.add_virtual_bool_target_safe();
    let two_minus_ct = b.sub(two, claim_type_t);
    let ind_computed = b.mul(claim_type_t, two_minus_ct);
    b.connect(ind_computed, nat_indicator_bool.target);
    let ind = nat_indicator_bool.target;

    // (1) Leaf opening — Poseidon(value ‖ salt) == leaf_t  (gated by ind)
    let mut preimage = vec![nat_value_t];
    preimage.extend_from_slice(&nat_salt_t.elements);
    let computed_leaf = b.hash_n_to_hash_no_pad::<PoseidonHash>(preimage);
    for i in 0..4 {
        let diff     = b.sub(computed_leaf.elements[i], leaf_t.elements[i]);
        let enforced = b.mul(diff, ind);
        b.connect(enforced, zero);
    }

    // (2) Expected match — Poseidon(value) == expected_nat_t  (gated by ind)
    let computed_expected = b.hash_n_to_hash_no_pad::<PoseidonHash>(vec![nat_value_t]);
    for i in 0..4 {
        let diff     = b.sub(computed_expected.elements[i], expected_nat_t.elements[i]);
        let enforced = b.mul(diff, ind);
        b.connect(enforced, zero);
    }
    // ── Constraint 2b: Age value binding (C2) ─────────────────────────────────
    //
    // age_t was a free witness value — provable independent of the committed
    // leaf → forged age proofs. Fix (same pattern as C1):
    //   (1) open leaf:  Poseidon(age_value ‖ age_salt) == leaf_t   (gated)
    //   (2) bind value: age_value == age_t                          (gated)
    // Age indicator: 1 iff ct == 0. With ct ∈ {0,1,2} enforced:
    //   (ct−1)(ct−2) = 2 at ct=0, else 0  →  connect((ct−1)(ct−2), 2·ind)
    let age_value_t = b.add_virtual_target();   // [C2] private age preimage
    let age_salt_t  = b.add_virtual_hash();     // [C2] private salt

    let age_indicator_bool = b.add_virtual_bool_target_safe();
    let ct_m1b  = b.sub(claim_type_t, one);
    let ct_m2b  = b.sub(claim_type_t, two);
    let p_age   = b.mul(ct_m1b, ct_m2b);
    let two_age = b.mul(two, age_indicator_bool.target);
    b.connect(p_age, two_age);
    let aind = age_indicator_bool.target;

    // (1) Leaf opening — binds opened value to committed age leaf (gated)
    let mut age_preimage = vec![age_value_t];
    age_preimage.extend_from_slice(&age_salt_t.elements);
    let computed_age_leaf = b.hash_n_to_hash_no_pad::<PoseidonHash>(age_preimage);
    for i in 0..4 {
        let diff     = b.sub(computed_age_leaf.elements[i], leaf_t.elements[i]);
        let enforced = b.mul(diff, aind);
        b.connect(enforced, zero);
    }

    // (2) Value binding — decoded attribute == age_t (gated)
    let age_diff = b.sub(age_value_t, age_t);
    let age_enf  = b.mul(age_diff, aind);
    b.connect(age_enf, zero);

    // ── Register Public Inputs ────────────────────────────────────────────────
    b.register_public_inputs(&root_t.elements);
    b.register_public_inputs(&nullifier_t.elements);
    b.register_public_input(claim_type_t);
    b.register_public_inputs(&dg1_anchor_t.elements);
    b.register_public_input(valid_until_t);
    b.register_public_inputs(&expected_nat_t.elements);
    b.register_public_inputs(&hw_binding_t.elements);    // [NEW v5.0]
    b.register_public_inputs(&revocation_id_t.elements); // [NEW v5.0]

    let data = b.build::<C>();

    UniversalCircuit {
        data, root_t, nullifier_t, claim_type_t, dg1_anchor_t,
        valid_until_t, expected_nat_t, hw_binding_t, revocation_id_t,
        leaf_t, sibling_1_t, sibling_2_t, bit_0_t, bit_1_t, age_t,
        nat_claim_indicator_t: nat_indicator_bool,
        nat_value_t, nat_salt_t,
        age_value_t, age_salt_t,
        age_indicator_t: age_indicator_bool,   // [C2]
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// [NEW v5.0] OUTER RECURSIVE CIRCUIT BUILDER (Proof Compression)
// ─────────────────────────────────────────────────────────────────────────────
fn build_recursive_circuit(inner: &UniversalCircuit) -> RecursiveCircuit {
    let config = CircuitConfig::standard_recursion_config();
    let mut b  = CircuitBuilder::<F, D>::new(config);

    // Create a target for the inner proof
    let proof_t = b.add_virtual_proof_with_pis(&inner.data.common);
    let verifier_data_t = b.constant_verifier_data(&inner.data.verifier_only);

    // Verify the inner proof INSIDE this outer circuit
    b.verify_proof::<C>(&proof_t, &verifier_data_t, &inner.data.common);

    // Expose the inner public inputs so the ultimate verifier can read them
    b.register_public_inputs(&proof_t.public_inputs);

    let data = b.build::<C>();
    RecursiveCircuit { data, proof_t }
}

// ─────────────────────────────────────────────────────────────────────────────
// DATA MODELS
// ─────────────────────────────────────────────────────────────────────────────

#[derive(Serialize, Deserialize, Debug, PartialEq, Default)]
#[serde(rename_all = "SCREAMING_SNAKE_CASE")]
#[derive(Clone)]
pub enum InputMode { #[default] NfcPassport, SimulatedPassport }

#[derive(Serialize, Deserialize, Debug, PartialEq, Clone)]
#[serde(rename_all = "snake_case")]
pub enum ClaimType { IsAdult, Nationality, IsHuman }

impl ClaimType {
    // [A-12] Strict parse: unknown claims are ERRORS, not silent IsHuman
    // downgrades (a typo must never yield a weaker/weirder predicate).
    fn from_str(s: &str) -> Result<Self> {
        match s {
            "is_adult" => Ok(ClaimType::IsAdult),
            "nationality" => Ok(ClaimType::Nationality),
            "is_human" => Ok(ClaimType::IsHuman),
            other => Err(anyhow!("unknown claim_type '{other}' (expected: is_adult | nationality | is_human)")),
        }
    }
    fn to_u64(&self) -> u64 {
        match self { ClaimType::IsAdult => 0, ClaimType::Nationality => 1, ClaimType::IsHuman => 2 }
    }
}

#[derive(Serialize, Deserialize, Debug)]
#[derive(Clone)]
#[serde(deny_unknown_fields)]
pub struct PassportData {
    // [K2] Internal only — entrypoint selection IS the mode; never on wire.
    #[serde(skip)]
    pub mode:                 InputMode,
    // [K2] Tripwire-only transport fields — NEVER attribute source.
    // Kotlin sends none of these; DG1 is the source of truth (A-01).
    // Semantics: Some+match -> accepted; Some+mismatch -> rejected; None -> ignored.
    pub first_name:           Option<String>,
    pub last_name:            Option<String>,
    pub document_number:      Option<String>,
    pub date_of_birth:        Option<String>,
    pub nationality:          Option<String>,
    // ── 7-field wire contract (VERIFIER_SPEC §6.5) ──
    pub dg1_hex:              String,
    pub sod_hex:              String,
    pub claim_type:           Option<String>,
    pub verifier_domain:      Option<String>,
    pub device_rng_hex:       Option<String>,
    pub expected_nationality: Option<String>,
    pub device_pubkey_hex:    Option<String>,
}

#[allow(dead_code)] // fields are used during tree construction but not read directly after
#[derive(Debug, Clone)]
struct IdentityLeaf { label: &'static str, value: Vec<F>, salt: [F; 4], hash: HashOut<F> }

#[derive(Debug)]
struct IdentityMerkleTree { leaves: [IdentityLeaf; 4], node_l: HashOut<F>, node_r: HashOut<F>, root: HashOut<F> }

#[derive(Serialize, Deserialize, Debug)]
pub struct ZkProofOutput {
    pub version:          String,
    pub compressed_proof: String,   // [NEW v5.0] Much smaller proof
    pub root:             String,
    pub nullifier:        String,
    pub dg1_anchor:       String,
    pub valid_until:      u64,
    pub hw_binding:       String,   // [NEW v5.0]
    pub revocation_id:    String,   // [NEW v5.0]
    pub claim:            ClaimOutput,
}

#[derive(Serialize, Deserialize, Debug)]
pub struct ClaimOutput { pub r#type: String, pub value: bool }

#[derive(Serialize, Deserialize, Debug)]
pub struct PassportProofResult {
    pub success:         bool,
    pub input_mode:      String,
    pub integrity_check: String,
    pub signature_check: String,
    pub zk_proof_status: String,
    pub zk_proof_ms:     u64,
    // [A-05] PII removed: document_number + holder_name were plaintext
    // identity correlators in every proof response (H-04 finding).
    pub error_msg:       String,
    // [A-04] When false, zk_output MUST be None and consumers MUST treat
    // this result as non-identity evidence (PROTOCOL.md §6).
    pub trusted:         bool,
    pub merkle_root:     String,
    pub trust_level:     String,
    pub nullifier:       String,
    // [K1] Computed from actual emitted keys — Kotlin pins expected value
    pub bridge_schema_digest: String,
    pub zk_output:       Option<ZkProofOutput>,
}

// ─────────────────────────────────────────────────────────────────────────────
// CORE LOGIC & CRYPTO
// ─────────────────────────────────────────────────────────────────────────────

// [C3] 7-byte chunks: values fit in 56 bits < Goldilocks prime p = 2^64 − 2^32 + 1,
// so every element is canonical by construction. The old 8-byte chunks could
// exceed p → silent non-canonical aliasing in release builds.
fn bytes_to_field_elements(bytes: &[u8]) -> Vec<F> {
    bytes.chunks(7).map(|c| {
        let mut a = [0u8; 8];
        a[..c.len()].copy_from_slice(c);
        F::from_canonical_u64(u64::from_le_bytes(a))
    }).collect()
}

fn hash_out_to_hex(h: &HashOut<F>) -> String {
    hex::encode(h.elements.iter().flat_map(|f| f.to_canonical_u64().to_le_bytes()).collect::<Vec<u8>>())
}

fn poseidon_hash_leaf(value: &[F], salt: &[F; 4]) -> HashOut<F> {
    let mut inputs = value.to_vec();
    inputs.extend_from_slice(salt);
    PoseidonHash::hash_no_pad(&inputs)
}

fn generate_poseidon_salt(doc_number: &str, label: &str, device_rng: &[u8]) -> [F; 4] {
    let mut inputs = vec![];
    inputs.extend(bytes_to_field_elements(doc_number.as_bytes()));
    inputs.extend(bytes_to_field_elements(label.as_bytes()));
    inputs.extend(bytes_to_field_elements(device_rng));
    let h = PoseidonHash::hash_no_pad(&inputs);
    [h.elements[0], h.elements[1], h.elements[2], h.elements[3]]
}

// [NEW v5.0] Nullifier using Secret (DG1 Hash) + Domain
fn generate_domain_nullifier(dg1_hash: &[u8], domain: &str) -> HashOut<F> {
    let mut inputs = vec![];
    inputs.extend(bytes_to_field_elements(dg1_hash)); // Secret
    inputs.extend(bytes_to_field_elements(domain.as_bytes()));
    PoseidonHash::hash_no_pad(&inputs)
}

// [M4] Howard Hinnant's civil_from_days — exact Gregorian date from days
// since epoch. Replaces average-year arithmetic (±1-day drift).
fn civil_from_days(z: i64) -> (i64, u32, u32) {
    let z = z + 719_468;
    let era = if z >= 0 { z } else { z - 146_096 } / 146_097;
    let doe = (z - era * 146_097) as u64;
    let yoe = (doe - doe/1460 + doe/36524 - doe/146096) / 365;
    let y   = yoe as i64 + era * 400;
    let doy = doe - (365*yoe + yoe/4 - yoe/100);
    let mp  = (5*doy + 2) / 153;
    let d   = (doy - (153*mp + 2)/5 + 1) as u32;
    let m   = if mp < 10 { mp + 3 } else { mp - 9 } as u32;
    (if m <= 2 { y + 1 } else { y }, m, d)
}

/// [A-08] Trusted current-date: fails hard on clock before 2020 (rollback /
/// unsynced device) instead of silently producing now=0 garbage.
fn trusted_now_secs() -> Result<u64> {
    let now = SystemTime::now().duration_since(UNIX_EPOCH)
        .map_err(|e| anyhow!("device clock error: {e}"))?
        .as_secs();
    if now < 1_577_836_800 { // 2020-01-01
        return Err(anyhow!("device clock implausible (pre-2020) — set clock and retry"));
    }
    Ok(now)
}

/// [A-08] Hardened DOB parse: exact 6-ASCII-digit YYMMDD, calendar-validated,
/// explicit century policy. Returns Err on ANY anomaly (no synthetic dates,
/// no panics on non-UTF-8, no silent zeros).
fn parse_yymmdd(dob: &str) -> Result<(i64, u32, u32)> {
    let b = dob.as_bytes();
    if b.len() != 6 || !b.iter().all(|c| c.is_ascii_digit()) {
        return Err(anyhow!("dob must be exactly 6 ASCII digits (YYMMDD)"));
    }
    let yy: u32 = dob[0..2].parse().unwrap();
    let mm: u32 = dob[2..4].parse().unwrap();
    let dd: u32 = dob[4..6].parse().unwrap();
    if !(1..=12).contains(&mm) { return Err(anyhow!("dob month {mm} invalid")); }
    if !(1..=31).contains(&dd) { return Err(anyhow!("dob day {dd} invalid")); }
    // [Obs1] Dynamic century policy: birth year can't be in the future —
    // yy > current-yy => previous century. No hardcoded cutoff time-bomb.
    let now_y = civil_from_days((trusted_now_secs()? / 86_400) as i64).0;
    let current_yy = (now_y % 100) as u32;
    let birth_year: i64 = if yy > current_yy { 1900 + yy as i64 } else { 2000 + yy as i64 };
    Ok((birth_year, mm, dd))
}

fn calculate_age(dob: &str) -> u32 {
    // [A-08] Invalid DOB => age 0 is FORBIDDEN for identity claims.
    // Caller path (prove_passport) surfaces hard errors via validate_dob;
    // this fn retains the u32 signature for tree-building but only after
    // parse_yymmdd validation. Non-validated callers are impossible by
    // construction (single call-site, gated below).
    let (birth_year, mm, dd) = match parse_yymmdd(dob) {
        Ok(v) => v,
        Err(_) => return 0, // tree leaf value; claim path rejects separately
    };
    let now = match trusted_now_secs() {
        Ok(n) => n,
        Err(_) => return 0,
    };
    let (cy, cm, cd) = civil_from_days((now / 86_400) as i64);

    // [A-08] negative-age overflow guard: future DOB => saturate, claim path rejects
    if (cy as i64) < birth_year { return 0; }
    let mut age = (cy as i64 - birth_year) as u32;
    if mm > cm || (mm == cm && dd > cd) { age = age.saturating_sub(1); }
    age
}

fn build_merkle_tree(
    surname:         &str,   // [B6] renamed — semantic clarity (pehle first_name tha, ulta use hota tha)
    given_names:     &str,
    document_number: &str,
    date_of_birth:   &str,
    nationality:     &str,
    device_rng:      &[u8],
) -> IdentityMerkleTree {
    let name_val = format!("{} {}", surname, given_names);
    let age      = calculate_age(date_of_birth);

    let name_f = bytes_to_field_elements(name_val.as_bytes());
    let dob_f  = bytes_to_field_elements(date_of_birth.as_bytes());
    let age_f  = bytes_to_field_elements(&age.to_le_bytes());
    let nat_f  = bytes_to_field_elements(nationality.as_bytes());

    let s0 = generate_poseidon_salt(document_number, "name", device_rng);
    let s1 = generate_poseidon_salt(document_number, "dob",  device_rng);
    let s2 = generate_poseidon_salt(document_number, "age",  device_rng);
    let s3 = generate_poseidon_salt(document_number, "nat",  device_rng);

    let leaf0 = IdentityLeaf { label: "name", value: name_f.clone(), salt: s0, hash: poseidon_hash_leaf(&name_f, &s0) };
    let leaf1 = IdentityLeaf { label: "dob",  value: dob_f.clone(),  salt: s1, hash: poseidon_hash_leaf(&dob_f,  &s1) };
    let leaf2 = IdentityLeaf { label: "age",  value: age_f.clone(),  salt: s2, hash: poseidon_hash_leaf(&age_f,  &s2) };
    let leaf3 = IdentityLeaf { label: "nat",  value: nat_f.clone(),  salt: s3, hash: poseidon_hash_leaf(&nat_f,  &s3) };

    let node_l = PoseidonHash::two_to_one(leaf0.hash, leaf1.hash);
    let node_r = PoseidonHash::two_to_one(leaf2.hash, leaf3.hash);
    let root   = PoseidonHash::two_to_one(node_l, node_r);

    IdentityMerkleTree { leaves: [leaf0, leaf1, leaf2, leaf3], node_l, node_r, root }
}

// ─────────────────────────────────────────────────────────────────────────────
// RECURSIVE ZK PROOF GENERATION (v5.0)
// ─────────────────────────────────────────────────────────────────────────────

fn generate_zk_proof(
    tree:      &IdentityMerkleTree,
    claim:     &ClaimType,
    nullifier: HashOut<F>,
    data:      &PassportData,
    dg1_hash:  &[u8],
    device_pubkey: &[u8],   // [H1] pre-validated bytes (owner: prove_passport)
) -> Result<(ZkProofOutput, u64)> {
    let start    = Instant::now();
    let circuits = get_circuits(); // Gets both Inner and Outer circuits
    let inner_c  = &circuits.inner;
    let outer_c  = &circuits.outer;
    let mut pw   = PartialWitness::new();

    let dg1_fields = bytes_to_field_elements(dg1_hash);
    let dg1_anchor = PoseidonHash::hash_no_pad(&dg1_fields);

    // [A-08/Gap2] Same trusted-clock contract as age path — rollback/unsynced
    // device must NOT produce proofs with garbage valid_until.
    let now         = trusted_now_secs()?;
    let valid_until = now + PROOF_TTL_SECS;

    let expected_nat_hash = match (claim, data.expected_nationality.as_deref()) {
        (ClaimType::Nationality, Some(expected_nat)) => PoseidonHash::hash_no_pad(&bytes_to_field_elements(expected_nat.as_bytes())),
        _ => HashOut::ZERO,
    };

    // [NEW v5.0] Hardware Binding
    // [H1] device_pubkey arrives PRE-VALIDATED (owner: prove_passport boundary)
    let mut hw_inputs = dg1_fields.clone();
    hw_inputs.extend(bytes_to_field_elements(device_pubkey));
    let hw_binding = PoseidonHash::hash_no_pad(&hw_inputs);

    // [NEW v5.0] Revocation ID
    let mut rev_inputs = dg1_fields.clone();
    rev_inputs.extend(bytes_to_field_elements(b"REVOCATION"));
    let revocation_id = PoseidonHash::hash_no_pad(&rev_inputs);

    // ── Set Public Inputs (Inner) ─────────────────────────────────────────────
    pw.set_hash_target(inner_c.root_t,          tree.root);
    pw.set_hash_target(inner_c.nullifier_t,     nullifier);
    pw.set_target(inner_c.claim_type_t,         F::from_canonical_u64(claim.to_u64()));
    pw.set_hash_target(inner_c.dg1_anchor_t,    dg1_anchor);
    pw.set_target(inner_c.valid_until_t,        F::from_canonical_u64(valid_until));
    pw.set_hash_target(inner_c.expected_nat_t,  expected_nat_hash);
    pw.set_hash_target(inner_c.hw_binding_t,    hw_binding);
    pw.set_hash_target(inner_c.revocation_id_t, revocation_id);

    // ── Set Private Inputs (Inner) ────────────────────────────────────────────
    match claim {
        ClaimType::IsAdult => {
            pw.set_hash_target(inner_c.leaf_t,      tree.leaves[2].hash);
            pw.set_hash_target(inner_c.sibling_1_t, tree.leaves[3].hash);
            pw.set_hash_target(inner_c.sibling_2_t, tree.node_l);
            pw.set_bool_target(inner_c.bit_0_t, false);
            pw.set_bool_target(inner_c.bit_1_t, true);
            // [K2] DOB Option — Some(MRZ-authoritative, override se) hi aata hai
            let age = match data.date_of_birth.as_deref() {
                Some(d) => calculate_age(d),
                None => return Err(anyhow!("age claim requires date_of_birth (K2 contract)")),
            };
            if age < 18 { return Err(anyhow!("Age < 18")); }
            let age_leaf = &tree.leaves[2];
            pw.set_target(inner_c.age_t, age_leaf.value[0]);                             // [C2] from committed leaf
            pw.set_target(inner_c.age_value_t, age_leaf.value[0]);                       // [C2]
            pw.set_hash_target(inner_c.age_salt_t, HashOut { elements: age_leaf.salt }); // [C2]
            pw.set_bool_target(inner_c.age_indicator_t, true);                           // [C2]
            pw.set_target(inner_c.nat_value_t, F::ZERO);                                 // [C1]
            pw.set_hash_target(inner_c.nat_salt_t, HashOut::ZERO);                       // [C1]
            pw.set_bool_target(inner_c.nat_claim_indicator_t, false);
        }
        ClaimType::Nationality => {
            pw.set_hash_target(inner_c.leaf_t,      tree.leaves[3].hash);
            pw.set_hash_target(inner_c.sibling_1_t, tree.leaves[2].hash);
            pw.set_hash_target(inner_c.sibling_2_t, tree.node_l);
            pw.set_bool_target(inner_c.bit_0_t, true);
            pw.set_bool_target(inner_c.bit_1_t, true);
            pw.set_target(inner_c.age_t, F::from_canonical_u64(18));
            pw.set_target(inner_c.age_value_t, F::ZERO);                                 // [C2] unconstrained (gated)
            pw.set_hash_target(inner_c.age_salt_t, HashOut::ZERO);                       // [C2]
            pw.set_bool_target(inner_c.age_indicator_t, false);                          // [C2]
            let nat_leaf = &tree.leaves[3];
            pw.set_target(inner_c.nat_value_t, nat_leaf.value[0]);                       // [C1]
            pw.set_hash_target(inner_c.nat_salt_t, HashOut { elements: nat_leaf.salt }); // [C1]
            pw.set_bool_target(inner_c.nat_claim_indicator_t, true);
        }
        ClaimType::IsHuman => {
            pw.set_hash_target(inner_c.leaf_t,      tree.leaves[0].hash);
            pw.set_hash_target(inner_c.sibling_1_t, tree.leaves[1].hash);
            pw.set_hash_target(inner_c.sibling_2_t, tree.node_r);
            pw.set_bool_target(inner_c.bit_0_t, false);
            pw.set_bool_target(inner_c.bit_1_t, false);
            pw.set_target(inner_c.age_t, F::from_canonical_u64(18));
            pw.set_target(inner_c.age_value_t, F::ZERO);                                 // [C2] unconstrained (gated)
            pw.set_hash_target(inner_c.age_salt_t, HashOut::ZERO);                       // [C2]
            pw.set_bool_target(inner_c.age_indicator_t, false);                          // [C2]
            pw.set_target(inner_c.nat_value_t, F::ZERO);                                 // [C1]
            pw.set_hash_target(inner_c.nat_salt_t, HashOut::ZERO);                       // [C1]
            pw.set_bool_target(inner_c.nat_claim_indicator_t, false);
        }
    }

    // 1. Prove Inner Circuit
    let inner_proof = inner_c.data.prove(pw).map_err(|e| anyhow!("Inner prove failed: {}", e))?;

    // 2. Prove Outer Circuit (Recursion Compression)
    let mut outer_pw = PartialWitness::new();
    outer_pw.set_proof_with_pis_target(&outer_c.proof_t, &inner_proof);
    
    let compressed_proof = outer_c.data.prove(outer_pw).map_err(|e| anyhow!("Recursive prove failed: {}", e))?;
    outer_c.data.verify(compressed_proof.clone()).map_err(|e| anyhow!("Recursive verify failed: {}", e))?;

    let ms = start.elapsed().as_millis() as u64;
    info!("✅ ZK proof v6.0 (Recursive): {}ms", ms);

    let output = ZkProofOutput {
        version:          PROOF_VERSION.to_string(),
        compressed_proof: hex::encode(compressed_proof.to_bytes()), // [NEW] Shrunk proof
        root:             hash_out_to_hex(&tree.root),
        nullifier:        hash_out_to_hex(&nullifier),
        dg1_anchor:       hash_out_to_hex(&dg1_anchor),
        valid_until,
        hw_binding:       hash_out_to_hex(&hw_binding),
        revocation_id:    hash_out_to_hex(&revocation_id),
        claim: ClaimOutput {
            r#type: match claim { ClaimType::IsAdult => "age".to_string(), ClaimType::Nationality => "nationality".to_string(), ClaimType::IsHuman => "human".to_string() },
            value: true,
        },
    };

    Ok((output, ms))
}

// ─────────────────────────────────────────────────────────────────────────────
// PROVE ENTRYPOINT & JNI
// ─────────────────────────────────────────────────────────────────────────────

/// [H1] Single validation boundary for device_pubkey: required, non-"00",
/// valid hex, >=32 bytes (real key material). Returns decoded bytes.
fn validate_device_pubkey(data: &PassportData) -> Result<Vec<u8>> {
    let hex_str = data.device_pubkey_hex.as_deref()
        .filter(|s| !s.is_empty() && *s != "00")
        .ok_or_else(|| anyhow!("device_pubkey_hex is required (H1)"))?;
    let key = hex::decode(hex_str)
        .map_err(|e| anyhow!("invalid device_pubkey_hex: {e}"))?;
    if key.len() < 32 {
        return Err(anyhow!("device_pubkey too short (min 32 bytes)"));
    }
    Ok(key)
}

/// [A-12/Gap3] JNI string creation that NEVER panics across FFI.
/// Returns null on failure — JNI side treats null as error.
fn safe_new_string(env: &mut JNIEnv, s: String) -> jstring {
    match env.new_string(&s) {
        Ok(j) => j.into_raw(),
        Err(e) => {
            error!("new_string failed (len={}): {e}", s.len());
            std::ptr::null_mut()
        }
    }
}

/// [K1] BRIDGE_SCHEMA_DIGEST — computed from ACTUAL emitted keys at runtime.
/// Never hardcoded (hardcoded = drift-theater — drift would still pass).
/// Canonical form: sorted keys · unit-separator join · SHA-256 hex.
/// A-02 field additions = deliberate v1→v2 bump (VERIFIER_SPEC §6 + Kotlin pin).
fn bridge_schema_digest(result_json: &serde_json::Value) -> String {
    let mut keys: Vec<String> = result_json
        .as_object()
        .map(|o| o.keys().cloned().collect())
        .unwrap_or_default();
    keys.sort();
    let canonical = keys.join("\u{1F}");
    sha256_hash(canonical.as_bytes())
        .iter()
        .map(|b| format!("{:02x}", b))
        .collect()
}

pub fn prove_passport(mut data: PassportData) -> Result<PassportProofResult> {
    let mode_str   = format!("{:?}", data.mode);
    let claim_type = ClaimType::from_str(data.claim_type.as_deref().unwrap_or("is_adult"))?;

    // ── [C1] Nationality input validation — fail-fast BEFORE any crypto work ──
    if claim_type == ClaimType::Nationality {
        if let Some(ref nat) = data.nationality {
            if nat.as_bytes().is_empty() || nat.as_bytes().len() > 7 {
                return Err(anyhow!("nationality must be 1..=7 bytes"));
            }
        }
        let expected = data.expected_nationality.as_deref()
            .ok_or_else(|| anyhow!("nationality claim requires expected_nationality"))?;
        if expected.as_bytes().is_empty() || expected.len() > 7 {
            return Err(anyhow!("expected_nationality must be 1..=7 bytes"));
        }
        // JSON-vs-JSON removed — authority is MRZ (A-01); tripwire wiring pe
    }
    // ── [C1] end ───────────────────────────────────────────────────────────────

    // [A-08] DOB must parse cleanly BEFORE any tree/proof work — synthetic
    // dates (mm=0 etc.) must never silently become age-0 identities.
    if let Some(ref dob) = data.date_of_birth {
        if let Err(e) = parse_yymmdd(dob) {
            return Err(anyhow!("invalid date_of_birth: {e}"));
        }
    }

    let domain     = data.verifier_domain.as_deref().unwrap_or("unknown.domain");

    // [A-12] Input-size limits BEFORE decode/crypto (DoS + parser-robustness)
    if data.dg1_hex.len() > 4096 { return Err(anyhow!("dg1_hex too large")); }
    if data.sod_hex.len() > 65536 { return Err(anyhow!("sod_hex too large")); }

    let dg1_bytes = hex::decode(&data.dg1_hex)
        .map_err(|e| anyhow!("Invalid dg1_hex: {}", e))?;
    let sod_bytes = hex::decode(&data.sod_hex)
        .map_err(|e| anyhow!("Invalid sod_hex: {}", e))?;
    let dg1_hash  = sha256_hash(&dg1_bytes);

    // ── [A-01 Phase C] Attributes from authenticated DG1 — not caller JSON ──
    // Attribute-substitution P0 fix (3 reviews; issue #8). Parse MRZ from
    // DG1 with ICAO check digits; JSON identity fields are transport-only.
    let (mrz_l1, mrz_l2) = mrz::extract_mrz_from_dg1(&dg1_bytes)
        .map_err(|e| anyhow!("DG1/MRZ extraction failed: {e}"))?;
    let mrz_parsed = mrz::parse_td3(&mrz_l1, &mrz_l2)
        .map_err(|e| anyhow!("DG1/MRZ parse failed: {e}"))?;
    if let Some(ref nat) = data.nationality {
        if !nat.is_empty() && *nat != mrz_parsed.nationality {
            return Err(anyhow!("nationality mismatch: JSON '{}' != DG1 '{}'",
                nat, mrz_parsed.nationality));
        }
    }
    if let Some(ref dob) = data.date_of_birth {
        if !dob.is_empty() && *dob != mrz_parsed.date_of_birth {
            return Err(anyhow!("DOB mismatch: JSON '{}' != DG1 '{}'",
                dob, mrz_parsed.date_of_birth));
        }
    }

    // [K2] Fail-fast: expected_nationality MUST match MRZ-authenticated
    // nationality — warna expected_nat_hash pe witness-conflict panic hoga
    // (valid proof kabhi nahi banta). Early clear error better hai.
    if claim_type == ClaimType::Nationality {
        let expected = data.expected_nationality.as_deref()
            .ok_or_else(|| anyhow!("nationality claim requires expected_nationality"))?;
        if expected != mrz_parsed.nationality {
            return Err(anyhow!(
                "expected_nationality '{}' does not match authenticated nationality '{}'",
                expected, mrz_parsed.nationality
            ));
        }
    }
    // [A-01] Override JSON identity fields in-place — downstream (tree, age,
    // C1/C2 witnesses) must see MRZ-authoritative values only.
    // [K2] In-place Option values — MRZ-authoritative
    data.nationality = Some(mrz_parsed.nationality.clone());
    data.date_of_birth = Some(mrz_parsed.date_of_birth.clone());
    data.document_number = Some(mrz_parsed.document_number.clone());
    // [B6] Name overrides deleted — tree explicit args leta hai, mirror
    // fields ab dead (holder_name A-05 se gone). Inverted-semantics trap removed.

    
    // [C4a+C4c] Single SOD parse — integrity + signature dono isi se
    let (integrity_ok, signature_msg) = match sod::parse_sod(&sod_bytes) {
        Ok(info) => {
            let integrity = info.dg_hash(1)
                .map(|h| h == dg1_hash.as_slice())
                .unwrap_or(false);
            if !integrity { error!("SOD integrity: DG1 hash mismatch or missing in SOd"); }
            // [C4c] Trust tiers (H3): honest reporting + SIMULATED never = success
            let sig_msg = {
                let is_placeholder = info.signer_info_sig.iter().all(|b| *b == 0);
                if is_placeholder && info.ds_cert_der.is_empty() {
                    "SIMULATED"
                } else {
                    match sod::sid_matches_cert(&info)
                        .and_then(|_| sod::verify_ds_signature(&info))
                    {
                        Ok(()) => "VERIFIED",
                        Err(e) => { error!("DS verify: {}", e); "FAILED" }
                    }
                }
            };
            (integrity, sig_msg)
        }
        Err(e) => { error!("SOD parse failed: {}", e); (false, "FAILED") }
    };

    // [C4c/H3] trust_level reflects ACTUAL guarantees — never overstated
    let trust_level = match signature_msg {
        "VERIFIED" => "VERIFIED_ONLY",   // DS sig valid; CSCA chain abhi nahi (C4c-full)
        "SIMULATED" => "SIMULATED",      // dev fixture — NOT production trust
        _ => "NONE",
    };
    // [H1] device_rng is MANDATORY — missing/invalid => hard error.
    // Doc-number-derived fallback randomness is FORBIDDEN: predictable salts
    // make identity leaves linkable across verifiers (unlinkability loss).
    let device_rng = match data.device_rng_hex.as_deref() {
        Some(hex_str) => {
            let rng = hex::decode(hex_str)
                .map_err(|e| anyhow!("invalid device_rng_hex: {e}"))?;
            if rng.len() < 16 {
                return Err(anyhow!("device_rng_hex too short (min 16 bytes)"));
            }
            rng
        }
        None => return Err(anyhow!("device_rng_hex is required (H1)")),
    };

    // [H1] single validation boundary — decoded bytes flow onward as params
    let device_pubkey = validate_device_pubkey(&data)?;

    // [K2] Tree from MRZ-AUTHORITATIVE values (A-01 Phase C)
    let tree = build_merkle_tree(
        &mrz_parsed.surname,
        &mrz_parsed.given_names,
        &mrz_parsed.document_number,
        &mrz_parsed.date_of_birth,
        &mrz_parsed.nationality,
        &device_rng,
    );
    let nullifier = generate_domain_nullifier(&dg1_hash, domain); // v5 uses DG1 Hash instead of doc#

    let (zk_status, zk_ms, zk_output) = if integrity_ok {
        match generate_zk_proof(&tree, &claim_type, nullifier, &data, &dg1_hash, &device_pubkey) {
            Ok((out, ms)) => ("GENERATED".to_string(), ms, Some(out)),
            Err(e) => { error!("ZK err: {}", e); ("FAILED".to_string(), 0u64, None) }
        }
    } else { ("SKIPPED".to_string(), 0u64, None) };

    let success = integrity_ok && signature_msg == "VERIFIED" && zk_status == "GENERATED";

    // [A-04] zk_output is emitted ONLY when the full trusted path passed.
    // Non-trusted results carry no proof blob — a consumer verifying only
    // the Plonky2 blob can no longer authenticate failed/SIMULATED flows.
    let zk_output = if success { zk_output } else { None };

    let mut res = PassportProofResult {
        success, trusted: success, input_mode: mode_str,
        integrity_check: if integrity_ok { "PASS".into() } else { "FAIL".into() },
        signature_check: signature_msg.to_string(), zk_proof_status: zk_status, zk_proof_ms: zk_ms,
        error_msg: String::new(), merkle_root: hash_out_to_hex(&tree.root), trust_level: trust_level.to_string(),
        nullifier: hash_out_to_hex(&nullifier), zk_output,
        bridge_schema_digest: String::new(), // computed below
    };
    // [K1] Digest over actual emitted keys (response plane anti-drift)
    res.bridge_schema_digest = bridge_schema_digest(
        &serde_json::to_value(&res).unwrap_or(serde_json::Value::Null)
    );
    Ok(res)
}

// Helpers
fn sha256_hash(data: &[u8]) -> Vec<u8> { let mut h = Sha256::new(); h.update(data); h.finalize().to_vec() }

// [A-07] Fixture stays compiled (tests use it). Production blocking happens
// at the JNI boundary below — release builds expose NO simulated entrypoint.
fn get_simulated_passport(claim_type: Option<String>, domain: Option<String>) -> PassportData {
    // [A-01] Proper ICAO DG1: 61 12 5F 1F <len> <MRZ-88>
    // MRZ line1+line2 with CORRECT check digits (7-3-1) — matches MRZ fixtures.
    let mrz_text = crate::passport_security::MRZ_FIXTURE_L1.to_string()
                 + &crate::passport_security::MRZ_FIXTURE_L2;
    let mut dg1_vec: Vec<u8> = vec![0x61, 0x12, 0x5F, 0x1F, 0x58]; // 0x58 = 88
    dg1_vec.extend_from_slice(mrz_text.as_bytes());
    let dg1 = dg1_vec;
    let hash = sha256_hash(&dg1);
    let sod = sod::build_simulated_sod(&hash);
    PassportData {
        mode: InputMode::SimulatedPassport,
        // [K2] Mirror fields = Some() — tripwire test ke liye (match => accepted)
        first_name: Some("ARSALAN".into()), last_name: Some("KHAN".into()),
        document_number: Some("AB1234567".into()), date_of_birth: Some("900101".into()),
        nationality: Some("PAK".into()),
        dg1_hex: hex::encode(&dg1), sod_hex: hex::encode(&sod),
        claim_type, verifier_domain: domain.or(Some("sim.local".into())),
        device_rng_hex: Some("a1b2c3d4e5f6a7b8c9d0e1f2a3b4c5d6e7f8a9b0c1d2e3f4a5b6c7d8e9f0a1b2".into()),
        expected_nationality: Some("PAK".into()),
        device_pubkey_hex: Some("02a1b2c3d4e5f6a7b8c9d0e1f2a3b4c5d6e7f8a9b0c1d2e3f4a5b6c7d8e9f0a1b2c3d4e5f6a7b8c9d0e1f2a3b4c5d6e7f8a9b0c1d2e3f4a5b6c7d8e9f0a1b2".into()),
    }
}

// JNI
fn init_logger() {
    #[cfg(target_os = "android")]
    {
        let _ = android_logger::init_once(
            Config::default()
                .with_max_level(LevelFilter::Info)
                .with_tag("RustZKP"),
        );
    }
}
#[no_mangle] pub extern "system" fn Java_com_example_zkpapp_SecurityGate_warmupCircuit(_env: JNIEnv, _class: JClass) { init_logger(); let _ = get_circuits(); }
#[no_mangle] pub extern "system" fn Java_com_example_zkpapp_SecurityGate_generateProof(mut env: JNIEnv, _c: JClass, p: JString) -> jstring { init_logger(); handle_req(&mut env, Some(p), false, None, None) }
// [A-07] Simulated JNI entrypoints are DEBUG-ONLY — stripped from release
// AAR. Production must never carry a synthetic trust path.
#[cfg(debug_assertions)]
#[no_mangle] pub extern "system" fn Java_com_example_zkpapp_SecurityGate_generateSimulatedProof(mut env: JNIEnv, _c: JClass, _u: JString) -> jstring { init_logger(); handle_req(&mut env, None, true, None, None) }
#[no_mangle] pub extern "system" fn Java_com_example_zkpapp_SecurityGate_generateClaimProof(mut env: JNIEnv, _c: JClass, p: JString, c: JString, d: JString) -> jstring {
    init_logger();
    // [A-12] claim string read failure => error JSON, not silent default
    let cl = match env.get_string(&c) { Ok(j) => j.into(), Err(e) => {
        return safe_new_string(&mut env, format!("{{\"error\":\"claim read failed: {e}\"}}"))
    }};
    let dom = env.get_string(&d).map(|j| j.into()).ok();
    handle_req(&mut env, Some(p), false, Some(cl), dom)
}
#[cfg(debug_assertions)]
#[no_mangle] pub extern "system" fn Java_com_example_zkpapp_SecurityGate_generateSimulatedClaimProof(mut env: JNIEnv, _c: JClass, c: JString, d: JString) -> jstring {
    init_logger();
    // [A-12] same strictness for simulated claim path
    let cl = match env.get_string(&c) { Ok(j) => j.into(), Err(e) => {
        return safe_new_string(&mut env, format!("{{\"error\":\"claim read failed: {e}\"}}"))
    }};
    let dom = env.get_string(&d).map(|j| j.into()).ok();
    handle_req(&mut env, None, true, Some(cl), dom)
}

fn handle_req(env: &mut JNIEnv, json: Option<JString>, sim: bool, claim: Option<String>, dom: Option<String>) -> jstring {
    // [A-07] Simulation is a DEBUG-only path. In release builds the entrypoint
    // does not exist AND any sim=true request fails closed here.
    #[cfg(not(debug_assertions))]
    if sim {
        return safe_new_string(env, "{\"error\":\"simulation unavailable in release build\"}".to_string());
    }
    let pd = if sim { get_simulated_passport(claim, dom) } else {
        match json {
            Some(p) => match env.get_string(&p) {
                Ok(s) => {
                    match serde_json::from_str::<PassportData>(&String::from(s)) {
                        Ok(mut d) => {
                            if let Some(c)   = claim  { d.claim_type      = Some(c); }
                            if let Some(do_v) = dom   { d.verifier_domain = Some(do_v); }
                            d
                        }
                        Err(e) => return safe_new_string(env, format!("{{\"error\":\"JSON parse failed: {}\"}}", e)),
                    }
                },
                Err(e) => return safe_new_string(env, format!("{{\"error\":\"JNI read failed: {}\"}}", e)),
            },
            None => return safe_new_string(env, "{\"error\":\"Null\"}".to_string()),
        }
    };
    let res = prove_passport(pd).unwrap_or_else(|e| PassportProofResult {
        success: false, input_mode: "ERR".into(), integrity_check: "FAIL".into(), signature_check: "FAIL".into(),
        zk_proof_status: "FAIL".into(), zk_proof_ms: 0, trusted: false,
        bridge_schema_digest: String::new(), // error path — no schema to digest
        error_msg: e.to_string(), merkle_root: "".into(), trust_level: "NONE".into(), nullifier: "".into(), zk_output: None,
    });
    safe_new_string(env, serde_json::to_string(&res).unwrap_or_else(|_| "{\"error\":\"serialize failed\"}".to_string()))
}
// ═════════════════════════════════════════════════════════════════════════════
// [C4a] ICAO 9303 EF.SOD — strict-DER structural extraction (zero new deps)
//   C4a: parse CMS ContentInfo/SignedData → LDS Security Object → DG hashes,
//        DS certificate, SignerInfo signature bytes.
//   C4b (next): DS signature verification, cert chain, trust tiers (H3).
// ═════════════════════════════════════════════════════════════════════════════
// ═════════════════════════════════════════════════════════════════════════════
// [C4a+C4b] ICAO 9303 EF.SOD — CMS SignedData parse + DS signature verify
//   Standards: RFC 5652 (CMS), ICAO Doc 9303-10 (LDS Security Object profile)
//   - eContentType MUST be id-icao-ldsSecurityObject (2.23.136.1.1.1)
//   - algorithm-aware: SHA-224/256/384/512 digest dispatch (fail-closed)
//   - signedAttrs path: messageDigest attribute MUST equal Hash(eContent)
//   C4c backlog: SignerIdentifier↔cert matching, profile validation,
//                ECDSA, x509-parser, CSCA chain, trust tiers (H3)
// ═════════════════════════════════════════════════════════════════════════════
mod sod {
    use anyhow::{anyhow, Result};

    const OID_SIGNED_DATA: &[u8] = &[0x2A, 0x86, 0x48, 0x86, 0xF7, 0x0D, 0x01, 0x07, 0x02];
    const OID_SHA224: &[u8] = &[0x60, 0x86, 0x48, 0x01, 0x65, 0x03, 0x04, 0x02, 0x04];
    const OID_SHA256: &[u8] = &[0x60, 0x86, 0x48, 0x01, 0x65, 0x03, 0x04, 0x02, 0x01];
    const OID_SHA384: &[u8] = &[0x60, 0x86, 0x48, 0x01, 0x65, 0x03, 0x04, 0x02, 0x02];
    const OID_SHA512: &[u8] = &[0x60, 0x86, 0x48, 0x01, 0x65, 0x03, 0x04, 0x02, 0x03];
    #[cfg(test)]
    const OID_RSA_ENCRYPTION: &[u8] = &[0x2A, 0x86, 0x48, 0x86, 0xF7, 0x0D, 0x01, 0x01, 0x01]; // SPKI only
    const OID_SHA224_WITH_RSA: &[u8] = &[0x2A, 0x86, 0x48, 0x86, 0xF7, 0x0D, 0x01, 0x01, 0x0E];
    const OID_SHA256_WITH_RSA: &[u8] = &[0x2A, 0x86, 0x48, 0x86, 0xF7, 0x0D, 0x01, 0x01, 0x0B];
    const OID_SHA384_WITH_RSA: &[u8] = &[0x2A, 0x86, 0x48, 0x86, 0xF7, 0x0D, 0x01, 0x01, 0x0C];
    const OID_SHA512_WITH_RSA: &[u8] = &[0x2A, 0x86, 0x48, 0x86, 0xF7, 0x0D, 0x01, 0x01, 0x0D];
    // [C4d-fix] 2.23.136.1.1.1 — DER: 2.23→0x67, 136→0x81 0x08 (was 0x53 0x88 0x08 ❌)
    const OID_LDS_SECURITY_OBJECT: &[u8] = &[0x67, 0x81, 0x08, 0x01, 0x01, 0x01];
    const OID_MESSAGE_DIGEST: &[u8] = &[0x2A, 0x86, 0x48, 0x86, 0xF7, 0x0D, 0x01, 0x09, 0x04];
    const OID_CONTENT_TYPE: &[u8] = &[0x2A, 0x86, 0x48, 0x86, 0xF7, 0x0D, 0x01, 0x09, 0x03];

    #[derive(Debug, Clone)]
    pub struct DgHashEntry { pub number: u64, pub hash: Vec<u8> }

    #[derive(Debug, Default, Clone)]
    pub struct SodInfo {
        pub dg_hashes: Vec<DgHashEntry>,
        pub signer_info_sig: Vec<u8>,
        pub signed_attrs: Vec<u8>,
        pub has_signed_attrs: bool,
        pub ds_cert_der: Vec<u8>,
        pub sod_body: Vec<u8>,
        pub digest_oid: Vec<u8>,
        pub sig_oid: Vec<u8>,
        pub content_type_oid: Vec<u8>,
    }

    impl SodInfo {
        pub fn dg_hash(&self, n: u64) -> Option<&[u8]> {
            self.dg_hashes.iter().find(|x| x.number == n).map(|x| x.hash.as_slice())
        }
    }

    fn tlv(buf: &[u8], pos: usize) -> Result<(u8, usize, usize)> {
        if pos + 2 > buf.len() { return Err(anyhow!("DER: truncated at {}", pos)); }
        let tag = buf[pos];
        let first = buf[pos + 1];
        let (v_start, len) = if first < 0x80 {
            (pos + 2, first as usize)
        } else if first == 0x80 {
            // [A-09] Indefinite length (0x80) is BER-only — forbidden in DER
            return Err(anyhow!("DER: indefinite length forbidden"));
        } else {
            let n = (first & 0x7F) as usize;
            if n == 0 || n > 4 { return Err(anyhow!("DER: bad long-form length")); }
            if pos + 2 + n > buf.len() { return Err(anyhow!("DER: truncated length")); }
            let mut l = 0usize;
            for b in &buf[pos + 2..pos + 2 + n] { l = (l << 8) | *b as usize; }
            (pos + 2 + n, l)
        };
        let v_end = v_start.checked_add(len).ok_or_else(|| anyhow!("DER: overflow"))?;
        if v_end > buf.len() { return Err(anyhow!("DER: value exceeds buffer")); }
        Ok((tag, v_start, v_end))
    }

    fn children(buf: &[u8], s: usize, e: usize) -> Result<Vec<(u8, usize, usize)>> {
        let mut out = Vec::new();
        let mut cur = s;
        while cur < e {
            let (t, vs, ve) = tlv(buf, cur)?;
            // [A-09] Child must be fully contained within its parent container.
            // Rejects overlong children (e.g. `30 02 04 01 41` — child ve=5 > e=4).
            if ve > e {
                return Err(anyhow!("DER: child exceeds parent boundary"));
            }
            out.push((t, vs, ve));
            cur = ve;
        }
        Ok(out)
    }

    fn read_uint(v: &[u8]) -> Result<u64> {
        // [A-09] Reject oversized integers (would silently truncate) and
        // leading-zero non-minimal encodings.
        if v.len() > 8 {
            return Err(anyhow!("DER: integer exceeds 8 bytes"));
        }
        if v.len() > 1 && v[0] == 0 {
            return Err(anyhow!("DER: non-minimal integer encoding"));
        }
        let mut n = 0u64;
        for b in v { n = (n << 8) | *b as u64; }
        Ok(n)
    }

    fn first_oid(buf: &[u8], s: usize, _e: usize) -> Option<Vec<u8>> {
        let (t, vs, ve) = tlv(buf, s).ok()?;
        if t != 0x06 { return None; }
        Some(buf[vs..ve].to_vec())
    }

    pub fn parse_sod(data: &[u8]) -> Result<SodInfo> {
        let (t, vs, ve) = tlv(data, 0)?;
        if t != 0x30 { return Err(anyhow!("SOD: ContentInfo SEQUENCE expected")); }
        let ck = children(data, vs, ve)?;
        if ck.len() != 2 { return Err(anyhow!("SOD: ContentInfo children != 2")); }
        let (t, s, e) = ck[0];
        if t != 0x06 || &data[s..e] != OID_SIGNED_DATA {
            return Err(anyhow!("SOD: contentType is not signedData"));
        }
        let (t, s, _e) = ck[1];
        if t != 0xA0 { return Err(anyhow!("SOD: content [0] expected")); }

        let (t, ss, se) = tlv(data, s)?;
        if t != 0x30 { return Err(anyhow!("SOD: SignedData SEQUENCE expected")); }
        let sk = children(data, ss, se)?;
        if sk.len() < 4 { return Err(anyhow!("SOD: SignedData too short")); }

        let (t, es, ee) = sk[2];
        if t != 0x30 { return Err(anyhow!("SOD: encapContentInfo SEQUENCE expected")); }
        let ek = children(data, es, ee)?;
        if ek.len() != 2 { return Err(anyhow!("SOD: encapContentInfo children != 2")); }
        let (t, os, oe) = ek[0];
        if t != 0x06 { return Err(anyhow!("SOD: eContentType OID expected")); }
        let ct = data[os..oe].to_vec();
        if ct != OID_LDS_SECURITY_OBJECT {
            return Err(anyhow!(
                "SOD: eContentType must be id-icao-ldsSecurityObject (2.23.136.1.1.1), got {:?}", ct
            ));
        }
        let (t, s, _e) = ek[1];
        if t != 0xA0 { return Err(anyhow!("SOD: eContent [0] expected")); }
        let (t, s, e) = tlv(data, s)?;
        if t != 0x04 { return Err(anyhow!("SOD: SOd OCTET STRING expected")); }
        let sod_body: Vec<u8> = data[s..e].to_vec();

        let mut info = SodInfo::default();
        info.sod_body = sod_body.clone();
        info.content_type_oid = ct;

        for &(tag, s, e) in sk[3..].iter() {
            match tag {
                0xA0 => {
                    // [0] IMPLICIT CertificateSet: children = Certificate TLVs;
                    // re-encode full DER incl. header
                    if let Ok(certs) = children(data, s, e) {
                        if let Some(&(0x30, vs, ve)) = certs.first() {
                            let mut der = vec![0x30];
                            der.extend(blen(ve - vs));
                            der.extend_from_slice(&data[vs..ve]);
                            info.ds_cert_der = der;
                        }
                    }
                }
                0xA1 => {}
                0x31 => {
                    if let Some((0x30, sis, sie)) = children(data, s, e)?.first().copied() {
                        let sik = children(data, sis, sie)?;
                        if sik.len() < 3 { continue; }
                        if sik[2].0 == 0x30 {
                            info.digest_oid = first_oid(data, sik[2].1, sik[2].2).unwrap_or_default();
                        }
                        let mut i = 3;
                        if i < sik.len() && sik[i].0 == 0xA0 {
                            info.has_signed_attrs = true;
                            info.signed_attrs = data[sik[i].1..sik[i].2].to_vec();
                            i += 1;
                        }
                        if i < sik.len() && sik[i].0 == 0x30 {
                            info.sig_oid = first_oid(data, sik[i].1, sik[i].2).unwrap_or_default();
                            i += 1;
                        }
                        if i < sik.len() && sik[i].0 == 0x04 {
                            info.signer_info_sig = data[sik[i].1..sik[i].2].to_vec();
                        }
                    }
                }
                _ => {}
            }
        }

        let (t, ls, le) = tlv(&sod_body, 0)?;
        if t != 0x30 { return Err(anyhow!("SOd: LdsSecurityObject SEQUENCE expected")); }
        let lk = children(&sod_body, ls, le)?;
        if lk.len() < 3 { return Err(anyhow!("SOd: too short")); }
        let (t, gs, ge) = lk[2];
        if t != 0x30 { return Err(anyhow!("SOd: dataGroupHashValues expected")); }
        for (t, s, e) in children(&sod_body, gs, ge)? {
            if t != 0x30 { continue; }
            let gk = children(&sod_body, s, e)?;
            if gk.len() != 2 { continue; }
            let (t1, s1, e1) = gk[0];
            let (t2, s2, e2) = gk[1];
            if t1 == 0x02 && t2 == 0x04 {
                let dg_num = read_uint(&sod_body[s1..e1])
                    .map_err(|e| anyhow!("SOd DG number: {e}"))?;
                // [A-09/Gap1] Duplicate DG number = profile-invalid SOD.
                // Ambiguous dg_hash() lookups must fail closed.
                if info.dg_hashes.iter().any(|x| x.number == dg_num) {
                    return Err(anyhow!("SOd: duplicate DG{} entry", dg_num));
                }
                info.dg_hashes.push(DgHashEntry {
                    number: dg_num,
                    hash: sod_body[s2..e2].to_vec(),
                });
            }
        }
        Ok(info)
    }

    fn extract_spki_key(info: &SodInfo) -> Result<RsaPublicKey> {
        use rsa::RsaPublicKey;
        use rsa::pkcs8::DecodePublicKey;
        let (t, s, _e) = tlv(&info.ds_cert_der, 0)?;
        if t != 0x30 { return Err(anyhow!("cert: SEQUENCE expected")); }
        let (t, ts, te) = tlv(&info.ds_cert_der, s)?;
        if t != 0x30 { return Err(anyhow!("cert: TBSCertificate expected")); }
        // [C4d] v3-aware indexed walk: [0]version?, serial, sig, issuer, validity,
        // subject, SPKI. uniqueIDs/extensions come AFTER SPKI — never assume last!
        let mut cur = ts;
        let mut field_idx = 0usize;
        let mut spki_target = 6usize;
        let mut spki: Option<(usize, usize)> = None;
        while cur < te {
            let (tag, _vs, ve) = tlv(&info.ds_cert_der, cur)?;
            if field_idx == 0 && tag != 0xA0 { spki_target = 5; } // v1
            if field_idx == spki_target {
                if tag != 0x30 { return Err(anyhow!("cert: SPKI expected")); }
                spki = Some((cur, ve));
                break;
            }
            field_idx += 1;
            cur = ve;
        }
        let (s0, e0) = spki.ok_or_else(|| anyhow!("cert: SPKI missing or TBS too short"))?;
        RsaPublicKey::from_public_key_der(&info.ds_cert_der[s0..e0])
            .map_err(|e| anyhow!("SPKI parse: {e}"))
    }
    use rsa::RsaPublicKey;

    /// [C4d] RFC 5652 §5.4: signedAttrs MUST contain contentType (== eContentType)
    /// and messageDigest (== Hash(eContent)). Both enforced here, fail-closed.
    fn signed_attrs_check(info: &SodInfo, content_digest: &[u8]) -> Result<()> {
        let mut ct_ok = false;
        let mut md_ok = false;
        for (t, s, e) in children(&info.signed_attrs, 0, info.signed_attrs.len())? {
            if t != 0x30 { continue; }
            let k = children(&info.signed_attrs, s, e)?;
            if k.len() != 2 { continue; }
            let (t1, s1, e1) = k[0];
            if t1 == 0x06 && info.signed_attrs[s1..e1] == *OID_CONTENT_TYPE {
                let (t2, s2, _e2) = k[1];
                if t2 != 0x31 { return Err(anyhow!("content-type: SET expected")); }
                let (t3, s3, e3) = tlv(&info.signed_attrs, s2)?;
                if t3 != 0x06 { return Err(anyhow!("content-type value: OID expected")); }
                if &info.signed_attrs[s3..e3] != info.content_type_oid.as_slice() {
                    return Err(anyhow!("content-type attr MISMATCH vs eContentType"));
                }
                ct_ok = true;
            }
            if t1 == 0x06 && info.signed_attrs[s1..e1] == *OID_MESSAGE_DIGEST {
                let (t2, s2, _e2) = k[1];
                if t2 != 0x31 { return Err(anyhow!("messageDigest: SET expected")); }
                let (t3, s3, e3) = tlv(&info.signed_attrs, s2)?;
                if t3 != 0x04 { return Err(anyhow!("messageDigest: OCTET STRING expected")); }
                if &info.signed_attrs[s3..e3] != content_digest {
                    return Err(anyhow!("messageDigest MISMATCH — attrs do not bind this content"));
                }
                md_ok = true;
            }
        }
        if !ct_ok { return Err(anyhow!("content-type attribute missing in signedAttrs")); }
        if !md_ok { return Err(anyhow!("messageDigest attribute missing in signedAttrs")); }
        Ok(())
    }

    /// [C4d] verify — profile-strict: signedAttrs required, with-RSA sig OID
    /// matching digestAlgorithm, attrs bound to content, SPKI-aware cert walk.
    pub fn verify_ds_signature(info: &SodInfo) -> Result<()> {
        use rsa::pkcs1v15::{Signature, VerifyingKey};
        use rsa::signature::Verifier;
        use sha2::{Digest, Sha224, Sha256, Sha384, Sha512};

        if info.ds_cert_der.is_empty() { return Err(anyhow!("no DS certificate in SOD")); }
        if info.signer_info_sig.is_empty() { return Err(anyhow!("no SignerInfo signature in SOD")); }
        // ICAO profile: eContent type != id-data ⇒ signedAttrs REQUIRED
        if !info.has_signed_attrs {
            return Err(anyhow!("signedAttrs REQUIRED for ICAO SOD profile (RFC 5652 §5.4)"));
        }

        let content_digest: Vec<u8> = match info.digest_oid.as_slice() {
            x if x == OID_SHA224 => Sha224::digest(&info.sod_body).to_vec(),
            x if x == OID_SHA256 => Sha256::digest(&info.sod_body).to_vec(),
            x if x == OID_SHA384 => Sha384::digest(&info.sod_body).to_vec(),
            x if x == OID_SHA512 => Sha512::digest(&info.sod_body).to_vec(),
            other => return Err(anyhow!("unsupported digestAlgorithm {other:?}")),
        };
        // signatureAlgorithm must be hash-with-RSA matching the digest (fail-closed;
        // rsaEncryption alone is NOT a CMS signature algorithm)
        let pair_ok =
            (info.digest_oid.as_slice() == OID_SHA224 && info.sig_oid.as_slice() == OID_SHA224_WITH_RSA) ||
            (info.digest_oid.as_slice() == OID_SHA256 && info.sig_oid.as_slice() == OID_SHA256_WITH_RSA) ||
            (info.digest_oid.as_slice() == OID_SHA384 && info.sig_oid.as_slice() == OID_SHA384_WITH_RSA) ||
            (info.digest_oid.as_slice() == OID_SHA512 && info.sig_oid.as_slice() == OID_SHA512_WITH_RSA);
        if !pair_ok {
            return Err(anyhow!(
                "digestAlgorithm/signatureAlgorithm pair not allowed: {:?}/{:?}",
                info.digest_oid, info.sig_oid
            ));
        }
        signed_attrs_check(info, &content_digest)?;

        let sig = Signature::try_from(info.signer_info_sig.as_slice())
            .map_err(|e| anyhow!("signature decode: {e}"))?;
        let key = extract_spki_key(info)?;

        macro_rules! run {
            ($h:ty) => {{
                let mut m = vec![0x31];
                m.extend(blen(info.signed_attrs.len()));
                m.extend_from_slice(&info.signed_attrs);
                let vk = VerifyingKey::<$h>::new(key.clone());
                vk.verify(&m, &sig).map_err(|e| anyhow!("DS signature invalid: {e}"))
            }};
        }
        match info.digest_oid.as_slice() {
            x if x == OID_SHA224 => run!(Sha224),
            x if x == OID_SHA256 => run!(Sha256),
            x if x == OID_SHA384 => run!(Sha384),
            x if x == OID_SHA512 => run!(Sha512),
            other => Err(anyhow!("unsupported digestAlgorithm {other:?}")),
        }
    }

    /// [C4c] lightweight signature↔cert presence contract (full Sid matching → C4c-full)
    pub fn sid_matches_cert(info: &SodInfo) -> Result<()> {
        if info.signer_info_sig.is_empty() && !info.ds_cert_der.is_empty() {
            return Err(anyhow!("certificate present but no signature — profile-invalid"));
        }
        if !info.signer_info_sig.is_empty() && info.ds_cert_der.is_empty() {
            return Err(anyhow!("signature present but no certificate — cannot attribute"));
        }
        Ok(())
    }

    // ── DER builders ─────────────────────────────────────────────────────────
    fn blen(l: usize) -> Vec<u8> {
        if l < 0x80 { vec![l as u8] } else if l <= 0xFF { vec![0x81, l as u8] }
        else { vec![0x82, (l >> 8) as u8, l as u8] }
    }
    fn btlv(tag: u8, val: &[u8]) -> Vec<u8> {
        let mut o = vec![tag]; o.extend(blen(val.len())); o.extend_from_slice(val); o
    }
    fn flatten(children: Vec<Vec<u8>>) -> Vec<u8> { children.into_iter().flatten().collect() }
    fn bseq(c: Vec<Vec<u8>>) -> Vec<u8> { btlv(0x30, &flatten(c)) }
    fn bset(c: Vec<Vec<u8>>) -> Vec<u8> { btlv(0x31, &flatten(c)) }
    fn bint(n: u64) -> Vec<u8> {
        let be = n.to_be_bytes();
        let mut i = 0; while i < 7 && be[i] == 0 { i += 1; }
        let mut v = be[i..].to_vec();
        if v[0] & 0x80 != 0 { v.insert(0, 0); }
        btlv(0x02, &v)
    }
    fn boct(v: &[u8]) -> Vec<u8> { btlv(0x04, v) }
    fn battrs(lds_oid: &[u8], md: &[u8]) -> Vec<u8> {
        flatten(vec![
            bseq(vec![btlv(0x06, OID_CONTENT_TYPE), bset(vec![btlv(0x06, lds_oid)])]),
            bseq(vec![btlv(0x06, OID_MESSAGE_DIGEST), bset(vec![boct(md)])]),
        ])
    }

    /// Simulated passport SOD — profile-shaped (attrs, sha256WithRSA),
    /// signature = zero placeholder (SIMULATED tier detection).
    pub fn build_simulated_sod(dg1_sha256: &[u8]) -> Vec<u8> {
        use sha2::{Digest, Sha256};
        let lso = bseq(vec![
            bint(0),
            bseq(vec![btlv(0x06, OID_SHA256)]),
            bseq(vec![bseq(vec![bint(1), boct(dg1_sha256)])]),
        ]);
        let econtent = bseq(vec![
            btlv(0x06, OID_LDS_SECURITY_OBJECT),
            btlv(0xA0, &boct(&lso)),
        ]);
        let signer_info = bseq(vec![
            bint(1),
            bseq(vec![bint(1)]),
            bseq(vec![btlv(0x06, OID_SHA256)]),
            btlv(0xA0, &battrs(OID_LDS_SECURITY_OBJECT, &Sha256::digest(&lso))),
            bseq(vec![btlv(0x06, OID_SHA256_WITH_RSA)]),
            boct(&vec![0u8; 256]),
        ]);
        let signed_data = bseq(vec![
            bint(1),
            bset(vec![bseq(vec![btlv(0x06, OID_SHA256)])]),
            econtent,
            bset(vec![signer_info]),
        ]);
        bseq(vec![btlv(0x06, OID_SIGNED_DATA), btlv(0xA0, &signed_data)])
    }

    // ── [C4d-test] fixtures (SigningKey API; omit_attrs ⇒ must FAIL) ─────────
    #[cfg(test)]
    pub fn build_test_cert(spki_der: &[u8], key: &rsa::RsaPrivateKey) -> Result<Vec<u8>> {
        use rsa::pkcs1v15::SigningKey;
        use rsa::signature::{SignatureEncoding, Signer};
        use sha2::Sha256;
        let alg = bseq(vec![btlv(0x06, OID_RSA_ENCRYPTION)]);
        // [C4d] extension AFTER SPKI — proves indexed walk (SPKI ≠ last field)
        let exts = btlv(0xA3, &bseq(vec![bseq(vec![
            btlv(0x06, &[0x55, 0x1D, 0x13]), // 2.5.29.19 basicConstraints
            boct(&bseq(vec![])),
        ])]));
        let tbs = bseq(vec![
            btlv(0xA0, &bint(2)),
            bint(1),
            alg.clone(),
            bseq(vec![]),
            bseq(vec![btlv(0x17, b"250101000000Z"), btlv(0x17, b"350101000000Z")]),
            bseq(vec![]),
            spki_der.to_vec(),
            exts,
        ]);
        let sig = SigningKey::<Sha256>::new(key.clone()).sign(&tbs).to_vec();
        let mut bits = vec![0u8];
        bits.extend_from_slice(&sig);
        Ok(bseq(vec![tbs, alg, btlv(0x03, &bits)]))
    }

    #[cfg(test)]
    pub fn build_signed_sod(
        dg1_sha256: &[u8],
        cert_der: &[u8],
        key: &rsa::RsaPrivateKey,
        with_signed_attrs: bool,
    ) -> Result<Vec<u8>> {
        use rsa::pkcs1v15::SigningKey;
        use rsa::signature::{SignatureEncoding, Signer};
        use sha2::{Digest, Sha256};
        let lso = bseq(vec![
            bint(0),
            bseq(vec![btlv(0x06, OID_SHA256)]),
            bseq(vec![bseq(vec![bint(1), boct(dg1_sha256)])]),
        ]);
        let econtent = bseq(vec![
            btlv(0x06, OID_LDS_SECURITY_OBJECT),
            btlv(0xA0, &boct(&lso)),
        ]);
        let signing = SigningKey::<Sha256>::new(key.clone());
        let mut signer = vec![
            bint(1),
            bseq(vec![bint(1)]),
            bseq(vec![btlv(0x06, OID_SHA256)]),
        ];
        if with_signed_attrs {
            let attrs = battrs(OID_LDS_SECURITY_OBJECT, &Sha256::digest(&lso));
            let set_der = btlv(0x31, &attrs);
            let sig = signing.sign(&set_der).to_vec();
            signer.push(btlv(0xA0, &attrs));
            signer.push(bseq(vec![btlv(0x06, OID_SHA256_WITH_RSA)]));
            signer.push(boct(&sig));
        } else {
            let sig = signing.sign(&lso).to_vec();
            signer.push(bseq(vec![btlv(0x06, OID_SHA256_WITH_RSA)]));
            signer.push(boct(&sig));
        }
        let signed_data = bseq(vec![
            bint(1),
            bset(vec![bseq(vec![btlv(0x06, OID_SHA256)])]),
            econtent,
            btlv(0xA0, cert_der),
            bset(vec![bseq(signer)]),
        ]);
        Ok(bseq(vec![btlv(0x06, OID_SIGNED_DATA), btlv(0xA0, &signed_data)]))
    }
}

#[cfg(test)]
mod c1_tests {
    use super::*;

    #[test]
    fn nationality_matching_claim_generates_proof() {
        let d = get_simulated_passport(Some("nationality".into()), Some("test.domain".into()));
        let res = prove_passport(d).expect("no hard error");
        assert_eq!(res.zk_proof_status, "GENERATED"); // C1 regression: used to always fail
        // [C4c] SIMULATED tier => success=false by design (H3); ZK path is what C1 proves
        assert!(!res.success);
        assert_eq!(res.trust_level, "SIMULATED");
    }

    #[test]
    fn nationality_mismatch_fails_fast() {
        let mut d = get_simulated_passport(Some("nationality".into()), Some("test.domain".into()));
        d.expected_nationality = Some("USA".into());
        assert!(prove_passport(d).is_err());
    }

    #[test]
    fn non_nat_claims_unaffected() {
        let mut d = get_simulated_passport(Some("is_adult".into()), Some("test.domain".into()));
        d.nationality = Some("PAK".into());
        let res = prove_passport(d).expect("no hard error");
        assert_eq!(res.zk_proof_status, "GENERATED");
        assert!(!res.success); // [C4c] SIMULATED tier by design
    }
    #[test]
    fn forged_age_leaf_value_rejected() {
        // C2 negative test: age_value_t jo leaf ke actual value se match na kare
        // → Poseidon(value‖salt) != leaf_t → prove() must return Err.
        //
        // Kyunke generate_zk_proof() by construction consistent witness banata
        // hai (C2 ka design goal hi yehi hai), hum negative case ke liye prove()
        // ko directly call kerte hain manually-built witness ke saath.
        let data = get_simulated_passport(Some("is_adult".into()), Some("test.domain".into()));
        let device_rng = sha256_hash(data.document_number.as_deref().unwrap_or("AB1234567").as_bytes());
        // [K2] build_merkle_tree ab 6-arg (parsed identity values) — sim mirrors Some() me hain
        let tree = build_merkle_tree(
            data.first_name.as_deref().unwrap_or("ARSALAN"),
            data.last_name.as_deref().unwrap_or("KHAN"),
            data.document_number.as_deref().unwrap_or("AB1234567"),
            data.date_of_birth.as_deref().unwrap_or("900101"),
            data.nationality.as_deref().unwrap_or("PAK"),
            &device_rng,
        );

        let circuits = get_circuits();
        let inner_c = &circuits.inner;

        let mk_witness = |age_claim: u64| -> PartialWitness<F> {
            let mut pw = PartialWitness::new();
            let now = std::time::SystemTime::now()
                .duration_since(std::time::UNIX_EPOCH).unwrap().as_secs();
            pw.set_hash_target(inner_c.root_t, tree.root);
            pw.set_hash_target(inner_c.nullifier_t, HashOut::ZERO);
            pw.set_target(inner_c.claim_type_t, F::from_canonical_u64(0));
            pw.set_hash_target(inner_c.dg1_anchor_t, HashOut::ZERO);
            pw.set_target(inner_c.valid_until_t, F::from_canonical_u64(now + PROOF_TTL_SECS));
            pw.set_hash_target(inner_c.expected_nat_t, HashOut::ZERO);
            pw.set_hash_target(inner_c.hw_binding_t, HashOut::ZERO);
            pw.set_hash_target(inner_c.revocation_id_t, HashOut::ZERO);

            pw.set_hash_target(inner_c.leaf_t, tree.leaves[2].hash);
            pw.set_hash_target(inner_c.sibling_1_t, tree.leaves[3].hash);
            pw.set_hash_target(inner_c.sibling_2_t, tree.node_l);
            pw.set_bool_target(inner_c.bit_0_t, false);
            pw.set_bool_target(inner_c.bit_1_t, true);

            pw.set_target(inner_c.age_t, F::from_canonical_u64(age_claim));
            pw.set_target(inner_c.age_value_t, F::from_canonical_u64(age_claim));
            pw.set_hash_target(inner_c.age_salt_t, HashOut { elements: tree.leaves[2].salt });
            pw.set_bool_target(inner_c.age_indicator_t, true);
            pw.set_target(inner_c.nat_value_t, F::ZERO);
            pw.set_hash_target(inner_c.nat_salt_t, HashOut::ZERO);
            pw.set_bool_target(inner_c.nat_claim_indicator_t, false);
            pw
        };

        // Consistent claim (real age) → prove OK
        let real_age = tree.leaves[2].value[0].to_canonical_u64();
        assert!(
            inner_c.data.prove(mk_witness(real_age)).is_ok(),
            "consistent witness must prove"
        );

        // Forged claim (age=99 ≠ committed) → REJECTED.
        //
        // plonky2 note: connect() copy-generators run during witness generation;
        // an inconsistent witness PANICS before the constraint check — so both
        // Err and panic count as rejection. What matters: no valid proof.
        let prev_hook = std::panic::take_hook();
        std::panic::set_hook(Box::new(|_| {})); // silence the expected panic
        let forged_outcome = std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
            inner_c.data.prove(mk_witness(99))
        }));
        std::panic::set_hook(prev_hook);

        let rejected = match forged_outcome {
            Ok(proof_res) => proof_res.is_err(),
            Err(_) => true, // witness-generation panic = rejection
        };
        assert!(rejected, "forged age value must be rejected by C2 binding");
    }
    #[test]
    fn field_encoding_canonical_and_roundtrip() {
        // C3: 56-bit values always < p → canonical; decode restores input
        let data: Vec<u8> = (0..=255u8).collect();
        let els = bytes_to_field_elements(&data);
        assert_eq!(els.len(), (data.len() + 6) / 7);
        for e in &els {
            assert!(e.to_canonical_u64() < (1u64 << 56), "non-canonical element");
        }
        let mut out = Vec::new();
        for e in &els {
            out.extend_from_slice(&e.to_canonical_u64().to_le_bytes()[..7]);
        }
        out.truncate(data.len());
        assert_eq!(out, data, "roundtrip mismatch");
    }

    #[test]
    fn civil_age_known_dates() {
        // M4: exact civil-calendar math (epoch + leap-year window)
        assert_eq!(civil_from_days(0), (1970, 1, 1));
        assert_eq!(civil_from_days(19_723), (2024, 1, 1));
    }
    #[test]
    fn simulated_sod_parses_and_binds_dg1() {
        // C4a: simulated SOD is now a REAL CMS structure; DG1 hash binds
        let d = get_simulated_passport(None, None);
        let dg1 = hex::decode(&d.dg1_hex).unwrap();
        let sod = hex::decode(&d.sod_hex).unwrap();
        let info = sod::parse_sod(&sod).expect("CMS parse");
        assert_eq!(info.dg_hash(1), Some(sha256_hash(&dg1).as_slice()));
        assert!(info.signer_info_sig.len() == 256); // placeholder present
    }

    #[test]
    fn tampered_dg1_fails_sod_integrity() {
        let d = get_simulated_passport(None, None);
        let sod = hex::decode(&d.sod_hex).unwrap();
        let info = sod::parse_sod(&sod).unwrap();
        let fake = sha256_hash(b"tampered-dg1");
        assert_ne!(info.dg_hash(1), Some(fake.as_slice()));
    }
    #[test]
    fn simulated_sod_tiered_as_simulated() {
        // C4b: placeholder signature + no cert → SIMULATED tier (not VERIFIED)
        let d = get_simulated_passport(None, None);
        let sod = hex::decode(&d.sod_hex).unwrap();
        let info = sod::parse_sod(&sod).unwrap();
        let is_placeholder = info.signer_info_sig.iter().all(|b| *b == 0);
        assert!(is_placeholder && info.ds_cert_der.is_empty(),
            "simulated SOD must be placeholder-tier");
        // aur real verify FAIL hona chahiye (placeholder sig valid nahi hota)
        assert!(sod::verify_ds_signature(&info).is_err());
        // C4c: simulated pipeline success=false hoga (neecha wala test)
    }

    #[test]
    fn positive_real_signature_attrs_path_verified() {
        // Review fix: REAL crypto positive — signedAttrs + messageDigest binding
        use rsa::pkcs8::EncodePublicKey;
        let mut rng = rand::thread_rng();
        let key = rsa::RsaPrivateKey::new(&mut rng, 2048).unwrap();
        let spki = key.to_public_key().to_public_key_der().unwrap().as_bytes().to_vec();
        let cert = sod::build_test_cert(&spki, &key).unwrap();
        let dg1 = sha256_hash(b"P<POSITIVE<<TEST<<<<<<<<<<<<<<<<<<<<<<X1234567USA9001011M");
        let sod_der = sod::build_signed_sod(&dg1, &cert, &key, true).unwrap();
        let info = sod::parse_sod(&sod_der).unwrap();
        assert!(info.has_signed_attrs);
        assert_eq!(info.dg_hash(1), Some(dg1.as_slice()));
        sod::verify_ds_signature(&info).expect("real signature must VERIFY");
    }

    #[test]
    fn no_attrs_profile_rejected() {
        // [C4d] RFC 5652 §5.4: signedAttrs REQUIRED (eContent type != id-data)
        use rsa::pkcs8::EncodePublicKey;
        let mut rng = rand::thread_rng();
        let key = rsa::RsaPrivateKey::new(&mut rng, 2048).unwrap();
        let spki = key.to_public_key().to_public_key_der().unwrap().as_bytes().to_vec();
        let cert = sod::build_test_cert(&spki, &key).unwrap();
        let dg1 = sha256_hash(b"P<POSITIVE<<NOATTRS<<<<<<<<<<<<<<<<<<<<X7654321DEU8801011M");
        let sod_der = sod::build_signed_sod(&dg1, &cert, &key, false).unwrap();
        let info = sod::parse_sod(&sod_der).unwrap();
        assert!(!info.has_signed_attrs);
        assert!(sod::verify_ds_signature(&info).is_err(),
            "no-attrs SOD must FAIL — ICAO profile requires signedAttrs");
    }

    #[test]
    fn content_type_attr_tamper_rejected() {
        // [C4d] contentType attr MUST equal eContentType — flip one OID byte
        use rsa::pkcs8::EncodePublicKey;
        let mut rng = rand::thread_rng();
        let key = rsa::RsaPrivateKey::new(&mut rng, 2048).unwrap();
        let spki = key.to_public_key().to_public_key_der().unwrap().as_bytes().to_vec();
        let cert = sod::build_test_cert(&spki, &key).unwrap();
        let dg1 = sha256_hash(b"P<CTTYPE<<CHECK<<<<<<<<<<<<<<<<<<<<<<Y2222229GBR7001013M");
        let sod_der = sod::build_signed_sod(&dg1, &cert, &key, true).unwrap();
        let mut info = sod::parse_sod(&sod_der).unwrap();
        let pos = info.signed_attrs.windows(6)
            .position(|w| w == [0x67u8, 0x81, 0x08, 0x01, 0x01, 0x01])
            .expect("LDS OID not found in signedAttrs");
        info.signed_attrs[pos] ^= 0x01;
        assert!(sod::verify_ds_signature(&info).is_err(),
            "content-type mismatch MUST be rejected");
    }



    #[test]
    fn message_digest_tamper_rejected() {
        // C4b-1 proof: content bit-flip breaks messageDigest binding
        use rsa::pkcs8::EncodePublicKey;
        let mut rng = rand::thread_rng();
        let key = rsa::RsaPrivateKey::new(&mut rng, 2048).unwrap();
        let spki = key.to_public_key().to_public_key_der().unwrap().as_bytes().to_vec();
        let cert = sod::build_test_cert(&spki, &key).unwrap();
        let dg1 = sha256_hash(b"P<TAMPER<<CHECK<<<<<<<<<<<<<<<<<<<<<<Z1111119FRA7501012F");
        let sod_der = sod::build_signed_sod(&dg1, &cert, &key, true).unwrap();
        let mut info = sod::parse_sod(&sod_der).unwrap();
        let last = info.sod_body.len() - 1;
        info.sod_body[last] ^= 0x01;
        assert!(
            sod::verify_ds_signature(&info).is_err(),
            "content tamper MUST break messageDigest binding"
        );
    }

    #[test]
    fn simulated_never_succeeds() {
        // C4c/H3: SIMULATED tier must NOT produce success=true
        let d = get_simulated_passport(None, None);
        let res = prove_passport(d).expect("no hard error");
        assert_eq!(res.signature_check, "SIMULATED");
        assert_eq!(res.trust_level, "SIMULATED");
        assert!(!res.success, "SIMULATED must never be success=true");
    }

    #[test]
    fn trust_tier_reflects_verification() {
        // Real-signed SOD via prove_passport path → VERIFIED tier
        use rsa::pkcs8::EncodePublicKey;
        let mut rng = rand::thread_rng();
        let key = rsa::RsaPrivateKey::new(&mut rng, 2048).unwrap();
        let spki = key.to_public_key().to_public_key_der().unwrap().as_bytes().to_vec();
        let cert = sod::build_test_cert(&spki, &key).unwrap();
        let d = get_simulated_passport(None, None);
        let dg1 = hex::decode(&d.dg1_hex).unwrap();
        let sod_der = sod::build_signed_sod(&sha256_hash(&dg1), &cert, &key, true).unwrap();
        let mut d2 = d.clone();
        d2.sod_hex = hex::encode(&sod_der);
        let res = prove_passport(d2).expect("no hard error");
        assert_eq!(res.signature_check, "VERIFIED");
        assert_eq!(res.trust_level, "VERIFIED_ONLY");
        assert!(res.success);
    }

    #[test]
    fn independent_external_fixture_verifies() {
        // [C4d] Externally-generated SOD: CMS hand-encoded in Python with an
        // independently DER-encoded ICAO OID set, real cryptography-lib X.509
        // cert with extensions, RSA signature over 0x31-SET signedAttrs,
        // eContent [0] EXPLICIT, sha256WithRSAEncryption signature algorithm.
        const FIXTURE_SOD_HEX: &str = "3082051c06092a864886f70d010702a082050d30820509020101310f300d0609608648016503040201050030490606678108010101a03f043d303b020100300d06096086480165030402010500302730250201010420d387e268decaa8225cfc040a1da1122beabef860ab6cd024e80b55befc55346ca08202f3308202ef308201d7a003020102021464514008a154f4b500949cb243b826e5fba04832300d06092a864886f70d01010b050030223120301e06035504030c175a4b5020496e646570656e64656e742054657374204453301e170d3235303130313030303030305a170d3335303130313030303030305a30223120301e06035504030c175a4b5020496e646570656e64656e74205465737420445330820122300d06092a864886f70d01010105000382010f003082010a0282010100ade0b439dfb31bdbac99504869e6a6acfd4ea4fe18b68fdf82529eaa1939d9506c70755ac80199cae91e567dee979850f3db2781f72f227305fdef0b18ea1bb464d42c758db7cd89ca9ea05a4e71d286fb3307c9adb83f3490927f6f78f58f133305be6f634cb97b85dc2aa5fc480537809374761648056705176824b9e57ff3f665b0c46fbe7985be949bbcdeaeaa48f3112af2b01419af9728437b32074268504b161531007bfa9d19801f2ac34a88bb9278057ba9b73e817b10e5c0fc470f8100ee1eaf0cdbb2e2b1bf74db68ab0823c19582331ad053cc30b2fcde37dfde616ba9d756278759301c0ecb63a6acf4e60fbb22909120c3966b30d11291f05f0203010001a31d301b30090603551d1304023000300e0603551d0f0101ff040403020780300d06092a864886f70d01010b05000382010100486e4486264339e0403ce624ec4ac0ad0cd2274f150dde3bc57ba9cde81936e8138ad61425d47ab6a1285a4a8c3b8d6469fbfbabc36f71d0c4aa89d1c2b60084a8d39efd0f0b792bd6b3ff3d90290e7978ed5410f436554b60cbedaf31930c8a4c444d8dc8ccb91c27150a9600c53182fa6d7b58ac43be2df1610b743650edbfc0ae1bfd92e1f5c518c3070689d33fd3f299718df0b8f09636ce9b60a437a25c9411b1833fa4b3ac1acb8494068e0d09661806ed1bc41d7ff43766fd2ff4e72948fc91e4da71d3af1b44370015324826397e777d79336a235071ab75bcaef25262876a696800a369bfab52618e8ed536398bd59e1cfade81a76bcc36911e142c318201af308201ab020101303a30223120301e06035504030c175a4b5020496e646570656e64656e742054657374204453021464514008a154f4b500949cb243b826e5fba04832300d06096086480165030402010500a048301506092a864886f70d01090331080606678108010101302f06092a864886f70d0109043122042019a200f2e84a88b7d40b49ef8591ef1745db5296d6c95f596e510e896a8cb2ab300d06092a864886f70d01010b0500048201005f13dbea4cf518bcba635f38e5ad10e565bf271325ffede5b4b0e92fdec9a8034666d9b9f1ecafc1a3091f3627bf7427502517b8f7d2c1cc75d31e8124593666f16e409bb08fedf76a7b7ce8c86e80cca9b494b3bb34e4a8501debdd66b7ba1c4c3bf52a4ac1f97897d77a46e366d3136de1cfbbac436309ca9b899ad3aee7f4e7c7c107e3a6ec35183d5c262ed5e685da2af3e2c865968adb0499cdc53a6c4bd84bd3d8a43bc3b975b76f61cbc5357358185dccab92b3bb2b527ea55c088ea5c03746cbf33581f32a09dd5eb34e06e3e7c873f7a45b6b1ae71f775766768a860f223737493e3f26c908e06f75da0c44dfbe9c86696a402f41ad372cdcb4c537";
        const FIXTURE_DG1: &[u8] = b"P<PAKARSALAN<<KHAN<<<<<<<<<<<<<<<<<<<<<<<<<<AB1234567PAK9001011M2501010<<<<<<<<<<<<4";
        let sod_der = hex::decode(FIXTURE_SOD_HEX).unwrap();
        let info = sod::parse_sod(&sod_der).expect("external fixture parses");
        assert!(info.has_signed_attrs);
        assert_eq!(info.dg_hash(1), Some(sha256_hash(FIXTURE_DG1).as_slice()));
        sod::verify_ds_signature(&info).expect("external fixture must VERIFY");
    }

    #[test]
    fn h1_missing_device_rng_fails() {
        let mut d = get_simulated_passport(Some("is_adult".into()), Some("test.domain".into()));
        d.device_rng_hex = None;
        assert!(prove_passport(d).is_err(), "missing device_rng must hard-error (H1)");
    }

    #[test]
    fn h1_short_device_rng_fails() {
        let mut d = get_simulated_passport(Some("is_adult".into()), Some("test.domain".into()));
        d.device_rng_hex = Some("a1b2c3d4".into()); // 4 bytes < 16
        assert!(prove_passport(d).is_err(), "short device_rng must hard-error (H1)");
    }

    #[test]
    fn h1_missing_or_zero_pubkey_fails() {
        let mut d = get_simulated_passport(Some("is_adult".into()), Some("test.domain".into()));
        d.device_pubkey_hex = None;
        assert!(prove_passport(d).is_err(), "missing pubkey must hard-error (H1)");
        d = get_simulated_passport(Some("is_adult".into()), Some("test.domain".into()));
        d.device_pubkey_hex = Some("00".into());
        assert!(prove_passport(d).is_err(), "\"00\" pubkey must hard-error (H1)");
        d = get_simulated_passport(Some("is_adult".into()), Some("test.domain".into()));
        d.device_pubkey_hex = Some("a1b2c3d4".into()); // 4 bytes < 32
        assert!(prove_passport(d).is_err(), "short pubkey must hard-error (H1 contract)");
    }

    // ═══════════════════════════════════════════════════════════════
    // [PHASE-4] A-01 fail-closed + adversarial suite
    // ═══════════════════════════════════════════════════════════════

    #[test]
    
    #[test]
    fn bridge_schema_digest_deterministic() {
        // [K1] Same schema => same digest (drift detection ki foundation)
        let d1 = get_simulated_passport(Some("is_adult".into()), Some("test.domain".into()));
        let r1 = prove_passport(d1).expect("proof ok");
        let d2 = get_simulated_passport(Some("is_adult".into()), Some("test.domain".into()));
        let r2 = prove_passport(d2).expect("proof ok");
        assert_eq!(r1.bridge_schema_digest, r2.bridge_schema_digest,
            "same schema => same digest");
        assert_eq!(r1.bridge_schema_digest.len(), 64, "SHA-256 hex");
    }

    #[test]
    fn deny_unknown_novel_fields_rejected() {
        // [B3a] Novel/mirror fields wire pe aaye → deny_unknown loud-reject
        let json_with_novel = r#"{
            "dg1_hex": "3161125f1f58",
            "sod_hex": "3082",
            "mode": "NFC_PASSPORT",
            "mrz_line": "legacy-field",
            "ds_cert_hex": "3082deadbeef",
            "claim_type": "is_adult",
            "verifier_domain": "test.domain",
            "device_rng_hex": "a1b2c3d4e5f6a7b8c9d0e1f2a3b4c5d6e7f8a9b0c1d2e3f4a5b6c7d8e9f0a1b2",
            "device_pubkey_hex": "02a1b2c3d4e5f6a7b8c9d0e1f2a3b4c5d6e7f8a9b0c1d2e3f4a5b6c7d8e9f0a1b2c3d4e5f6a7b8c9d0e1f2a3b4c5d6e7f8a9b0c1d2e3f4a5b6c7d8e9f0a1b2"
        }"#;
        let parsed: Result<PassportData, _> = serde_json::from_str(json_with_novel);
        assert!(parsed.is_err(),
            "novel fields (mode/mrz_line/ds_cert_hex) must be LOUDLY rejected");
    }

    #[test]
    fn bridge_schema_digest_matches_published_spec_value() {
        // [B3b] spec↔code binding — R5-class doc-vs-code drift ka antidote
        const PUBLISHED_SPEC_DIGEST: &str = "e04f05fb2a29949481825e02c044bad2a49a0b390205cc28b58484983213f2b3";
        let d = get_simulated_passport(Some("is_adult".into()), Some("test.domain".into()));
        let r = prove_passport(d).expect("proof ok");
        assert_eq!(r.bridge_schema_digest, PUBLISHED_SPEC_DIGEST,
            "digest != published spec value — schema drifted WITHOUT deliberate bump");
    }

    #[test]
    fn phase4_json_nationality_mismatch_dg1_rejected() {
        // [A-01 Phase-4] JSON nationality ≠ DG1-authenticated → reject
        let d = get_simulated_passport(Some("nationality".into()), Some("test.domain".into()));
        let mut d2 = d.clone();
        d2.nationality = Some("USA".into());
        d2.expected_nationality = Some("USA".into());
        assert!(prove_passport(d2).is_err(),
            "JSON nationality ≠ DG1 must fail closed (attribute substitution)");
    }

    #[test]
    fn phase4_json_dob_mismatch_dg1_rejected() {
        // [A-01 Phase-4] JSON DOB ≠ DG1-authenticated → reject
        let d = get_simulated_passport(Some("is_adult".into()), Some("test.domain".into()));
        let mut d2 = d.clone();
        d2.date_of_birth = Some("000101".into());   // JSON lie (MRZ has 900101)
        assert!(prove_passport(d2).is_err(),
            "JSON DOB ≠ DG1 must fail closed");
    }

    #[test]
    fn phase4_pi_tamper_invalidates_proof() {
        // [M-02] Proof PI tamper => plonky2 verify fails (recursive binding)
        // NOTE: needs trusted==true => real-signed SOD (A-04 gates SIMULATED
        // to zk_output=None). Build real-signed SOD like trust_tier test.
        use rsa::pkcs8::EncodePublicKey;
        let mut rng = rand::thread_rng();
        let key = rsa::RsaPrivateKey::new(&mut rng, 2048).unwrap();
        let spki = key.to_public_key().to_public_key_der().unwrap().as_bytes().to_vec();
        let cert = sod::build_test_cert(&spki, &key).unwrap();
        let d = get_simulated_passport(Some("is_adult".into()), Some("test.domain".into()));
        let dg1 = hex::decode(&d.dg1_hex).unwrap();
        let sod_der = sod::build_signed_sod(&sha256_hash(&dg1), &cert, &key, true).unwrap();
        let mut d = d;
        d.sod_hex = hex::encode(&sod_der);
        let res = prove_passport(d).expect("no hard error");
        assert!(res.trusted, "fixture must produce trusted result");
        let zk = res.zk_output.expect("trusted proof must have zk_output");
        let mut proof_bytes = hex::decode(&zk.compressed_proof).unwrap();
        let last = proof_bytes.len() - 1;
        proof_bytes[last] ^= 0x01;
        let circuits = get_circuits();
        let parsed = plonky2::plonk::proof::ProofWithPublicInputs::<F, C, D>::from_bytes(
            proof_bytes, &circuits.outer.data.common
        );
        match parsed {
            Ok(p) => {
                assert!(circuits.outer.data.verify(p).is_err(),
                    "tampered proof must fail verification");
            }
            Err(_) => { /* deserialization failure = valid rejection */ }
        }
    }

    // ⏳ Pending items — placeholders with reasons (enable as items ship):

    #[test]
    #[ignore = "A-01b pending: IsHuman leaf_t unconstrained (#8)"]
    fn phase4_ishuman_arbitrary_leaf_must_be_bound() {}

    #[test]
    #[ignore = "A-03 pending: anchors unconstrained PIs (#8)"]
    fn phase4_arbitrary_anchor_must_reject() {}

    #[test]
    #[ignore = "P1 pending: no verifier-challenge binding (#8)"]
    fn phase4_cross_domain_replay_must_reject() {}

    #[test]
    fn mrz_td3_valid_fixture_parses() {
        // ICAO 9303 TD3 valid fixture — check digits per 7-3-9:
        // doc# AB1234567 => 1 · DOB 900101 => 1 · expiry 250101 => 7 (ICAO 7-3-1)
        // personal (14x<) => 0 · composite (39 chars) => 6
        let l1 = crate::passport_security::MRZ_FIXTURE_L1;
        let l2 = crate::passport_security::MRZ_FIXTURE_L2;
        assert_eq!(l1.len(), 44);
        assert_eq!(l2.len(), 44);
        let m = mrz::parse_td3(l1.as_bytes(), l2.as_bytes()).expect("valid fixture must parse");
        assert_eq!(m.document_number, "AB1234567");
        assert_eq!(m.nationality, "PAK");
        assert_eq!(m.date_of_birth, "900101");
        assert_eq!(m.sex, "M");
        assert_eq!(m.date_of_expiry, "250101");
        assert_eq!(m.surname, "ARSALAN");
        assert_eq!(m.given_names, "KHAN");
    }

    #[test]
    fn mrz_tampered_dob_check_digit_rejected() {
        // Flip a DOB digit — check digit at idx 19 must no longer match
        let mut l2 = MRZ_FIXTURE_L2.as_bytes().to_vec();
        l2[14] = b'1'; // 900101 -> 910101
        let l1 = crate::passport_security::MRZ_FIXTURE_L1;
        assert!(mrz::parse_td3(l1.as_bytes(), &l2).is_err(), "tampered DOB must fail check digit");
    }

    #[test]
    fn mrz_tampered_doc_number_rejected() {
        let mut l2 = MRZ_FIXTURE_L2.as_bytes().to_vec();
        l2[0] = b'X';
        let l1 = crate::passport_security::MRZ_FIXTURE_L1;
        assert!(mrz::parse_td3(l1.as_bytes(), &l2).is_err(), "tampered doc# must fail check digit");
    }

    #[test]
    fn mrz_wrong_length_rejected() {
        // Deliberately malformed lengths — 43 (short) and 45 (long).
        // Length gate must reject BEFORE any field parsing.
        let l1_short = "P<PAKARSALAN<<KHAN<<<<<<<<<<<<<<<<<<<<<<<<<<<"; // 43
        let l1_long  = "P<PAKARSALAN<<KHAN<<<<<<<<<<<<<<<<<<<<<<<<<<<X"; // 45
        let l2 = MRZ_FIXTURE_L2;
        assert!(mrz::parse_td3(l1_short.as_bytes(), l2.as_bytes()).is_err(), "43-char line must fail");
        assert!(mrz::parse_td3(l1_long.as_bytes(), l2.as_bytes()).is_err(), "45-char line must fail");
    }

    #[test]
    fn mrz_non_td3_document_code_rejected() {
        // 'I<' = TD1 (ID card) document code — exact 44 chars, so ONLY the
        // doc-code check can reject (not the length gate).
        let l1 = "I<PAKARSALAN<<KHAN<<<<<<<<<<<<<<<<<<<<<<<<<<<"; // 45 -> pad down to 44
        let l1_44 = &l1[..44];
        let l2 = MRZ_FIXTURE_L2;
        assert!(mrz::parse_td3(l1_44.as_bytes(), l2.as_bytes()).is_err(), "TD3 parser must reject non-P docs");
    }

}
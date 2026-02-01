use jni::JNIEnv;
use jni::objects::{JClass, JString};
use jni::sys::{jstring, jboolean};
use std::panic;
use std::time::Instant; // ⏱️ TIMING TOOL
use base64::{Engine as _, engine::general_purpose};
use android_logger::Config; // 📝 LOGGING TOOL
use log::{info, debug, error, LevelFilter}; // 📝 LOGGING MACROS
use serde::{Deserialize, Serialize}; // 📦 NEW FOR JSON
use anyhow::Result;

// 👇 NEW DAY 72/73 IMPORT: SHA-256 Hashing
use sha2::{Sha256, Digest};

// Plonky2 Imports
use plonky2::field::types::Field;
use plonky2::plonk::circuit_builder::CircuitBuilder;
use plonky2::plonk::circuit_data::CircuitConfig;
use plonky2::plonk::config::{GenericConfig, PoseidonGoldilocksConfig};
use plonky2::plonk::proof::ProofWithPublicInputs; 
use plonky2::hash::poseidon::PoseidonHash;
use plonky2::plonk::config::Hasher;
use plonky2::iop::witness::{PartialWitness, WitnessWrite};

// 🟢 LOGGER SETUP (Shared)
fn init_logger() {
    let _ = android_logger::init_once(
        Config::default().with_max_level(LevelFilter::Debug).with_tag("RustZKP"),
    );
}

// =========================================================================
// 🏛️ PART 1: OLD LOGIC (Balance Proof for Phase 3/4)
// =========================================================================

const D: usize = 2;
type C = PoseidonGoldilocksConfig;
type F = <C as GenericConfig<D>>::F;

fn build_identity_circuit(builder: &mut CircuitBuilder<F, D>) -> (plonky2::iop::target::Target, plonky2::hash::hash_types::HashOutTarget) {
    let balance_target = builder.add_virtual_target();
    let computed_hash = builder.hash_n_to_hash_no_pad::<PoseidonHash>(vec![balance_target]);
    let expected_hash_target = builder.add_virtual_hash();
    builder.connect_hashes(computed_hash, expected_hash_target);
    builder.register_public_input(expected_hash_target.elements[0]);
    let min_required = builder.constant(F::from_canonical_u64(10000));
    let diff = builder.sub(balance_target, min_required);
    builder.range_check(diff, 32);
    (balance_target, expected_hash_target)
}

#[no_mangle]
pub extern "system" fn Java_com_example_zkpapp_MainActivity_stringFromRust(
    env: JNIEnv,
    _class: JClass,
) -> jstring {
    init_logger();
    let start_time = Instant::now();
    info!("🚀 OLD PROVER START...");

    let result = panic::catch_unwind(|| -> Result<String> {
        let config = CircuitConfig::standard_recursion_config();
        let mut builder = CircuitBuilder::<F, D>::new(config);
        let (balance_target, expected_hash_target) = build_identity_circuit(&mut builder);
        let data = builder.build::<C>();

        let my_real_balance = F::from_canonical_u64(50000);
        let my_balance_hash = PoseidonHash::hash_no_pad(&[my_real_balance]);

        let mut pw = PartialWitness::new();
        pw.set_target(balance_target, my_real_balance);
        pw.set_hash_target(expected_hash_target, my_balance_hash);

        let proof = data.prove(pw)?;
        info!("✅ OLD PROOF GENERATED in: {:.2?}", start_time.elapsed());

        let proof_bytes = bincode::serialize(&proof)?;
        let proof_base64 = general_purpose::STANDARD.encode(proof_bytes);
        
        // Chunking Logic (Preserved)
        let chunk_size = 500;
        let total_chunks = (proof_base64.len() + chunk_size - 1) / chunk_size;
        let mut json_array = String::from("[");
        for i in 0..total_chunks {
            let start = i * chunk_size;
            let end = std::cmp::min(start + chunk_size, proof_base64.len());
            let slice = &proof_base64[start..end];
            if i > 0 { json_array.push(','); }
            json_array.push_str(&format!("\"{}/{}\\|{}\"", i + 1, total_chunks, slice));
        }
        json_array.push(']');
        Ok(json_array)
    });

    let output = match result {
        Ok(Ok(json)) => json,
        Ok(Err(e)) => format!("Error: {}", e),
        Err(_) => "Panic".to_string(),
    };
    env.new_string(output).expect("Error").into_raw()
}

#[no_mangle]
pub extern "system" fn Java_com_example_zkpapp_VerifierActivity_verifyProofFromRust(
    mut env: JNIEnv, _class: JClass, proof_str: JString,
) -> jboolean {
    init_logger();
    let proof_base64: String = match env.get_string(&proof_str) {
        Ok(s) => s.into(),
        Err(_) => return 0,
    };
    
    let result = panic::catch_unwind(|| {
        let proof_bytes = match general_purpose::STANDARD.decode(&proof_base64) {
            Ok(b) => b,
            Err(_) => return false,
        };
        let proof: ProofWithPublicInputs<F, C, D> = match bincode::deserialize(&proof_bytes) {
            Ok(p) => p,
            Err(_) => return false,
        };

        let config = CircuitConfig::standard_recursion_config();
        let mut builder = CircuitBuilder::<F, D>::new(config);
        build_identity_circuit(&mut builder);
        let data = builder.build::<C>();

        match data.verify(proof) {
            Ok(_) => true,
            Err(_) => false,
        }
    });
    
    match result {
        Ok(true) => 1,
        _ => 0,
    }
}

// =========================================================================
// 🆕 PART 2: DAY 73 LOGIC (SOD VERIFICATION)
// =========================================================================

#[derive(Serialize, Deserialize, Debug)]
struct PassportData {
    first_name: String,
    last_name: String,
    document_number: String,
    dg1_hex: String, 
    sod_hex: String, 
}

// 🔍 Helper: Search for bytes inside another byte array (The "Find" function)
fn find_subsequence(haystack: &[u8], needle: &[u8]) -> bool {
    if needle.is_empty() { return false; }
    haystack.windows(needle.len()).any(|window| window == needle)
}

fn prove_passport_logic(data: PassportData) -> Result<String, anyhow::Error> {
    info!("🏗️ Rust: Starting SOD Verification (Day 73)...");
    
    // 1. Decode Hex to Bytes
    let dg1_bytes = hex::decode(&data.dg1_hex).map_err(|e| anyhow::anyhow!("Invalid DG1 Hex: {}", e))?;
    let sod_bytes = hex::decode(&data.sod_hex).map_err(|e| anyhow::anyhow!("Invalid SOD Hex: {}", e))?;
    
    // 2. 🔥 CALCULATE DG1 HASH
    let mut hasher = Sha256::new();
    hasher.update(&dg1_bytes);
    let calculated_hash = hasher.finalize();
    let hash_hex = hex::encode(calculated_hash);
    
    info!("✅ CALCULATED HASH: {}", hash_hex);

    // 3. 🕵️‍♂️ SEARCH HASH INSIDE SOD (VERIFICATION)
    // Real Passport SOD contains the hash of DG1. We check if it exists.
    let is_authentic = find_subsequence(&sod_bytes, &calculated_hash);

    let status_icon = if is_authentic { "✅ MATCHED" } else { "❌ MISMATCH" };
    
    if is_authentic {
        info!("🎉 PASSPORT IS GENUINE! Hash found in SOD.");
    } else {
        error!("⚠️ PASSPORT TAMPERED OR SIMULATED! Hash not found in SOD.");
    }

    // 4. Return Report
    let response = format!(
        "👤 User: {} {}\n📜 Doc: {}\n\n🔒 HASH CHECK:\n{}\n\n📝 Result:\n{} (SOD Verification)",
        data.first_name, data.last_name, data.document_number, hash_hex, status_icon
    );

    Ok(response)
}

#[no_mangle]
pub extern "system" fn Java_com_example_zkpapp_SecurityGate_generateProof(
    mut env: JNIEnv,
    _class: JClass,
    json_payload: JString,
) -> jstring {
    init_logger(); 
    
    // Get JSON from Java
    let input: String = match env.get_string(&json_payload) {
        Ok(s) => s.into(),
        Err(e) => {
            error!("Failed to get Java string: {:?}", e);
            return env.new_string("ERROR_JNI").unwrap().into_raw();
        }
    };

    debug!("🚀 Rust received Payload: {}", input);

    // Parse JSON
    let passport_data: PassportData = match serde_json::from_str(&input) {
        Ok(data) => data,
        Err(e) => {
            error!("❌ JSON Parse Error: {:?}", e);
            return env.new_string(format!("ERROR_JSON: {}", e)).unwrap().into_raw();
        }
    };

    // Run Logic
    match prove_passport_logic(passport_data) {
        Ok(proof) => env.new_string(proof).unwrap().into_raw(),
        Err(e) => {
             error!("❌ Logic Error: {:?}", e);
             env.new_string(format!("ERROR_LOGIC: {}", e)).unwrap().into_raw()
        }
    }
}
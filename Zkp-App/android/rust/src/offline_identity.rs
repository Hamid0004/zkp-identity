use jni::JNIEnv;
use jni::objects::{JClass, JString};
use jni::sys::jstring; // Removed jboolean, we need String for report
use std::panic;
use std::time::Instant;
use base64::{Engine as _, engine::general_purpose};
use android_logger::Config;
use log::{info, LevelFilter};

// Plonky2 Imports
use plonky2::field::types::Field;
use plonky2::plonk::circuit_builder::CircuitBuilder;
use plonky2::plonk::circuit_data::CircuitConfig;
use plonky2::plonk::config::{GenericConfig, PoseidonGoldilocksConfig};
use plonky2::plonk::proof::ProofWithPublicInputs;
use plonky2::hash::poseidon::PoseidonHash;
use plonky2::plonk::config::Hasher;
use plonky2::iop::witness::{PartialWitness, WitnessWrite};

// 🟢 LOGGER SETUP
fn init_logger() {
    let _ = android_logger::init_once(
        Config::default().with_max_level(LevelFilter::Info).with_tag("RustZKP_Lion"),
    );
}

// SHARED CONFIG
const D: usize = 2;
type C = PoseidonGoldilocksConfig;
type F = <C as GenericConfig<D>>::F;

// 🧠 THE BRAIN: Circuit Logic
fn build_identity_circuit(builder: &mut CircuitBuilder<F, D>) -> (plonky2::iop::target::Target, plonky2::hash::hash_types::HashOutTarget) {
    let balance_target = builder.add_virtual_target();
    // Simple logic: Hash the balance and range check it
    let computed_hash = builder.hash_n_to_hash_no_pad::<PoseidonHash>(vec![balance_target]);
    let expected_hash_target = builder.add_virtual_hash();
    builder.connect_hashes(computed_hash, expected_hash_target);
    builder.register_public_input(expected_hash_target.elements[0]);
    
    // Constraint: Balance > 10000
    let min_required = builder.constant(F::from_canonical_u64(10000));
    let diff = builder.sub(balance_target, min_required);
    builder.range_check(diff, 32);
    
    (balance_target, expected_hash_target)
}

// ==================================================================================
// 1️⃣ PROVER (SENDER) - Linked to LoginActivity
// ==================================================================================
#[no_mangle]
pub extern "C" fn Java_com_example_zkpapp_LoginActivity_stringFromRust(
    env: JNIEnv,
    _class: JClass,
) -> jstring {
    init_logger();
    info!("🚀 PROVER STARTED: Generating Proof...");

    let result = panic::catch_unwind(|| {
        let start_time = Instant::now();

        // 1. Build Circuit
        let config = CircuitConfig::standard_recursion_config();
        let mut builder = CircuitBuilder::<F, D>::new(config);
        let (balance_target, expected_hash_target) = build_identity_circuit(&mut builder);
        let data = builder.build::<C>();

        // 2. Witness Data (Real Identity)
        let my_real_balance = F::from_canonical_u64(50000);
        let my_balance_hash = PoseidonHash::hash_no_pad(&[my_real_balance]);

        let mut pw = PartialWitness::new();
        pw.set_target(balance_target, my_real_balance);
        pw.set_hash_target(expected_hash_target, my_balance_hash);

        // 3. Prove
        let proof = data.prove(pw).expect("Proof generation failed");
        
        let duration = start_time.elapsed();
        info!("✅ PROOF GENERATED in: {:.2?}", duration);

        // 4. Serialize
        let proof_bytes = bincode::serialize(&proof).unwrap();
        let proof_base64 = general_purpose::STANDARD.encode(proof_bytes);

        // 5. Chunking for QR (JSON Array)
        // Format: ["1/5|...data...", "2/5|...data..."]
        let chunk_size = 300; // Safe size for QR
        let total_chunks = (proof_base64.len() + chunk_size - 1) / chunk_size;
        let mut json_str = String::from("[");
        
        for i in 0..total_chunks {
            let start = i * chunk_size;
            let end = std::cmp::min(start + chunk_size, proof_base64.len());
            let slice = &proof_base64[start..end];
            
            if i > 0 { json_str.push(','); }
            // Format: "index/total|data"
            json_str.push_str(&format!("\"{}/{}\\|{}\"", i + 1, total_chunks, slice));
        }
        json_str.push(']');
        
        json_str
    });

    let output = result.unwrap_or_else(|_| "Error: Rust Panic".to_string());
    env.new_string(output).expect("Couldn't create java string").into_raw()
}

// ==================================================================================
// 2️⃣ VERIFIER (RECEIVER) - Linked to VerifierActivity
// ==================================================================================
#[no_mangle]
pub extern "C" fn Java_com_example_zkpapp_VerifierActivity_verifyProofFromRust(
    mut env: JNIEnv,
    _class: JClass,
    proof_str: JString,
) -> jstring {
    init_logger();
    info!("🕵️ VERIFIER STARTED");

    let input_proof: String = env.get_string(&proof_str).expect("Invalid JString").into();
    
    let result = panic::catch_unwind(|| {
        let start_time = Instant::now();

        // 1. Decode
        let proof_bytes = match general_purpose::STANDARD.decode(&input_proof) {
            Ok(b) => b,
            Err(_) => return "❌ Parse Failed: Invalid Base64".to_string(),
        };

        let proof: ProofWithPublicInputs<F, C, D> = match bincode::deserialize(&proof_bytes) {
            Ok(p) => p,
            Err(_) => return "❌ Parse Failed: Invalid Struct".to_string(),
        };

        // 2. Rebuild Circuit (Verifier must know the structure)
        let config = CircuitConfig::standard_recursion_config();
        let mut builder = CircuitBuilder::<F, D>::new(config);
        build_identity_circuit(&mut builder);
        let data = builder.build::<C>();

        // 3. Verify
        let duration = start_time.elapsed();
        match data.verify(proof) {
            Ok(_) => format!("✅ Verified! (Math: {:.2?})", duration),
            Err(_) => format!("❌ Invalid Proof! (Math: {:.2?})", duration),
        }
    });

    let output = result.unwrap_or_else(|_| "🔥 Rust Panic".to_string());
    env.new_string(output).expect("Couldn't create java string").into_raw()
}
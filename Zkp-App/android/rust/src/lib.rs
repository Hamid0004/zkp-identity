use jni::JNIEnv;
// 👇 FIX 1: Added JByteArray to safe imports
use jni::objects::{JClass, JString, JByteArray}; 
use jni::sys::jstring; 
use std::panic;
use std::time::Instant;
use base64::{Engine as _, engine::general_purpose};
use android_logger::Config;
use log::{info, LevelFilter}; // Removed unused 'error'

// Plonky2 Imports
use plonky2::field::types::Field;
use plonky2::plonk::circuit_builder::CircuitBuilder;
use plonky2::plonk::circuit_data::CircuitConfig;
use plonky2::plonk::config::{GenericConfig, PoseidonGoldilocksConfig};
use plonky2::plonk::proof::ProofWithPublicInputs; 
use plonky2::hash::poseidon::PoseidonHash;
use plonky2::plonk::config::Hasher;
use plonky2::iop::witness::{PartialWitness, WitnessWrite};
use anyhow::Result;

// Logger Init
fn init_logger() {
    let _ = android_logger::init_once(
        Config::default().with_max_level(LevelFilter::Info).with_tag("RustZKP"),
    );
}

// 🔧 CONFIGURATION: Standard (100% Safe)
fn get_diet_config() -> CircuitConfig {
    CircuitConfig::standard_recursion_config()
}

// Shared Constants
const D: usize = 2;
type C = PoseidonGoldilocksConfig;
type F = <C as GenericConfig<D>>::F;

// Shared Logic
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

// 1️⃣ PROVER (Sender) - Returns JSON Chunks
#[no_mangle]
pub extern "system" fn Java_com_example_zkpapp_MainActivity_stringFromRust(
    env: JNIEnv,
    _class: JClass,
) -> jstring {
    init_logger();
    let start_time = Instant::now();
    info!("🚀 PROVER START: Generating Standard Proof...");

    let result = panic::catch_unwind(|| -> Result<String> {
        let config = get_diet_config(); 
        
        let mut builder = CircuitBuilder::<F, D>::new(config);
        let (balance_target, expected_hash_target) = build_identity_circuit(&mut builder);
        let data = builder.build::<C>();

        let my_real_balance = F::from_canonical_u64(50000);
        let my_balance_hash = PoseidonHash::hash_no_pad(&[my_real_balance]);

        let mut pw = PartialWitness::new();
        pw.set_target(balance_target, my_real_balance);
        pw.set_hash_target(expected_hash_target, my_balance_hash);

        let proof = data.prove(pw)?;
        
        let duration = start_time.elapsed();
        info!("✅ PROOF GENERATED in: {:.2?}", duration);

        let proof_bytes = bincode::serialize(&proof)?;
        let proof_base64 = general_purpose::STANDARD.encode(proof_bytes);
        
        // 👇 OPTIMIZATION: 750 Chars per QR
        let chunk_size = 750; 
        let total_chunks = (proof_base64.len() + chunk_size - 1) / chunk_size;
        
        info!("📦 FINAL SIZE: {} chunks", total_chunks);

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
        Ok(Err(e)) => format!("[\"Error: {}\"]", e),
        Err(_) => "[\"Error: Panic\"]".to_string(),
    };
    env.new_string(output).expect("Error").into_raw()
}

// 2️⃣ VERIFIER (Receiver) - Returns REPORT STRING (Benchmarks) 📊
#[no_mangle]
pub extern "system" fn Java_com_example_zkpapp_VerifierActivity_verifyProofFromRust(
    mut env: JNIEnv,
    _class: JClass,
    proof_str: JString,
) -> jstring { 
    init_logger();

    let proof_base64: String = match env.get_string(&proof_str) {
        Ok(s) => s.into(),
        Err(_) => return env.new_string("❌ Error: JNI String Fail").unwrap().into_raw(),
    };

    let result_msg = panic::catch_unwind(|| {
        let deser_start = Instant::now();
        
        let proof_bytes = match general_purpose::STANDARD.decode(&proof_base64) {
            Ok(b) => b,
            Err(_) => return "❌ Error: Base64 Fail".to_string(),
        };
        
        let proof: ProofWithPublicInputs<F, C, D> = match bincode::deserialize(&proof_bytes) {
            Ok(p) => p,
            Err(_) => return "❌ Error: Parse Fail".to_string(),
        };
        
        let deser_time = deser_start.elapsed(); 
        
        let math_start = Instant::now();
        
        let config = get_diet_config(); 
        let mut builder = CircuitBuilder::<F, D>::new(config);
        build_identity_circuit(&mut builder);
        let data = builder.build::<C>();

        let is_valid = match data.verify(proof) {
            Ok(_) => true,
            Err(_) => false,
        };
        
        let math_time = math_start.elapsed(); 
        
        if is_valid {
            format!("✅ Verified!\n📂 Parse: {:.2?}\n🧮 Math: {:.2?}", deser_time, math_time)
        } else {
            "⛔ Invalid Proof".to_string()
        }
    });

    let final_output = match result_msg {
        Ok(msg) => msg,
        Err(_) => "💥 Rust Panic (Crash)".to_string(),
    };

    env.new_string(final_output).expect("Could not create string").into_raw()
}

// 3️⃣ PASSPORT BRIDGE (Phase 6 Entry Point)
// Input: Raw bytes from the NFC chip (SOD + DG1)
// Output: Initial analysis string
#[no_mangle]
pub extern "system" fn Java_com_example_zkpapp_PassportActivity_processPassportData(
    mut env: JNIEnv,
    _class: JClass,
    input_data: JByteArray, // 👈 FIX 2: Changed from 'jbyteArray' (unsafe) to 'JByteArray' (safe)
) -> jstring {
    init_logger();
    info!("🛂 RUST: Passport Data Received");

    let result = panic::catch_unwind(|| -> Result<String> {
        // Now this works because 'input_data' is the correct type
        let passport_bytes = env.convert_byte_array(input_data).unwrap_or(vec![]);
        
        info!("📊 Received {} bytes from NFC", passport_bytes.len());

        // TODO: Future parsing logic here
        
        Ok(format!("Rust received {} bytes. Ready for parsing.", passport_bytes.len()))
    });

    let output = match result {
        Ok(Ok(msg)) => msg,
        Ok(Err(e)) => format!("Error: {}", e),
        Err(_) => "Panic in Passport processing".to_string(),
    };
    
    env.new_string(output).expect("Error creating string").into_raw()
}
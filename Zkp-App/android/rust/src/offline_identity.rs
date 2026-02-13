// ═══════════════════════════════════════════════════════════════════════════
// 🦁 OFFLINE IDENTITY VERIFICATION SYSTEM (Rust JNI Bridge)
// ═══════════════════════════════════════════════════════════════════════════

use jni::JNIEnv;
use jni::objects::{JClass, JString};
use jni::sys::jstring;
use std::panic;
use std::time::Instant;
use base64::{Engine as _, engine::general_purpose};
use android_logger::Config;
use log::{info, error, warn, LevelFilter};
use crc32fast::Hasher;

// Plonky2 - Zero-Knowledge Proof Framework
use plonky2::field::types::Field;
use plonky2::plonk::circuit_builder::CircuitBuilder;
use plonky2::plonk::circuit_data::CircuitConfig;
use plonky2::plonk::config::{GenericConfig, PoseidonGoldilocksConfig};
use plonky2::plonk::proof::ProofWithPublicInputs;
use plonky2::hash::poseidon::PoseidonHash;
use plonky2::plonk::config::Hasher as PlonkyHasher;
use plonky2::iop::witness::{PartialWitness, WitnessWrite};

// ═══════════════════════════════════════════════════════════════════════════
// 🌍 GLOBAL CONFIGURATION
// ═══════════════════════════════════════════════════════════════════════════

const D: usize = 2;
type C = PoseidonGoldilocksConfig;
type F = <C as GenericConfig<D>>::F;

// 500 bytes = ~667 chars in Base64 = Optimal for QR v40
const CHUNK_SIZE: usize = 500;
const MIN_BALANCE: u64 = 10_000;

// ═══════════════════════════════════════════════════════════════════════════
// 📱 ANDROID LOGGER
// ═══════════════════════════════════════════════════════════════════════════

pub fn init_logger() {
    let _ = android_logger::init_once(
        Config::default()
            .with_max_level(LevelFilter::Info)
            .with_tag("RustZKP_Lion"),
    );
}

// ═══════════════════════════════════════════════════════════════════════════
// 🧠 CIRCUIT LOGIC
// ═══════════════════════════════════════════════════════════════════════════

fn build_identity_circuit(
    builder: &mut CircuitBuilder<F, D>,
) -> (
    plonky2::iop::target::Target,
    plonky2::hash::hash_types::HashOutTarget,
) {
    let balance_target = builder.add_virtual_target();
    let computed_hash = builder.hash_n_to_hash_no_pad::<PoseidonHash>(vec![balance_target]);
    let expected_hash_target = builder.add_virtual_hash();
    builder.connect_hashes(computed_hash, expected_hash_target);
    builder.register_public_input(expected_hash_target.elements[0]);

    let min_required = builder.constant(F::from_canonical_u64(MIN_BALANCE));
    let diff = builder.sub(balance_target, min_required);
    builder.range_check(diff, 32);

    (balance_target, expected_hash_target)
}

// ═══════════════════════════════════════════════════════════════════════════
// 1️⃣ PROVER (Sender)
// ═══════════════════════════════════════════════════════════════════════════

#[no_mangle]
pub extern "C" fn Java_com_example_zkpapp_OfflineMenuActivity_stringFromRust(
    env: JNIEnv,
    _class: JClass,
) -> jstring {
    init_logger();
    info!("🚀 PROVER STARTED");

    let result = panic::catch_unwind(|| {
        let start_time = Instant::now();

        // 1. Build Circuit
        let config = CircuitConfig::standard_recursion_config();
        let mut builder = CircuitBuilder::<F, D>::new(config);
        let (balance_target, expected_hash_target) = build_identity_circuit(&mut builder);
        let data = builder.build::<C>();

        // 2. Witness Data (Secret: 50,000)
        let my_real_balance = F::from_canonical_u64(50_000);
        let my_balance_hash = PoseidonHash::hash_no_pad(&[my_real_balance]);

        let mut pw = PartialWitness::new();
        pw.set_target(balance_target, my_real_balance);
        pw.set_hash_target(expected_hash_target, my_balance_hash);

        // 3. Prove
        let proof = data.prove(pw).expect("Proof generation failed");
        let duration = start_time.elapsed();
        info!("✅ Proof Generated: {:.2?}", duration);

        // 4. Serialize
        let proof_bytes = bincode::serialize(&proof).unwrap();
        let proof_base64 = general_purpose::STANDARD.encode(&proof_bytes);

        // 5. Chunking with Checksum
        let total_chunks = (proof_base64.len() + CHUNK_SIZE - 1) / CHUNK_SIZE;
        let mut json_chunks = String::from("[");

        for i in 0..total_chunks {
            let start = i * CHUNK_SIZE;
            let end = std::cmp::min(start + CHUNK_SIZE, proof_base64.len());
            let chunk_data = &proof_base64[start..end];

            // CRC32 Checksum
            let mut hasher = Hasher::new();
            hasher.update(chunk_data.as_bytes());
            let checksum = hasher.finalize();

            if i > 0 { json_chunks.push(','); }
            
            // Format: "index/total|data|checksum"
            json_chunks.push_str(&format!(
                "\"{}/{}|{}|{}\"",
                i + 1, total_chunks, chunk_data, checksum
            ));
        }
        json_chunks.push(']');
        json_chunks
    });

    let output = result.unwrap_or_else(|_| "Error: Rust Panic".to_string());
    env.new_string(output).expect("Error creating string").into_raw()
}

// ═══════════════════════════════════════════════════════════════════════════
// 2️⃣ VERIFIER (Receiver)
// ═══════════════════════════════════════════════════════════════════════════

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
            Err(_) => return "❌ Corrupted Data (Base64)".to_string(),
        };

        // 2. Deserialize
        let proof: ProofWithPublicInputs<F, C, D> = match bincode::deserialize(&proof_bytes) {
            Ok(p) => p,
            Err(_) => return "❌ Invalid Proof Structure".to_string(),
        };

        // 3. Rebuild Circuit
        let config = CircuitConfig::standard_recursion_config();
        let mut builder = CircuitBuilder::<F, D>::new(config);
        build_identity_circuit(&mut builder);
        let data = builder.build::<C>();

        // 4. Verify
        match data.verify(proof) {
            Ok(_) => format!("✅ Verified! (Crypto: {:.2?})", start_time.elapsed()),
            Err(_) => "⛔ Invalid Proof!".to_string(),
        }
    });

    let output = result.unwrap_or_else(|_| "🔥 Rust Panic".to_string());
    env.new_string(output).expect("Error creating string").into_raw()
}

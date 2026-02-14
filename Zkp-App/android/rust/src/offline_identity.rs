// ═══════════════════════════════════════════════════════════════════════════
// 🦁 OFFLINE IDENTITY SYSTEM (ZKP Core)
// ═══════════════════════════════════════════════════════════════════════════

use jni::JNIEnv;
use jni::objects::{JClass, JString};
use jni::sys::jstring;
use std::ffi::CString; // Essential for string conversion
use std::panic;
use std::time::Instant;
use base64::{Engine as _, engine::general_purpose};
use android_logger::Config;
use log::{info, error, LevelFilter};
use crc32fast::Hasher;
use serde_json::json; // For structured output

// Plonky2 - The Brain 🧠
use plonky2::field::types::Field;
use plonky2::plonk::circuit_builder::CircuitBuilder;
use plonky2::plonk::circuit_data::CircuitConfig;
use plonky2::plonk::config::{GenericConfig, PoseidonGoldilocksConfig};
use plonky2::hash::poseidon::PoseidonHash;
use plonky2::iop::witness::{PartialWitness, WitnessWrite};

// ═══════════════════════════════════════════════════════════════════════════
// 🌍 GLOBAL CONFIGURATION
// ═══════════════════════════════════════════════════════════════════════════

const D: usize = 2;
type C = PoseidonGoldilocksConfig;
type F = <C as GenericConfig<D>>::F;

// 500 bytes limit for optimal QR scanning
const CHUNK_SIZE: usize = 500; 

// ═══════════════════════════════════════════════════════════════════════════
// 📱 ANDROID LOGGER (Debugging)
// ═══════════════════════════════════════════════════════════════════════════

fn init_logger() {
    let _ = android_logger::init_once(
        Config::default()
            .with_max_level(LevelFilter::Info)
            .with_tag("RustZKP_Lion"),
    );
}

// ═══════════════════════════════════════════════════════════════════════════
// 1️⃣ PROVER (Generates Proof for Transmit)
// ═══════════════════════════════════════════════════════════════════════════

#[no_mangle]
pub extern "C" fn Java_com_example_zkpapp_OfflineMenuActivity_stringFromRust(
    env: JNIEnv,
    _class: JClass,
) -> jstring {
    init_logger();
    info!("🚀 PROVER STARTED: Generating Zero-Knowledge Proof...");

    // Safe execution to prevent app crashes
    let result = panic::catch_unwind(|| {
        let start_time = Instant::now();

        // 🦁 1. DUMMY PROOF LOGIC (Simulating Complex ZKP)
        // Note: Real Plonky2 logic is heavy, for now we simulate "Huge Data"
        // to test the QR Chunking system perfectly.
        
        let mut huge_data = String::new();
        huge_data.push_str("ZKP_PROOF_HEADER_V1_");
        for _ in 0..10 {
            huge_data.push_str("A1B2C3D4E5F6G7H8I9J0_"); // Simulating bytes
        }
        huge_data.push_str("END_OF_PROOF_SIGNATURE_LION");

        // Real Logic Placeholder (Commented out to save build time for this test)
        /*
        let config = CircuitConfig::standard_recursion_config();
        let mut builder = CircuitBuilder::<F, D>::new(config);
        // ... build circuit ...
        let proof = data.prove(pw).unwrap();
        let proof_bytes = bincode::serialize(&proof).unwrap();
        let huge_data = general_purpose::STANDARD.encode(&proof_bytes);
        */

        info!("✅ Proof Generated in {:.2?}", start_time.elapsed());

        // 🦁 2. CHUNKING SYSTEM (The Magic Part)
        // Splits big data into small QR codes
        let total_len = huge_data.len();
        let total_chunks = (total_len + CHUNK_SIZE - 1) / CHUNK_SIZE;
        
        let mut chunks_vec = Vec::new();

        for i in 0..total_chunks {
            let start = i * CHUNK_SIZE;
            let end = std::cmp::min(start + CHUNK_SIZE, total_len);
            let chunk_data = &huge_data[start..end];

            // Calculate Checksum for integrity
            let mut hasher = Hasher::new();
            hasher.update(chunk_data.as_bytes());
            let checksum = hasher.finalize();

            // Format: "index/total|checksum|data"
            // Example: "1/4|3928472|ZKP_PROOF..."
            let formatted_chunk = format!("{}/{}|{:x}|{}", i + 1, total_chunks, checksum, chunk_data);
            chunks_vec.push(formatted_chunk);
        }

        // Return as JSON Array ["chunk1", "chunk2", ...]
        json!(chunks_vec).to_string()
    });

    // Handle Panic cleanly
    let output = result.unwrap_or_else(|_| "[]".to_string());
    
    // Convert Rust String -> Java String
    let c_str = CString::new(output).unwrap();
    env.new_string(c_str.to_str().unwrap()).expect("Failed to create Java String").into_raw()
}

// ═══════════════════════════════════════════════════════════════════════════
// 2️⃣ VERIFIER (Validates Proof from Camera)
// ═══════════════════════════════════════════════════════════════════════════

#[no_mangle]
pub extern "C" fn Java_com_example_zkpapp_VerifierActivity_verifyProofFromRust(
    mut env: JNIEnv,
    _class: JClass,
    proof_str: JString,
) -> jstring {
    init_logger();
    info!("🕵️ VERIFIER STARTED");

    // Get String from Java
    let input: String = env.get_string(&proof_str).expect("Invalid JString").into();

    let result = panic::catch_unwind(|| {
        // Logic: Check if it starts with our header (Simulation)
        if input.contains("ZKP_PROOF_HEADER_V1") {
            "✅ Verified: Valid Identity Proof".to_string()
        } else {
            "❌ Invalid: Corrupted or Fake Proof".to_string()
        }
    });

    let output = result.unwrap_or_else(|_| "Error: Verifier Panic".to_string());
    let c_str = CString::new(output).unwrap();
    env.new_string(c_str.to_str().unwrap()).expect("Failed to create Java String").into_raw()
}
// ═══════════════════════════════════════════════════════════════════════════
// 🦀 RUST ZKP MOBILE MODULE (Production Grade)
// ═══════════════════════════════════════════════════════════════════════════

use jni::JNIEnv;
use jni::objects::{JClass, JString};
use jni::sys::jstring;
use std::ffi::CString;
use std::panic;
use std::time::Instant;
use std::cmp::min;

// Logic & Serialization
use anyhow::{Context, Result};
use base64::{Engine as _, engine::general_purpose};
use serde_json::json;

// Android Logging
use android_logger::Config;
use log::{info, error, LevelFilter};

// Plonky2 Imports
use plonky2::field::types::Field;
use plonky2::plonk::circuit_builder::CircuitBuilder;
use plonky2::plonk::circuit_data::{CircuitConfig, CircuitData};
use plonky2::plonk::config::{GenericConfig, PoseidonGoldilocksConfig, Hasher}; // Hasher is critical!
use plonky2::plonk::proof::ProofWithPublicInputs; 
use plonky2::hash::poseidon::PoseidonHash;
use plonky2::iop::witness::{PartialWitness, WitnessWrite};
use plonky2::iop::target::Target;
use plonky2::hash::hash_types::HashOutTarget;

// ═══════════════════════════════════════════════════════════════════════════
// ⚙️ CONSTANTS & CONFIGURATION
// ═══════════════════════════════════════════════════════════════════════════

const D: usize = 2;
type C = PoseidonGoldilocksConfig;
type F = <C as GenericConfig<D>>::F;

// Constraints
const MIN_REQUIRED_BALANCE: u64 = 10_000;
const USER_REAL_BALANCE: u64 = 50_000; // In production, pass this from Java/Kotlin
const QR_CHUNK_SIZE: usize = 750;

fn init_logger() {
    let _ = android_logger::init_once(
        Config::default().with_max_level(LevelFilter::Info).with_tag("RustZKP"),
    );
}

// ═══════════════════════════════════════════════════════════════════════════
// 🧠 CIRCUIT LOGIC (Single Source of Truth)
// ═══════════════════════════════════════════════════════════════════════════

struct IdentityCircuit {
    data: CircuitData<F, C, D>,
    target_balance: Target,
    target_hash: HashOutTarget,
}

impl IdentityCircuit {
    /// Builds the circuit constraints.
    /// Returns the compiled CircuitData and the targets needed for the witness.
    fn build() -> Result<Self> {
        let config = CircuitConfig::standard_recursion_config();
        let mut builder = CircuitBuilder::<F, D>::new(config);

        // 1. Define Targets
        let balance_target = builder.add_virtual_target();
        let expected_hash_target = builder.add_virtual_hash();

        // 2. Hash Constraint: hash(balance) == public_hash
        // We use hash_n_to_hash_no_pad for efficiency with single field elements
        let computed_hash = builder.hash_n_to_hash_no_pad::<PoseidonHash>(vec![balance_target]);
        builder.connect_hashes(computed_hash, expected_hash_target);
        
        // 3. Register Public Input (The Hash)
        // This allows the verifier to check "I am verifying a proof for Hash X"
        builder.register_public_input(expected_hash_target.elements[0]);

        // 4. Range/Threshold Constraint: balance >= 10,000
        // We prove this by checking: (balance - 10000) is a valid number within range
        let min_required = builder.constant(F::from_canonical_u64(MIN_REQUIRED_BALANCE));
        let diff = builder.sub(balance_target, min_required);
        
        // Range check to ensure no underflow (implicitly checks >=)
        builder.range_check(diff, 32); 

        let data = builder.build::<C>();
        
        Ok(Self {
            data,
            target_balance: balance_target,
            target_hash: expected_hash_target,
        })
    }
}

// ═══════════════════════════════════════════════════════════════════════════
// 1️⃣ PROVER (JNI)
// ═══════════════════════════════════════════════════════════════════════════

#[no_mangle]
pub extern "C" fn Java_com_example_zkpapp_OfflineMenuActivity_stringFromRust(
    env: JNIEnv,
    _class: JClass,
) -> jstring {
    init_logger();
    info!("🚀 PROVER: Request Received");

    // Catch panics to prevent crashing the JVM
    let result = panic::catch_unwind(|| -> Result<String> {
        let overall_start = Instant::now();

        // 1. Build Circuit
        let circuit = IdentityCircuit::build().context("Failed to build circuit")?;
        
        // 2. Witness Generation
        info!("🧬 generating witness...");
        let my_real_balance = F::from_canonical_u64(USER_REAL_BALANCE);
        let my_balance_hash = PoseidonHash::hash_no_pad(&[my_real_balance]);

        let mut pw = PartialWitness::new();
        pw.set_target(circuit.target_balance, my_real_balance);
        pw.set_hash_target(circuit.target_hash, my_balance_hash);

        // 3. Prove
        info!("🔨 proving...");
        let proof_start = Instant::now();
        let proof = circuit.data.prove(pw).context("Proof generation failed")?;
        info!("✅ PROOF TIME: {:.2?}", proof_start.elapsed());

        // 4. Serialization
        let proof_bytes = bincode::serialize(&proof).context("Serialization failed")?;
        let proof_base64 = general_purpose::STANDARD.encode(proof_bytes);
        
        // 5. Chunking (Fountain Code / QR Format)
        let total_chunks = (proof_base64.len() + QR_CHUNK_SIZE - 1) / QR_CHUNK_SIZE;
        info!("📦 PAYLOAD: {} bytes | {} chunks", proof_base64.len(), total_chunks);

        // Efficient JSON array construction
        let mut chunks = Vec::with_capacity(total_chunks);
        for i in 0..total_chunks {
            let start = i * QR_CHUNK_SIZE;
            let end = min(start + QR_CHUNK_SIZE, proof_base64.len());
            let slice = &proof_base64[start..end];
            
            // Format: "index/total|data"
            chunks.push(format!("{}/{}|{}", i + 1, total_chunks, slice));
        }

        info!("🎉 TOTAL TIME: {:.2?}", overall_start.elapsed());
        
        // Convert Vec<String> to JSON String
        serde_json::to_string(&chunks).context("JSON serialization failed")
    });

    // Safe JNI Return
    let output = match result {
        Ok(Ok(json)) => json,
        Ok(Err(e)) => {
            error!("Logic Error: {:?}", e);
            json!([format!("Error: {}", e)]).to_string()
        },
        Err(e) => {
            error!("Panic: {:?}", e);
            json!(["Error: Rust Critical Panic"]).to_string()
        }
    };

    let c_str = CString::new(output).unwrap_or_else(|_| CString::new("[\"Error: CString\"]").unwrap());
    env.new_string(c_str.to_str().unwrap_or("[]")).expect("JNI NewString Failed").into_raw()
}

// ═══════════════════════════════════════════════════════════════════════════
// 2️⃣ VERIFIER (JNI)
// ═══════════════════════════════════════════════════════════════════════════

#[no_mangle]
pub extern "C" fn Java_com_example_zkpapp_VerifierActivity_verifyProofFromRust(
    mut env: JNIEnv,
    _class: JClass,
    proof_str: JString,
) -> jstring { 
    init_logger();

    // Safe String Extraction
    let proof_base64: String = match env.get_string(&proof_str) {
        Ok(s) => s.into(),
        Err(e) => {
            error!("JNI String Error: {:?}", e);
            return env.new_string("❌ Error: Invalid Input").unwrap().into_raw();
        }
    };

    let result = panic::catch_unwind(|| -> String {
        let start_time = Instant::now();

        // 1. Decode Base64
        let proof_bytes = match general_purpose::STANDARD.decode(&proof_base64) {
            Ok(b) => b,
            Err(_) => return "❌ Error: Invalid Base64".to_string(),
        };

        // 2. Deserialize Proof
        // Note: Explicitly typing the proof helps the compiler
        let proof: ProofWithPublicInputs<F, C, D> = match bincode::deserialize(&proof_bytes) {
            Ok(p) => p,
            Err(e) => return format!("❌ Error: Corrupt Proof Data ({})", e),
        };

        // 3. Rebuild Circuit (Must match Prover exactly)
        // In a real production app, we would load a pre-computed "VerifierOnlyCircuitData" 
        // from a file instead of rebuilding, but this ensures exact logic match.
        let circuit = match IdentityCircuit::build() {
            Ok(c) => c,
            Err(_) => return "❌ Error: Circuit Build Fail".to_string(),
        };

        // 4. Verify
        match circuit.data.verify(proof) {
            Ok(_) => {
                let duration = start_time.elapsed();
                format!("✅ VERIFIED!\n⏱️ Time: {:.2?}", duration)
            },
            Err(e) => {
                format!("⛔ REJECTED: Invalid Proof\nReason: {:?}", e)
            }
        }
    });

    let final_msg = match result {
        Ok(msg) => msg,
        Err(_) => "💥 Error: Verifier Panic".to_string(),
    };

    let c_str = CString::new(final_msg).unwrap();
    env.new_string(c_str.to_str().unwrap()).expect("JNI NewString Failed").into_raw()
}
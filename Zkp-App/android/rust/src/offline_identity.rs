use jni::JNIEnv;
use jni::objects::{JClass, JString};
use jni::sys::jstring;
use std::ffi::CString;
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
// 🦁 CRITICAL FIX: Yeh line zaroori hai taaki 'hash_no_pad' kaam kare 👇
use plonky2::plonk::config::Hasher; 
use plonky2::iop::witness::{PartialWitness, WitnessWrite};
use anyhow::Result;

// Logger Init
fn init_logger() {
    let _ = android_logger::init_once(
        Config::default().with_max_level(LevelFilter::Info).with_tag("RustZKP"),
    );
}

// 🔧 CONFIGURATION
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

// ═══════════════════════════════════════════════════════════════════════════
// 1️⃣ PROVER (Sender)
// ═══════════════════════════════════════════════════════════════════════════

#[no_mangle]
pub extern "C" fn Java_com_example_zkpapp_OfflineMenuActivity_stringFromRust(
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

        // 🦁 Secret Data: Balance 50,000
        let my_real_balance = F::from_canonical_u64(50000);
        
        // 🦁 AB YEH ERROR NAHI DEGA KYUNKI 'Hasher' TRAIT IMPORTED HAI ✅
        let my_balance_hash = PoseidonHash::hash_no_pad(&[my_real_balance]);

        let mut pw = PartialWitness::new();
        pw.set_target(balance_target, my_real_balance);
        pw.set_hash_target(expected_hash_target, my_balance_hash);

        info!("🔨 Computing Proof...");
        let proof = data.prove(pw)?;

        let duration = start_time.elapsed();
        info!("✅ PROOF GENERATED in: {:.2?}", duration);

        let proof_bytes = bincode::serialize(&proof)?;
        let proof_base64 = general_purpose::STANDARD.encode(proof_bytes);

        // 👇 OPTIMIZATION: 750 Chars per QR
        let chunk_size = 750; 
        let total_chunks = (proof_base64.len() + chunk_size - 1) / chunk_size;

        info!("📦 FINAL SIZE: {} bytes in {} chunks", proof_base64.len(), total_chunks);

        let mut json_array = String::from("[");
        for i in 0..total_chunks {
            let start = i * chunk_size;
            let end = std::cmp::min(start + chunk_size, proof_base64.len());
            let slice = &proof_base64[start..end];
            
            if i > 0 { json_array.push(','); }
            json_array.push_str(&format!("\"{}/{}|{}\"", i + 1, total_chunks, slice));
        }
        json_array.push(']');
        
        Ok(json_array)
    });

    let output = match result {
        Ok(Ok(json)) => json,
        Ok(Err(e)) => format!("[\"Error: {}\"]", e),
        Err(_) => "[\"Error: Rust Panic\"]".to_string(),
    };
    
    let c_str = CString::new(output).unwrap();
    env.new_string(c_str.to_str().unwrap()).expect("JNI Error").into_raw()
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

        let is_valid = data.verify(proof).is_ok();

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

    let c_str = CString::new(final_output).unwrap();
    env.new_string(c_str.to_str().unwrap()).expect("JNI Error").into_raw()
}
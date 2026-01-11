use jni::JNIEnv;
use jni::objects::{JClass, JString};
use jni::sys::{jstring, jboolean};
use std::ffi::CString;
use std::panic;
use std::time::Instant; // ⏱️ TIMING TOOL
use base64::{Engine as _, engine::general_purpose};
use android_logger::Config; // 📝 LOGGING TOOL
use log::{info, LevelFilter}; // 📝 LOGGING MACROS

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

// 🟢 LOGGER SETUP (Sirf ek baar start karna hota hai)
fn init_logger() {
    let _ = android_logger::init_once(
        Config::default().with_max_level(LevelFilter::Info).with_tag("RustZKP"),
    );
}

// SHARED CIRCUIT LOGIC
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

// 1️⃣ PROVER (Sender) with TIMER ⏱️
#[no_mangle]
pub extern "system" fn Java_com_example_zkpapp_MainActivity_stringFromRust(
    env: JNIEnv,
    _class: JClass,
) -> jstring {
    init_logger(); // Logger ON
    
    // ⏱️ START WATCH
    let start_time = Instant::now();
    info!("🚀 PROVER START: Generating Proof...");

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
        
        // ⏱️ STOP WATCH & LOG
        let duration = start_time.elapsed();
        info!("✅ PROOF GENERATED in: {:.2?}", duration);

        let proof_bytes = bincode::serialize(&proof)?;
        let proof_base64 = general_purpose::STANDARD.encode(proof_bytes);
        
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
        Ok(Err(e)) => {
            info!("❌ PROVER ERROR: {}", e);
            format!("Error: {}", e)
        },
        Err(_) => "Panic".to_string(),
    };
    let output_java = env.new_string(output).expect("Error");
    output_java.into_raw()
}

// 2️⃣ VERIFIER (Receiver) with TIMER ⏱️
#[no_mangle]
pub extern "system" fn Java_com_example_zkpapp_VerifierActivity_verifyProofFromRust(
    mut env: JNIEnv,
    _class: JClass,
    proof_str: JString,
) -> jboolean {
    init_logger();
    
    // ⏱️ START WATCH
    let start_time = Instant::now();
    info!("🕵️ VERIFIER START: Stitching Complete, Checking Math...");

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

    // ⏱️ STOP WATCH & LOG
    let duration = start_time.elapsed();
    info!("⚖️ VERIFICATION DONE in: {:.2?}", duration);

    match result {
        Ok(true) => 1,
        _ => 0,
    }
}
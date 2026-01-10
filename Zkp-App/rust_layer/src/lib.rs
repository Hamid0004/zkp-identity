use anyhow::Result;
use plonky2::field::types::Field;
use plonky2::iop::witness::{PartialWitness, WitnessWrite};
use plonky2::plonk::circuit_builder::CircuitBuilder;
use plonky2::plonk::circuit_data::CircuitConfig;
use plonky2::plonk::config::{GenericConfig, PoseidonGoldilocksConfig};
use plonky2::hash::poseidon::PoseidonHash;
use plonky2::plonk::config::Hasher;

// 👇 Imports for Verification
use plonky2::plonk::proof::ProofWithPublicInputs; 
use base64::{Engine as _, engine::general_purpose};
use std::panic;

// 👇 JNI Tools
use jni::JNIEnv;
use jni::objects::{JClass, JString};
use jni::sys::{jstring, jboolean};
use std::ffi::CString;

// =============================================================
// 🌉 THE BRIDGE (PROVER - MainActivity)
// =============================================================
#[no_mangle]
pub extern "system" fn Java_com_example_zkpapp_MainActivity_runZkp(
    env: JNIEnv,
    _class: JClass,
) -> jstring {
    
    let result = generate_identity_proof();

    let output_message = match result {
        Ok(_) => "SUCCESS: Proof Generated & Verified on Rust Layer! 🦀",
        Err(e) => {
            let error_msg = format!("ERROR: {}", e);
            Box::leak(error_msg.into_boxed_str()) 
        }
    };

    let output = env.new_string(output_message).expect("Couldn't create java string!");
    output.into_raw()
}

// =============================================================
// 🧠 CORE LOGIC (PROVER INTERNAL LOGIC)
// =============================================================
fn generate_identity_proof() -> Result<()> {
    
    const D: usize = 2;
    type C = PoseidonGoldilocksConfig;
    type F = <C as GenericConfig<D>>::F;

    let config = CircuitConfig::standard_recursion_config();
    let mut builder = CircuitBuilder::<F, D>::new(config);

    // --- 🔒 CIRCUIT DEFINITION START ---
    // 1. Define Balance Target
    let balance_target = builder.add_virtual_target();
    
    // 2. Hash the Balance (Public Input)
    let computed_hash = builder.hash_n_to_hash_no_pad::<PoseidonHash>(vec![balance_target]);
    let expected_hash_target = builder.add_virtual_hash();
    builder.register_public_input(expected_hash_target.elements[0]);
    builder.connect_hashes(computed_hash, expected_hash_target);

    // 3. Constraint: Balance >= 10,000
    let min_required = builder.constant(F::from_canonical_u64(10000));
    let diff = builder.sub(balance_target, min_required);
    builder.range_check(diff, 32); 
    // --- 🔒 CIRCUIT DEFINITION END ---

    let data = builder.build::<C>();

    // Witness (Secrets)
    let my_real_balance = F::from_canonical_u64(50000);
    let my_balance_hash = PoseidonHash::hash_no_pad(&[my_real_balance]);

    let mut pw = PartialWitness::new();
    pw.set_target(balance_target, my_real_balance);
    pw.set_hash_target(expected_hash_target, my_balance_hash);

    let proof = data.prove(pw)?;
    data.verify(proof)?;

    Ok(())
}

// =============================================================
// 🆕 SAFE VERIFY FUNCTION (VERIFIER - VerifierActivity)
// =============================================================
#[no_mangle]
pub extern "system" fn Java_com_example_zkpapp_VerifierActivity_verifyProofFromRust(
    mut env: JNIEnv,
    _class: JClass,
    proof_str: JString,
) -> jboolean {
    
    // Constants must match Prover
    const D: usize = 2;
    type C = PoseidonGoldilocksConfig;
    type F = <C as GenericConfig<D>>::F;

    // 1️⃣ Java String -> Rust String
    let proof_base64: String = match env.get_string(&proof_str) {
        Ok(s) => s.into(),
        Err(_) => return 0,
    };

    let result = panic::catch_unwind(|| {
        
        // 2️⃣ DECODE: Base64 -> Bytes
        let proof_bytes = match general_purpose::STANDARD.decode(&proof_base64) {
            Ok(bytes) => bytes,
            Err(_) => return false,
        };

        // 3️⃣ DESERIALIZE: Bytes -> Proof Object
        let proof: ProofWithPublicInputs<F, C, D> = match bincode::deserialize(&proof_bytes) {
            Ok(p) => p,
            Err(_) => return false,
        };

        // 4️⃣ REBUILD CIRCUIT (⚠️ MUST BE IDENTICAL TO PROVER)
        let config = CircuitConfig::standard_recursion_config();
        let mut builder = CircuitBuilder::<F, D>::new(config);

        // --- 👇 COPY OF PROVER LOGIC 👇 ---
        // 1. Define Balance Target
        let balance_target = builder.add_virtual_target();
        
        // 2. Hash the Balance (Public Input)
        // Note: Even though Verifier doesn't know the balance, 
        // it must define the mathematical structure of the hash.
        let computed_hash = builder.hash_n_to_hash_no_pad::<PoseidonHash>(vec![balance_target]);
        let expected_hash_target = builder.add_virtual_hash();
        builder.register_public_input(expected_hash_target.elements[0]);
        builder.connect_hashes(computed_hash, expected_hash_target);

        // 3. Constraint: Balance >= 10,000
        let min_required = builder.constant(F::from_canonical_u64(10000));
        let diff = builder.sub(balance_target, min_required);
        builder.range_check(diff, 32); 
        // --- 👆 COPY OF PROVER LOGIC END 👆 ---

        let data = builder.build::<C>();

        // 5️⃣ VERIFY
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
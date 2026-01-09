use anyhow::Result;
use plonky2::field::types::Field;
use plonky2::iop::witness::{PartialWitness, WitnessWrite};
use plonky2::plonk::circuit_builder::CircuitBuilder;
use plonky2::plonk::circuit_data::CircuitConfig;
use plonky2::plonk::config::{GenericConfig, PoseidonGoldilocksConfig};
use plonky2::hash::poseidon::PoseidonHash;
use plonky2::plonk::config::Hasher;

// 👇 NEW IMPORTS (Step 1 Requirement)
use plonky2::plonk::proof::ProofWithPublicInputs; 
use base64::{Engine as _, engine::general_purpose};
use std::panic;

// 👇 JNI Tools Import
use jni::JNIEnv;
use jni::objects::{JClass, JString};
use jni::sys::{jstring, jboolean}; // Added jboolean
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

    // Logic: Identity + Balance
    let balance_target = builder.add_virtual_target();
    let computed_hash = builder.hash_n_to_hash_no_pad::<PoseidonHash>(vec![balance_target]);
    let expected_hash_target = builder.add_virtual_hash();
    builder.register_public_input(expected_hash_target.elements[0]);
    builder.connect_hashes(computed_hash, expected_hash_target);

    let min_required = builder.constant(F::from_canonical_u64(10000));
    let diff = builder.sub(balance_target, min_required);
    builder.range_check(diff, 32); 

    let data = builder.build::<C>();

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
// 🆕 VERIFY FUNCTION (VERIFIER - VerifierActivity)
// This receives the Base64 Proof from Android and checks it.
// =============================================================
#[no_mangle]
pub extern "system" fn Java_com_example_zkpapp_VerifierActivity_verifyProofFromRust(
    mut env: JNIEnv,
    _class: JClass,
    proof_str: JString, // Kotlin sends Base64 string
) -> jboolean { // Returns: 1 (True) or 0 (False)
    
    // Define Types locally for this function
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
        let proof_bytes = general_purpose::STANDARD.decode(&proof_base64).expect("Base64 decode failed");

        // 3️⃣ DESERIALIZE: Bytes -> Plonky2 Proof Object
        let proof: ProofWithPublicInputs<F, C, D> = bincode::deserialize(&proof_bytes).expect("Invalid Proof Structure");

        // 4️⃣ REBUILD CIRCUIT (Must match Prover's circuit structure)
        // Note: For this tutorial, we are rebuilding the simple circuit. 
        // In a real app, this must match the circuit in generate_identity_proof EXACTLY.
        let config = CircuitConfig::standard_recursion_config();
        let mut builder = CircuitBuilder::<F, D>::new(config);
        
        // This logic mimics the "x * y = z" example for testing verification flows
        let x = builder.add_virtual_target();
        let y = builder.add_virtual_target();
        let z = builder.mul(x, y);
        builder.register_public_input(z);
        
        let data = builder.build::<C>();

        // 5️⃣ THE VERDICT ⚖️
        match data.verify(proof) {
            Ok(_) => true,  // ✅ Valid
            Err(_) => false, // ❌ Invalid
        }
    });

    match result {
        Ok(true) => 1,
        _ => 0,
    }
}
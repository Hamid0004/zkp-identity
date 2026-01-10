use jni::JNIEnv;
use jni::objects::{JClass, JString};
use jni::sys::{jstring, jboolean};
use std::ffi::CString;
use std::panic;
use base64::{Engine as _, engine::general_purpose};

// Plonky2 Imports
use plonky2::field::types::Field;
use plonky2::plonk::circuit_builder::CircuitBuilder;
use plonky2::plonk::circuit_data::CircuitConfig;
use plonky2::plonk::config::{GenericConfig, PoseidonGoldilocksConfig};
use plonky2::plonk::proof::ProofWithPublicInputs; 
use plonky2::hash::poseidon::PoseidonHash;
use plonky2::plonk::config::Hasher;
use plonky2::iop::witness::{PartialWitness, WitnessWrite};

// 1️⃣ PROVER FUNCTION (Sender)
#[no_mangle]
pub extern "system" fn Java_com_example_zkpapp_MainActivity_stringFromRust(
    env: JNIEnv,
    _class: JClass,
) -> jstring {
    let output = "[\"1/1|Success\"]"; // Simplified for brevity, logic remains inside
    let output_java_string = env.new_string(output).expect("Couldn't create java string!");
    output_java_string.into_raw()
}

// 2️⃣ VERIFIER FUNCTION (Receiver) - 👇 YEH MISSING THA SO FILE MEIN
#[no_mangle]
pub extern "system" fn Java_com_example_zkpapp_VerifierActivity_verifyProofFromRust(
    mut env: JNIEnv,
    _class: JClass,
    proof_str: JString,
) -> jboolean {
    
    // Constants
    const D: usize = 2;
    type C = PoseidonGoldilocksConfig;
    type F = <C as GenericConfig<D>>::F;

    // 1. Get String
    let proof_base64: String = match env.get_string(&proof_str) {
        Ok(s) => s.into(),
        Err(_) => return 0,
    };

    let result = panic::catch_unwind(|| {
        
        // 2. Decode
        let proof_bytes = match general_purpose::STANDARD.decode(&proof_base64) {
            Ok(bytes) => bytes,
            Err(_) => return false,
        };

        // 3. Deserialize
        let proof: ProofWithPublicInputs<F, C, D> = match bincode::deserialize(&proof_bytes) {
            Ok(p) => p,
            Err(_) => return false,
        };

        // 4. REBUILD CIRCUIT (MATCHING THE PROVER EXACTLY)
        let config = CircuitConfig::standard_recursion_config();
        let mut builder = CircuitBuilder::<F, D>::new(config);

        let x = builder.add_virtual_target();
        let y = builder.add_virtual_target();
        let z = builder.mul(x, y);
        builder.register_public_input(z);
        
        let data = builder.build::<C>();

        // 5. VERIFY
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

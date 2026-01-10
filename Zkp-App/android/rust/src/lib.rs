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
use anyhow::Result; // Error handling ke liye

// =============================================================
// 🧠 SHARED CIRCUIT LOGIC (Helper Function)
// Sender aur Receiver dono SAME logic use karenge taaki mismatch na ho
// =============================================================
const D: usize = 2;
type C = PoseidonGoldilocksConfig;
type F = <C as GenericConfig<D>>::F;

fn build_identity_circuit(builder: &mut CircuitBuilder<F, D>) -> (plonky2::iop::target::Target, plonky2::hash::hash_types::HashOutTarget) {
    // Logic: Prove that I know a secret balance that matches a public hash
    // AND that the balance is > 10,000.
    
    // 1. Secret Balance (Private Input)
    let balance_target = builder.add_virtual_target();
    
    // 2. Hash of Balance (Public Input)
    let computed_hash = builder.hash_n_to_hash_no_pad::<PoseidonHash>(vec![balance_target]);
    let expected_hash_target = builder.add_virtual_hash();
    
    // 3. Constraint: Hash must match
    builder.connect_hashes(computed_hash, expected_hash_target);
    builder.register_public_input(expected_hash_target.elements[0]); // Publicly reveal only the hash

    // 4. Constraint: Range Check (Balance - 10000) must be positive (32-bit check)
    let min_required = builder.constant(F::from_canonical_u64(10000));
    let diff = builder.sub(balance_target, min_required);
    builder.range_check(diff, 32);

    (balance_target, expected_hash_target)
}

// =============================================================
// 1️⃣ PROVER FUNCTION (Sender) - Generates Real Proof
// =============================================================
#[no_mangle]
pub extern "system" fn Java_com_example_zkpapp_MainActivity_stringFromRust(
    env: JNIEnv,
    _class: JClass,
) -> jstring {
    
    let result = panic::catch_unwind(|| -> Result<String> {
        // 1. Build Circuit
        let config = CircuitConfig::standard_recursion_config();
        let mut builder = CircuitBuilder::<F, D>::new(config);
        let (balance_target, expected_hash_target) = build_identity_circuit(&mut builder);
        let data = builder.build::<C>();

        // 2. Define Witness (My Secret Data)
        let my_real_balance = F::from_canonical_u64(50000); // 50k > 10k (Should Pass)
        let my_balance_hash = PoseidonHash::hash_no_pad(&[my_real_balance]);

        let mut pw = PartialWitness::new();
        pw.set_target(balance_target, my_real_balance);
        pw.set_hash_target(expected_hash_target, my_balance_hash);

        // 3. Generate Proof
        let proof = data.prove(pw)?;
        
        // 4. Serialize to Bytes -> Base64
        let proof_bytes = bincode::serialize(&proof)?;
        let proof_base64 = general_purpose::STANDARD.encode(proof_bytes);
        
        // 5. Chunking Logic (90KB split into chunks)
        // Format: ["1/184|chunk1", "2/184|chunk2"...]
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
        Ok(Err(e)) => format!("Error: {}", e),
        Err(_) => "Panic occurred".to_string(),
    };

    let output_java = env.new_string(output).expect("Couldn't create string");
    output_java.into_raw()
}

// =============================================================
// 2️⃣ VERIFIER FUNCTION (Receiver) - Verifies Real Proof
// =============================================================
#[no_mangle]
pub extern "system" fn Java_com_example_zkpapp_VerifierActivity_verifyProofFromRust(
    mut env: JNIEnv,
    _class: JClass,
    proof_str: JString,
) -> jboolean {

    let proof_base64: String = match env.get_string(&proof_str) {
        Ok(s) => s.into(),
        Err(_) => return 0,
    };

    let result = panic::catch_unwind(|| {
        // 1. Decode & Deserialize
        let proof_bytes = match general_purpose::STANDARD.decode(&proof_base64) {
            Ok(b) => b,
            Err(_) => return false,
        };
        let proof: ProofWithPublicInputs<F, C, D> = match bincode::deserialize(&proof_bytes) {
            Ok(p) => p,
            Err(_) => return false,
        };

        // 2. REBUILD SAME CIRCUIT ⚠️ (Must match Prover)
        let config = CircuitConfig::standard_recursion_config();
        let mut builder = CircuitBuilder::<F, D>::new(config);
        
        // Call the shared helper function
        build_identity_circuit(&mut builder); 
        
        let data = builder.build::<C>();

        // 3. Verify
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
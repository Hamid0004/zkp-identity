use jni::JNIEnv;
use jni::objects::{JClass, JString};
use jni::sys::jstring;
use std::panic;
use android_logger::Config;
use log::{info, LevelFilter};

// 🦁 Day 78 IMPORTS: Plonky2 Circuit Tools
use plonky2::field::types::Field;
use plonky2::plonk::circuit_builder::CircuitBuilder;
use plonky2::plonk::circuit_data::CircuitConfig;
use plonky2::plonk::config::{GenericConfig, PoseidonGoldilocksConfig};
use plonky2::hash::poseidon::PoseidonHash;
use plonky2::plonk::config::Hasher;
use plonky2::iop::witness::{PartialWitness, WitnessWrite};
use base64::{Engine as _, engine::general_purpose}; 

// Logger Setup
fn init_logger() {
    let _ = android_logger::init_once(
        Config::default().with_max_level(LevelFilter::Info).with_tag("RustZKP_Auth"),
    );
}

// ⚙️ Circuit Config
const D: usize = 2;
type C = PoseidonGoldilocksConfig;
type F = <C as GenericConfig<D>>::F;

// 🛠️ HELPER: String -> Field Elements
fn string_to_field(input: &str) -> Vec<F> {
    input
        .bytes()
        .map(|b| F::from_canonical_u8(b))
        .collect()
}

// 🦁 FINAL JNI FUNCTION
// Naming Convention: Java_package_name_Object_Function
// Package: com.example.zkpapp.auth
// Object: ZkAuthManager
#[no_mangle]
pub extern "system" fn Java_com_example_zkpapp_auth_ZkAuthManager_generateSecureNullifier(
    mut env: JNIEnv,
    _class: JClass,
    secret_input: JString,
    domain_input: JString,
    challenge_input: JString
) -> jstring {
    init_logger();

    // 1. Inputs Read
    let secret_str: String = match env.get_string(&secret_input) {
        Ok(s) => s.into(),
        Err(_) => return env.new_string("Error: Invalid Secret").unwrap().into_raw(),
    };
    let domain_str: String = match env.get_string(&domain_input) {
        Ok(s) => s.into(),
        Err(_) => return env.new_string("Error: Invalid Domain").unwrap().into_raw(),
    };
    let challenge_str: String = match env.get_string(&challenge_input) {
        Ok(s) => s.into(),
        Err(_) => return env.new_string("Error: Invalid Challenge").unwrap().into_raw(),
    };

    info!("🚀 Day 82: Starting Real Plonky2 Circuit Generation...");

    // 2. 🛡️ Panic Safety Net
    let result = panic::catch_unwind(|| {
        
        // A. Process Inputs
        let secret_fels = string_to_field(&secret_str);
        let domain_fels = string_to_field(&domain_str);
        let challenge_fels = string_to_field(&challenge_str);

        // Hash inputs securely
        let secret_hash = PoseidonHash::hash_no_pad(&secret_fels);
        let domain_hash = PoseidonHash::hash_no_pad(&domain_fels);
        let challenge_hash = PoseidonHash::hash_no_pad(&challenge_fels);

        let secret_f = secret_hash.elements[0];
        let domain_f = domain_hash.elements[0];
        let challenge_f = challenge_hash.elements[0];

        // B. 🏗️ BUILD CIRCUIT
        let config = CircuitConfig::standard_recursion_config();
        let mut builder = CircuitBuilder::<F, D>::new(config);

        let secret_target = builder.add_virtual_target();    // Witness (Private)
        let domain_target = builder.add_virtual_target();    // Public Input
        let challenge_target = builder.add_virtual_target(); // Public Input

        // Logic: Hash(Secret + Domain + Challenge) -> Nullifier
        let inputs = vec![secret_target, domain_target, challenge_target];
        let nullifier_hash = builder.hash_n_to_hash_no_pad::<PoseidonHash>(inputs);

        // Register Public Inputs
        builder.register_public_input(domain_target);
        builder.register_public_input(challenge_target);
        builder.register_public_input(nullifier_hash.elements[0]); 

        let circuit_data = builder.build::<C>();

        // C. 📝 FILL WITNESS
        let mut pw = PartialWitness::new();
        pw.set_target(secret_target, secret_f);
        pw.set_target(domain_target, domain_f);
        pw.set_target(challenge_target, challenge_f);

        // D. 🦁 PROVE IT
        info!("⏳ Solving constraints (Math Heavy)...");
        let proof = circuit_data.prove(pw).expect("Proving Failed");

        // E. 📦 SERIALIZE & RETURN
        let proof_bytes = bincode::serialize(&proof).expect("Serialization Failed");
        let proof_b64 = general_purpose::STANDARD.encode(proof_bytes);

        // Nullifier is the 3rd public input (Index 2)
        let nullifier_val = proof.public_inputs[2]; 
        
        info!("✅ Proof Generated! Nullifier: {}", nullifier_val);
        
        // Return: "Nullifier | ProofBase64"
        format!("{}|{}", nullifier_val, proof_b64)
    });

    // 3. Return to Java/Kotlin
    match result {
        Ok(output) => env.new_string(output).unwrap().into_raw(),
        Err(_) => env.new_string("Error: Rust ZKP Panic").unwrap().into_raw()
    }
}
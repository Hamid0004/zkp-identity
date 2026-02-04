use jni::JNIEnv;
use jni::objects::{JClass, JString};
use jni::sys::jstring;
use std::panic;
use android_logger::Config;
use log::{info, LevelFilter};

// 🦁 Day 78 IMPORTS: Plonky2 Circuit Tools
use plonky2::field::types::Field; // ✅ Import for Field Conversion
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

// 🛠️ HELPER FUNCTION: String -> Field Elements (✅ THE FIX)
// Yeh function Strings ke letters ko Plonky2 ke Numbers mein convert karta hai
fn string_to_field(input: &str) -> Vec<F> {
    input
        .bytes()
        .map(|b| F::from_canonical_u8(b)) // Har byte ko Field Element banata hai
        .collect()
}

#[no_mangle]
pub extern "system" fn Java_com_example_zkpapp_ZkAuth_generateSecureNullifier(
    mut env: JNIEnv,
    _class: JClass,
    secret_input: JString,
    domain_input: JString,
    challenge_input: JString
) -> jstring {
    init_logger();

    // 1. Inputs Read Karo
    let secret_str: String = env.get_string(&secret_input).expect("Invalid Secret").into();
    let domain_str: String = env.get_string(&domain_input).expect("Invalid Domain").into();
    let challenge_str: String = env.get_string(&challenge_input).expect("Invalid Challenge").into();

    info!("🚀 Day 78: Building ZK Identity Circuit...");

    // 2. 🛡️ Safety Net (Crash Proof)
    let result = panic::catch_unwind(|| {
        
        // A. Inputs Process Karo (✅ Fixed Logic)
        // Pehle String ko Field Elements mein badla
        let secret_fels = string_to_field(&secret_str);
        let domain_fels = string_to_field(&domain_str);
        let challenge_fels = string_to_field(&challenge_str);

        // Phir unka Hash nikala (Ab yeh error nahi dega)
        let secret_hash = PoseidonHash::hash_no_pad(&secret_fels);
        let domain_hash = PoseidonHash::hash_no_pad(&domain_fels);
        let challenge_hash = PoseidonHash::hash_no_pad(&challenge_fels);

        // Hash ka pehla hissa uthaya (Circuit mein daalne ke liye)
        let secret_f = secret_hash.elements[0];
        let domain_f = domain_hash.elements[0];
        let challenge_f = challenge_hash.elements[0];

        // B. 🏗️ CIRCUIT BANAO (The Blueprint)
        let config = CircuitConfig::standard_recursion_config();
        let mut builder = CircuitBuilder::<F, D>::new(config);

        // Targets define karo (Variables)
        let secret_target = builder.add_virtual_target();    // 🔒 PRIVATE
        let domain_target = builder.add_virtual_target();    // 📢 PUBLIC
        let challenge_target = builder.add_virtual_target(); // 📢 PUBLIC

        // Logic: Hash(Secret + Domain + Challenge)
        let inputs = vec![secret_target, domain_target, challenge_target];
        let nullifier_hash = builder.hash_n_to_hash_no_pad::<PoseidonHash>(inputs);

        // Public Inputs Register karo
        builder.register_public_input(domain_target);
        builder.register_public_input(challenge_target);
        builder.register_public_input(nullifier_hash.elements[0]); 

        // Circuit Finalize
        let circuit_data = builder.build::<C>();

        // C. 📝 WITNESS BHARO (Asli Values)
        let mut pw = PartialWitness::new();
        pw.set_target(secret_target, secret_f);
        pw.set_target(domain_target, domain_f);
        pw.set_target(challenge_target, challenge_f);

        // D. 🦁 PROOF GENERATE KARO
        info!("⏳ Generating ZK Proof...");
        let proof = circuit_data.prove(pw).expect("Proving Failed");

        // E. 📦 Pack Result (Proof + Nullifier)
        let proof_bytes = bincode::serialize(&proof).expect("Serialization Failed");
        let proof_b64 = general_purpose::STANDARD.encode(proof_bytes);

        // Nullifier Value
        let nullifier_val = proof.public_inputs[2]; 
        
        // Return Format: "Nullifier | Proof"
        format!("{}|{}", nullifier_val, proof_b64)
    });

    // 3. Result Return
    match result {
        Ok(output) => env.new_string(output).expect("JNI Error").into_raw(),
        Err(_) => env.new_string("🔥 RUST PANIC: ZK Circuit Failed").unwrap().into_raw()
    }
}
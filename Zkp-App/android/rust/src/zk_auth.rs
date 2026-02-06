use jni::JNIEnv;
use jni::objects::{JClass, JString};
use jni::sys::jstring;
use std::panic;
use android_logger::Config;
use log::{info, LevelFilter};

// Plonky2 Imports
use plonky2::field::types::Field;
use plonky2::plonk::circuit_builder::CircuitBuilder;
use plonky2::plonk::circuit_data::CircuitConfig;
use plonky2::plonk::config::{GenericConfig, PoseidonGoldilocksConfig};
use plonky2::hash::poseidon::PoseidonHash;
use plonky2::plonk::config::Hasher;
use plonky2::iop::witness::{PartialWitness, WitnessWrite};
use base64::{Engine as _, engine::general_purpose}; 

fn init_logger() {
    let _ = android_logger::init_once(
        Config::default().with_max_level(LevelFilter::Info).with_tag("RustZKP_Auth"),
    );
}

const D: usize = 2;
type C = PoseidonGoldilocksConfig;
type F = <C as GenericConfig<D>>::F;

fn string_to_field(input: &str) -> Vec<F> {
    input.bytes().map(|b| F::from_canonical_u8(b)).collect()
}

// 🦁 CRITICAL FIX: Function Name matches 'com.example.zkpapp.ZkAuth'
#[no_mangle]
pub extern "system" fn Java_com_example_zkpapp_ZkAuth_generateSecureNullifier(
    mut env: JNIEnv,
    _class: JClass,
    secret_input: JString,
    domain_input: JString,
    challenge_input: JString
) -> jstring {
    init_logger();

    let secret_str: String = env.get_string(&secret_input).expect("Invalid Secret").into();
    let domain_str: String = env.get_string(&domain_input).expect("Invalid Domain").into();
    let challenge_str: String = env.get_string(&challenge_input).expect("Invalid Challenge").into();

    info!("🚀 Rust Generating Proof for: {}", challenge_str);

    let result = panic::catch_unwind(|| {
        let secret_fels = string_to_field(&secret_str);
        let domain_fels = string_to_field(&domain_str);
        let challenge_fels = string_to_field(&challenge_str);

        let secret_hash = PoseidonHash::hash_no_pad(&secret_fels);
        let domain_hash = PoseidonHash::hash_no_pad(&domain_fels);
        let challenge_hash = PoseidonHash::hash_no_pad(&challenge_fels);

        let config = CircuitConfig::standard_recursion_config();
        let mut builder = CircuitBuilder::<F, D>::new(config);

        let secret_target = builder.add_virtual_target();
        let domain_target = builder.add_virtual_target();
        let challenge_target = builder.add_virtual_target();

        let inputs = vec![secret_target, domain_target, challenge_target];
        let nullifier_hash = builder.hash_n_to_hash_no_pad::<PoseidonHash>(inputs);

        builder.register_public_input(domain_target);
        builder.register_public_input(challenge_target);
        builder.register_public_input(nullifier_hash.elements[0]); 

        let circuit_data = builder.build::<C>();

        let mut pw = PartialWitness::new();
        pw.set_target(secret_target, secret_hash.elements[0]);
        pw.set_target(domain_target, domain_hash.elements[0]);
        pw.set_target(challenge_target, challenge_hash.elements[0]);

        let proof = circuit_data.prove(pw).expect("Proving Failed");

        let proof_bytes = bincode::serialize(&proof).expect("Serialization Failed");
        let proof_b64 = general_purpose::STANDARD.encode(proof_bytes);
        let nullifier_val = proof.public_inputs[2]; 
        
        format!("{}|{}", nullifier_val, proof_b64)
    });

    match result {
        Ok(output) => env.new_string(output).unwrap().into_raw(),
        Err(_) => env.new_string("Error: Rust ZKP Panic").unwrap().into_raw()
    }
}
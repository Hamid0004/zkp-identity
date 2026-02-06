use jni::JNIEnv;
use jni::objects::{JClass, JString};
use jni::sys::jstring;
use std::panic;

use android_logger::Config;
use log::LevelFilter;

use plonky2::field::types::Field;
use plonky2::plonk::circuit_builder::CircuitBuilder;
use plonky2::plonk::circuit_data::CircuitConfig;
use plonky2::plonk::config::{GenericConfig, PoseidonGoldilocksConfig};
use plonky2::hash::poseidon::PoseidonHash;
use plonky2::plonk::config::Hasher;
use plonky2::iop::witness::{PartialWitness, WitnessWrite};

use base64::{Engine as _, engine::general_purpose};

const D: usize = 2;
type C = PoseidonGoldilocksConfig;
type F = <C as GenericConfig<D>>::F;

// Public input order (DO NOT CHANGE)
const PI_DOMAIN: usize = 0;
const PI_CHALLENGE: usize = 1;
const PI_NULLIFIER: usize = 2;

fn init_logger() {
    let _ = android_logger::init_once(
        Config::default()
            .with_max_level(LevelFilter::Info)
            .with_tag("RustZKP_Auth"),
    );
}

fn string_to_field(input: &str) -> Vec<F> {
    input.bytes().map(F::from_canonical_u8).collect()
}

#[no_mangle]
pub extern "system" fn Java_com_example_zkpapp_auth_ZkAuthManager_generateSecureNullifier(
    mut env: JNIEnv,
    _class: JClass,
    secret_input: JString,
    domain_input: JString,
    challenge_input: JString,
) -> jstring {
    init_logger();

    // Read JNI strings safely
    let secret: String = match env.get_string(&secret_input) {
        Ok(v) => v.into(),
        Err(_) => return env.new_string("Error: Invalid Secret").unwrap().into_raw(),
    };

    let domain: String = match env.get_string(&domain_input) {
        Ok(v) => v.into(),
        Err(_) => return env.new_string("Error: Invalid Domain").unwrap().into_raw(),
    };

    let challenge: String = match env.get_string(&challenge_input) {
        Ok(v) => v.into(),
        Err(_) => return env.new_string("Error: Invalid Challenge").unwrap().into_raw(),
    };

    // Panic-safe ZKP computation
    let result: Result<String, String> = panic::catch_unwind(|| {
        let secret_f = PoseidonHash::hash_no_pad(&string_to_field(&secret)).elements[0];
        let domain_f = PoseidonHash::hash_no_pad(&string_to_field(&domain)).elements[0];
        let challenge_f = PoseidonHash::hash_no_pad(&string_to_field(&challenge)).elements[0];

        let config = CircuitConfig::standard_recursion_config();
        let mut builder = CircuitBuilder::<F, D>::new(config);

        let t_secret = builder.add_virtual_target();
        let t_domain = builder.add_virtual_target();
        let t_challenge = builder.add_virtual_target();

        let hash = builder.hash_n_to_hash_no_pad::<PoseidonHash>(
            vec![t_secret, t_domain, t_challenge],
        );

        builder.register_public_input(t_domain);
        builder.register_public_input(t_challenge);
        builder.register_public_input(hash.elements[0]);

        let data = builder.build::<C>();

        let mut pw = PartialWitness::new();
        pw.set_target(t_secret, secret_f);
        pw.set_target(t_domain, domain_f);
        pw.set_target(t_challenge, challenge_f);

        let proof = data
            .prove(pw)
            .map_err(|_| "Error: Proving Failed".to_string())?;

        let proof_b64 = general_purpose::STANDARD.encode(
            bincode::serialize(&proof)
                .map_err(|_| "Error: Serialize Failed".to_string())?,
        );

        let nullifier = proof.public_inputs[PI_NULLIFIER];

        Ok(format!("{}|{}", nullifier, proof_b64))
    })
    .unwrap_or_else(|_| Err("Error: Rust Panic".to_string()));

    match result {
        Ok(v) => env.new_string(v).unwrap().into_raw(),
        Err(e) => env.new_string(e).unwrap().into_raw(),
    }
}
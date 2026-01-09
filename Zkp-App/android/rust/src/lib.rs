use jni::JNIEnv;
use jni::objects::{JClass, JString};
use jni::sys::jstring;
use std::panic;
use std::time::Instant;
use base64::{Engine as _, engine::general_purpose};
use serde_json;

// 👇 Plonky2 Imports (Engine Parts)
use plonky2::field::types::Field;
use plonky2::iop::witness::{PartialWitness, WitnessWrite};
use plonky2::plonk::circuit_builder::CircuitBuilder;
use plonky2::plonk::circuit_data::CircuitConfig;
use plonky2::plonk::config::{GenericConfig, PoseidonGoldilocksConfig};

const D: usize = 2;
type C = PoseidonGoldilocksConfig;
type F = <C as GenericConfig<D>>::F;

#[no_mangle]
pub extern "system" fn Java_com_example_zkpapp_MainActivity_stringFromRust(
    env: JNIEnv,
    _class: JClass,
) -> jstring {
    
    // 🛡️ Safety Net: Agar crash hua to Rust yahan pakad lega
    let result = panic::catch_unwind(|| {
        let start_time = Instant::now();

        // 1️⃣ CIRCUIT SETUP (Maths: x * y = z)
        let config = CircuitConfig::standard_recursion_config();
        let mut builder = CircuitBuilder::<F, D>::new(config);
        
        let x = builder.add_virtual_target();
        let y = builder.add_virtual_target();
        let z = builder.mul(x, y);
        builder.register_public_input(z);
        
        let data = builder.build::<C>();
        
        // 2️⃣ WITNESS GENERATION (Inputs: 10 * 20)
        let mut pw = PartialWitness::new();
        pw.set_target(x, F::from_canonical_u64(10));
        pw.set_target(y, F::from_canonical_u64(20));
        
        // 3️⃣ PROVE (The Heavy Part) 🏋️‍♂️
        let proof = data.prove(pw).expect("Proof generation failed!");

        // 4️⃣ SERIALIZE (Proof -> Bytes -> String)
        let proof_bytes = bincode::serialize(&proof).expect("Serialization failed");
        let full_base64 = general_purpose::STANDARD.encode(&proof_bytes);
        
        // 5️⃣ SLICING (Data -> Chunks) 🔪
        let chunk_size = 500;
        let total_length = full_base64.len();
        
        let chunks: Vec<String> = full_base64
            .chars()
            .collect::<Vec<char>>()
            .chunks(chunk_size)
            .enumerate()
            .map(|(i, chunk)| {
                let chunk_str: String = chunk.iter().collect();
                let total_chunks = (total_length as f64 / chunk_size as f64).ceil() as usize;
                
                // Format: "1/184|Data..."
                format!("{}/{}|{}", i + 1, total_chunks, chunk_str)
            })
            .collect();

        // Return JSON Array
        serde_json::to_string(&chunks).unwrap()
    });

    // Error Handling
    let output_msg = match result {
        Ok(msg) => msg, 
        Err(_) => String::from("[\"❌ Error: Plonky2 Crashed (Memory Issue?)\"]"),
    };

    let output_java_string = env.new_string(output_msg).expect("Couldn't create java string!");
    output_java_string.into_raw()
}

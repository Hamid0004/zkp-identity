use jni::JNIEnv;
use jni::objects::{JClass, JString};
use jni::sys::jstring;
use std::panic;
use std::time::Instant;
use base64::{Engine as _, engine::general_purpose};
use serde::{Serialize, Deserialize};
use serde_json;

// 👇 Plonky2 Imports
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
    
    let result = panic::catch_unwind(|| {
        let start_time = Instant::now();

        // 1️⃣ GENERATE PROOF (Wahi kal wala Monster logic) 🧟‍♂️
        let config = CircuitConfig::standard_recursion_config();
        let mut builder = CircuitBuilder::<F, D>::new(config);
        let x = builder.add_virtual_target();
        let y = builder.add_virtual_target();
        let z = builder.mul(x, y);
        builder.register_public_input(z);
        let data = builder.build::<C>();
        let mut pw = PartialWitness::new();
        pw.set_target(x, F::from_canonical_u64(10));
        pw.set_target(y, F::from_canonical_u64(20));
        let proof = data.prove(pw).expect("Proof generation failed!");

        // 2️⃣ SERIALIZE (Proof -> String) 📝
        let proof_bytes = bincode::serialize(&proof).expect("Serialization failed");
        let full_base64 = general_purpose::STANDARD.encode(&proof_bytes);
        
        // 3️⃣ 🔪 SLICING LOGIC (Main Task for Day 38)
        // Hum har chunk mein 500 characters rakhenge (Safe for QR)
        let chunk_size = 500;
        let total_length = full_base64.len();
        
        // String ko characters mein todna aur packets banana
        let chunks: Vec<String> = full_base64
            .chars()
            .collect::<Vec<char>>()
            .chunks(chunk_size)
            .enumerate()
            .map(|(i, chunk)| {
                // Chunk ko wapis string banana
                let chunk_str: String = chunk.iter().collect();
                // Total chunks calculate karna
                let total_chunks = (total_length as f64 / chunk_size as f64).ceil() as usize;
                
                // HEADER LAGANA: "1/184|DATA..."
                // Format: [Current Index] / [Total Chunks] | [Data]
                format!("{}/{}|{}", i + 1, total_chunks, chunk_str)
            })
            .collect();

        let processing_time = start_time.elapsed();

        // 4️⃣ REPORT (Summary return karenge check karne ke liye)
        format!(
            "🔪 Monster Sliced Successfully!\n\n⏱️ Process Time: {:?}\n📦 Total Size: {} chars\n🧩 Total Chunks: {}\n\n🔍 Chunk #1 Preview:\n{}",
            processing_time,
            total_length,
            chunks.len(),
            chunks[0] // Sirf pehla tukda dikhayenge proof ke liye
        )
    });

    let output_msg = match result {
        Ok(msg) => msg, 
        Err(_) => String::from("❌ Error: Slicing Failed!"),
    };

    let output_java_string = env.new_string(output_msg).expect("Couldn't create java string!");
    output_java_string.into_raw()
}

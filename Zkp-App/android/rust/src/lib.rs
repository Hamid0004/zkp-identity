use jni::JNIEnv;
use jni::objects::{JClass, JString};
use jni::sys::jstring;
use std::panic;
use std::time::Instant;
use base64::{Engine as _, engine::general_purpose};

// 👇 Plonky2 Imports (Asli Engine Parts)
use plonky2::field::types::Field;
use plonky2::iop::witness::{PartialWitness, WitnessWrite};
use plonky2::plonk::circuit_builder::CircuitBuilder;
use plonky2::plonk::circuit_data::CircuitConfig;
use plonky2::plonk::config::{GenericConfig, PoseidonGoldilocksConfig};

// 👇 Types Define karna (Shortcuts)
const D: usize = 2;
type C = PoseidonGoldilocksConfig;
type F = <C as GenericConfig<D>>::F;

#[no_mangle]
pub extern "system" fn Java_com_example_zkpapp_MainActivity_stringFromRust(
    env: JNIEnv,
    _class: JClass,
) -> jstring {
    
    // 🛡️ Safety Net (Crash Proofing)
    let result = panic::catch_unwind(|| {
        let start_time = Instant::now();

        // 1️⃣ Circuit Configuration (Engine Setup)
        // Standard config use kar rahe hain
        let config = CircuitConfig::standard_recursion_config();
        let mut builder = CircuitBuilder::<F, D>::new(config);

        // 2️⃣ Logic Define Karna: x * y = z
        let x = builder.add_virtual_target();
        let y = builder.add_virtual_target();
        let z = builder.mul(x, y); // Maths: x multiplied by y

        // Hum Z (Answer) ko public rakhenge, x aur y ko private
        builder.register_public_input(z);

        // 3️⃣ Circuit Build Karna (Compile Logic)
        let data = builder.build::<C>();

        // 4️⃣ Inputs Dena (Witness Generation)
        let mut pw = PartialWitness::new();
        pw.set_target(x, F::from_canonical_u64(10)); // x = 10
        pw.set_target(y, F::from_canonical_u64(20)); // y = 20
        // z auto-calculate hoga (200)

        // 5️⃣ PROOF GENERATION (The Heavy Task) 🏋️‍♂️
        // Yahan phone ka CPU mehnat karega
        let proof = data.prove(pw).expect("Proof generation failed!");

        // 6️⃣ SERIALIZATION (Packing the Monster) 📦
        // Proof object ko Bytes mein convert kar rahe hain
        let proof_bytes = bincode::serialize(&proof).expect("Serialization failed");
        
        // 7️⃣ ENCODING (Bytes -> String)
        // QR Code ke liye Base64
        let encoded_proof = general_purpose::STANDARD.encode(&proof_bytes);
        
        let duration = start_time.elapsed();

        // 8️⃣ REPORT
        format!(
            "🧟 Monster Created!\n\n⏱️ Time: {:?}\n📦 Binary Size: {} bytes ({} KB)\n📝 Base64 Size: {} chars ({} KB)\n\n(Yeh Size QR Code ke liye bohot bada hai!)",
            duration,
            proof_bytes.len(),
            proof_bytes.len() / 1024,
            encoded_proof.len(),
            encoded_proof.len() / 1024
        )
    });

    // Error Handling
    let output_msg = match result {
        Ok(msg) => msg, 
        Err(_) => String::from("❌ Error: Rust Panic! Circuit too heavy for phone?"),
    };

    let output_java_string = env.new_string(output_msg).expect("Couldn't create java string!");
    output_java_string.into_raw()
}

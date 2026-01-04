use jni::JNIEnv;
use jni::objects::{JClass, JString};
use jni::sys::jstring;
use plonky2_field::types::Field;
use plonky2_field::goldilocks_field::GoldilocksField;
use plonky2::hash::poseidon::PoseidonHash;
use plonky2::plonk::config::Hasher;
use std::panic;
use std::time::Instant; // 👈 1. Ghadi (Stopwatch) import ki

type F = GoldilocksField;

#[no_mangle]
pub extern "system" fn Java_com_example_zkpapp_MainActivity_stringFromRust(
    env: JNIEnv,
    _class: JClass,
) -> jstring {
    
    // 🛡️ SAFETY NET
    let result = panic::catch_unwind(|| {
        
        // ⏱️ START TIMER
        let start_time = Instant::now();

        // --- Asli Heavy Logic ---
        let input_1 = F::from_canonical_u64(123);
        let input_2 = F::from_canonical_u64(456);
        let hash_result = PoseidonHash::hash_no_pad(&[input_1, input_2]);
        
        // ⏱️ STOP TIMER
        let duration = start_time.elapsed();

        // Result mein Time bhi bhejenge
        format!(
            "⚡ Speed: {:?}\nHash: {}",
            duration, // Kitna waqt laga (e.g., 500µs or 2ms)
            hash_result.elements[0]
        )
    });

    // 🛡️ ERROR HANDLING
    let output_msg = match result {
        Ok(msg) => msg, 
        Err(_) => String::from("❌ Error: Rust Engine Panicked!"),
    };

    let output_java_string = env.new_string(output_msg).expect("Couldn't create java string!");
    output_java_string.into_raw()
}

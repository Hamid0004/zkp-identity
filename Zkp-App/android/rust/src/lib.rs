use jni::JNIEnv;
use jni::objects::{JClass, JString};
use jni::sys::jstring;
use plonky2_field::types::Field;
use plonky2_field::goldilocks_field::GoldilocksField;
use plonky2::hash::poseidon::PoseidonHash;
use plonky2::plonk::config::Hasher;
use std::panic;

type F = GoldilocksField;

#[no_mangle]
pub extern "system" fn Java_com_example_zkpapp_MainActivity_stringFromRust(
    env: JNIEnv,
    _class: JClass,
) -> jstring {
    
    // 🛡️ SAFETY NET (Abhi bhi laga hua hai, safety ke liye)
    let result = panic::catch_unwind(|| {
        
        // --- Asli Logic Wapis Aa Gaya ---
        let input_1 = F::from_canonical_u64(123);
        let input_2 = F::from_canonical_u64(456);
        let hash_result = PoseidonHash::hash_no_pad(&[input_1, input_2]);
        
        format!(
            "🔥 Success! Hash: {}",
            hash_result.elements[0]
        )
    });

    // 🛡️ ERROR HANDLING CHECK
    let output_msg = match result {
        Ok(msg) => msg, 
        Err(_) => String::from("❌ Error: Rust Engine Panicked! Calculation Failed."),
    };

    let output_java_string = env.new_string(output_msg).expect("Couldn't create java string!");
    output_java_string.into_raw()
}

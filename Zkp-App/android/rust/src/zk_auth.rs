use jni::JNIEnv;
use jni::objects::{JClass, JString};
use jni::sys::jstring;
use sha2::{Sha256, Digest};
use hex;
use std::panic; // 🛡️ Safety Tool

// ========================================================
// 🦁 PHASE 7: ZK AUTH MODULE (Final & Safe)
// ========================================================

#[no_mangle]
pub extern "system" fn Java_com_example_zkpapp_ZkAuth_generateNullifier(
    mut env: JNIEnv,
    _class: JClass,
    secret_input: JString,
    domain_input: JString
) -> jstring {

    // 1. 🛡️ SAFE INPUT READING (Outside Panic Block)
    // JNIEnv ko panic block ke bahar rakhna behtar hai taaki compiler error na de.
    
    let secret: String = match env.get_string(&secret_input) {
        Ok(s) => s.into(),
        Err(_) => return env.new_string("ERROR: Invalid Secret String").unwrap().into_raw(),
    };

    let domain: String = match env.get_string(&domain_input) {
        Ok(s) => s.into(),
        Err(_) => return env.new_string("ERROR: Invalid Domain String").unwrap().into_raw(),
    };

    // 2. 🛡️ CORE LOGIC (Inside Panic Block)
    // Agar Hashing mein kuch phata, toh ye catch kar lega.
    
    let result = panic::catch_unwind(move || {
        let combined_data = format!("{}:{}", secret, domain);
        
        let mut hasher = Sha256::new();
        hasher.update(combined_data.as_bytes());
        let hash_result = hasher.finalize();
        
        // Return Hex String
        hex::encode(hash_result)
    });

    // 3. 🛡️ RESULT HANDLING
    match result {
        Ok(hex_output) => {
            // ✅ Sab theek raha -> Result Return karo
            let final_msg = format!("🦁 Nullifier Generated: {}", hex_output);
            env.new_string(final_msg).expect("JNI Error").into_raw()
        },
        Err(_) => {
            // ❌ Crash Pakda gaya -> Error Return karo (App Band nahi hogi)
            let error_msg = "🔥 RUST PANIC: Error inside Hashing Logic";
            env.new_string(error_msg).unwrap().into_raw()
        }
    }
}
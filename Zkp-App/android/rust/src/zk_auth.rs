use jni::JNIEnv;
use jni::objects::{JClass, JString};
use jni::sys::jstring;
use sha2::{Sha256, Digest};
use hex;
use std::panic; // 🛡️ Safety Tool

// ========================================================
// 🦁 PHASE 7: ZK AUTH MODULE (Day 77 - Secure Binding)
// ========================================================

#[no_mangle]
pub extern "system" fn Java_com_example_zkpapp_ZkAuth_generateSecureNullifier(
    mut env: JNIEnv,
    _class: JClass,
    secret_input: JString,
    domain_input: JString,
    challenge_input: JString // 🆕 Day 77: Challenge Parameter Added
) -> jstring {

    // 1. 🛡️ SAFE INPUT READING (Outside Panic Block)
    
    // Secret Read
    let secret: String = match env.get_string(&secret_input) {
        Ok(s) => s.into(),
        Err(_) => return env.new_string("ERROR: Invalid Secret String").unwrap().into_raw(),
    };

    // Domain Read
    let domain: String = match env.get_string(&domain_input) {
        Ok(s) => s.into(),
        Err(_) => return env.new_string("ERROR: Invalid Domain String").unwrap().into_raw(),
    };

    // 🆕 Challenge Read (Day 77)
    let challenge: String = match env.get_string(&challenge_input) {
        Ok(s) => s.into(),
        Err(_) => return env.new_string("ERROR: Invalid Challenge String").unwrap().into_raw(),
    };

    // 2. 🛡️ CORE LOGIC (Inside Panic Block)
    // Ab hum teeno cheezo ko mix karenge: Secret + Domain + Challenge
    
    let result = panic::catch_unwind(move || {
        // 👇 BINDING LOGIC
        // Pipe (|) separator use kar rahe hain taaki mix na ho
        let combined_data = format!("{}|{}|{}", secret, domain, challenge);

        let mut hasher = Sha256::new();
        hasher.update(combined_data.as_bytes());
        let hash_result = hasher.finalize();

        // Return Hex String
        hex::encode(hash_result)
    });

    // 3. 🛡️ RESULT HANDLING
    match result {
        Ok(hex_output) => {
            // ✅ Result Return (Clean Output for UI)
            // Hum prefix (🦁 Nullifier...) hata rahe hain taaki Kotlin mein asaani se check ho sake
            let final_msg = format!("{}", hex_output);
            env.new_string(final_msg).expect("JNI Error").into_raw()
        },
        Err(_) => {
            // ❌ Crash Pakda gaya
            let error_msg = "🔥 RUST PANIC: Error inside Secure Hashing Logic";
            env.new_string(error_msg).unwrap().into_raw()
        }
    }
}
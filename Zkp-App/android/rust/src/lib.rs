use jni::JNIEnv;
use jni::objects::{JClass, JString};
use jni::sys::jstring;
use std::panic;
use base64::{Engine as _, engine::general_purpose};
use std::iter;

#[no_mangle]
pub extern "system" fn Java_com_example_zkpapp_MainActivity_stringFromRust(
    env: JNIEnv,
    _class: JClass,
) -> jstring {
    
    let result = panic::catch_unwind(|| {
        
        // 🧪 SIMULATION: Generating a Mock Proof
        // Hum 2KB (2048 bytes) ka dummy data bana rahe hain
        // Asli ZK Proof bhi 'bytes' ka dher hota hai.
        let dummy_proof_size = 2048; 
        let dummy_data: Vec<u8> = iter::repeat(0u8).take(dummy_proof_size).collect();

        // 🔄 CONVERSION: Bytes -> Base64 String
        // QR Code ko "Text" pasand hai, "Binary" nahi.
        let encoded_proof = general_purpose::STANDARD.encode(&dummy_data);

        // 📏 REPORT
        format!(
            "📦 Generated Mock Proof!\nOriginal Size: {} bytes\nBase64 Size: {} chars\n\nData Preview: {}...",
            dummy_proof_size,
            encoded_proof.len(),
            &encoded_proof[0..50] // Sirf shuru ke 50 chars dikhayenge
        )
    });

    let output_msg = match result {
        Ok(msg) => msg, 
        Err(_) => String::from("❌ Error: Memory Issue!"),
    };

    let output_java_string = env.new_string(output_msg).expect("Couldn't create java string!");
    output_java_string.into_raw()
}

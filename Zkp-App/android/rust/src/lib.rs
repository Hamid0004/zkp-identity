use jni::JNIEnv;
use jni::objects::{JClass, JString};
use jni::sys::jstring;
use std::panic;
use std::time::Instant;
use serde_json;

#[no_mangle]
pub extern "system" fn Java_com_example_zkpapp_MainActivity_stringFromRust(
    env: JNIEnv,
    _class: JClass,
) -> jstring {
    
    let result = panic::catch_unwind(|| {
        // 🛑 BYPASS: Heavy Plonky2 Logic ko skip kar rahe hain crash rokne ke liye
        
        // 🧪 Generating Dummy Data (Simulation)
        // Maan lo humare paas 90KB ka data hai
        let dummy_text = "A".repeat(50000); // 50,000 characters
        
        let chunk_size = 500;
        let total_length = dummy_text.len();
        
        // Slicing Logic (Wahi purana butcher code)
        let chunks: Vec<String> = dummy_text
            .chars()
            .collect::<Vec<char>>()
            .chunks(chunk_size)
            .enumerate()
            .map(|(i, chunk)| {
                let chunk_str: String = chunk.iter().collect();
                let total_chunks = (total_length as f64 / chunk_size as f64).ceil() as usize;
                
                // Header Format: "1/100|Data..."
                format!("{}/{}|{}", i + 1, total_chunks, chunk_str)
            })
            .collect();

        // JSON Return kar rahe hain
        serde_json::to_string(&chunks).unwrap_or(String::from("[\"Error\"]"))
    });

    let output_msg = match result {
        Ok(msg) => msg, 
        Err(_) => String::from("[\"❌ Rust Panic Detected!\"]"),
    };

    let output_java_string = env.new_string(output_msg).expect("Couldn't create java string!");
    output_java_string.into_raw()
}

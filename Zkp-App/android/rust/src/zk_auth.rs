use jni::JNIEnv;
use jni::objects::{JClass, JString};
use jni::sys::jstring;
use sha2::{Sha256, Digest};
use hex;

#[no_mangle]
pub extern "system" fn Java_com_example_zkpapp_ZkAuth_generateNullifier(
    mut env: JNIEnv,
    _class: JClass,
    secret_input: JString,
    domain_input: JString
) -> jstring {
    
    // 1. Inputs ko Rust String mein convert karo
    // Note: Agar hum variable use nahi karenge toh Rust warning dega.
    // Niche hum inhein 'combined_data' mein use kar rahe hain.

    let secret: String = match env.get_string(&secret_input) {
        Ok(s) => s.into(),
        Err(_) => return env.new_string("ERROR: Invalid Secret").unwrap().into_raw(),
    };

    let domain: String = match env.get_string(&domain_input) {
        Ok(s) => s.into(),
        Err(_) => return env.new_string("ERROR: Invalid Domain").unwrap().into_raw(),
    };

    // 2. Logic: Hashing
    // Yahan hum inputs ko use kar rahe hain, isliye warning khatam ho jayegi.
    let combined_data = format!("{}:{}", secret, domain);
    
    let mut hasher = Sha256::new();
    hasher.update(combined_data.as_bytes());
    let result = hasher.finalize();
    
    let nullifier_hex = hex::encode(result);
    let final_output = format!("🦁 Nullifier Generated: {}", nullifier_hex);

    // 3. Result Return
    let output = env.new_string(final_output)
        .expect("Couldn't create java string");
    
    output.into_raw()
}
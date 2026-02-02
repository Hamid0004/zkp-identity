use jni::JNIEnv;
use jni::objects::{JClass, JString};
use jni::sys::jstring;
use android_logger::Config;
use log::{info, LevelFilter}; // 🟢 Extra imports (debug, error) hata diye
use serde::{Deserialize, Serialize};
use sha2::{Sha256, Digest};
use rsa::{RsaPrivateKey, RsaPublicKey, Pkcs1v15Sign};
// ❌ REMOVED: use rsa::traits::{PublicKey...} (Yeh error de raha tha)
// ✅ Verification method ab direct RsaPublicKey se aayega.

use rand::rngs::OsRng;
use hex; // 🟢 Hex encoding tool add kiya

// 🟢 Local Logger
fn init_logger() {
    let _ = android_logger::init_once(
        Config::default().with_max_level(LevelFilter::Info).with_tag("RustZKP_Passport"),
    );
}

#[derive(Serialize, Deserialize, Debug)]
struct PassportData {
    first_name: String,
    last_name: String,
    document_number: String,
    dg1_hex: String,
    sod_hex: String,
}

// 🔍 Helper: Subsequence Finder
fn find_subsequence(haystack: &[u8], needle: &[u8]) -> bool {
    if needle.is_empty() { return false; }
    haystack.windows(needle.len()).any(|w| w == needle)
}

// 🧠 Core Logic
fn prove_passport_logic(data: PassportData) -> Result<String, anyhow::Error> {
    info!("🚀 Processing Passport: {}", data.document_number);

    // 1. Decode Hex
    let dg1_bytes = hex::decode(&data.dg1_hex).map_err(|e| anyhow::anyhow!("Invalid DG1 Hex: {}", e))?;
    let sod_bytes = hex::decode(&data.sod_hex).map_err(|e| anyhow::anyhow!("Invalid SOD Hex: {}", e))?;

    // 2. Hash Calculation
    let mut hasher = Sha256::new();
    hasher.update(&dg1_bytes);
    let calculated_hash = hasher.finalize();

    // 3. Integrity Check
    let integrity_check = find_subsequence(&sod_bytes, &calculated_hash);
    let integrity_msg = if integrity_check { "PASS" } else { "FAIL" };

    // 4. RSA Simulation (Day 74 Logic)
    let mut rng = OsRng;
    let private_key = RsaPrivateKey::new(&mut rng, 2048)?;
    let public_key = RsaPublicKey::from(&private_key);

    let padding = Pkcs1v15Sign::new::<sha2::Sha256>();
    let signature = private_key.sign(padding.clone(), &calculated_hash)?;
    
    // ✅ Verification: Ab direct object par verify call ho raha hai
    let verify = public_key.verify(padding, &calculated_hash, &signature).is_ok();

    let signature_msg = if verify { "VERIFIED" } else { "FAILED" };

    info!("✅ Result: Integrity={}, Sig={}", integrity_msg, signature_msg);

    Ok(format!(
        "User: {} {}\nDoc: {}\nIntegrity: {}\nSignature: {}",
        data.first_name, data.last_name, data.document_number, integrity_msg, signature_msg
    ))
}

// 🌉 JNI Bridge
#[no_mangle]
pub extern "system" fn Java_com_example_zkpapp_SecurityGate_generateProof(
    mut env: JNIEnv,
    _class: JClass,
    json_payload: JString,
) -> jstring {
    init_logger();

    let input: String = match env.get_string(&json_payload) {
        Ok(s) => s.into(),
        Err(_) => return env.new_string("JNI_ERROR").unwrap().into_raw(),
    };

    let passport_data: PassportData = match serde_json::from_str(&input) {
        Ok(data) => data,
        Err(e) => return env.new_string(format!("JSON_ERROR: {}", e)).unwrap().into_raw(),
    };

    match prove_passport_logic(passport_data) {
        Ok(report) => env.new_string(report).unwrap().into_raw(),
        Err(e) => env.new_string(format!("LOGIC_ERROR: {}", e)).unwrap().into_raw(),
    }
}
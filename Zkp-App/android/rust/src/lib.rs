use jni::JNIEnv;
use jni::objects::{JClass, JString};
use jni::sys::jstring;
use std::panic;

#[no_mangle]
pub extern "system" fn Java_com_example_zkpapp_MainActivity_stringFromRust(
    env: JNIEnv,
    _class: JClass,
) -> jstring {
    
    // 🛡️ SAFETY NET
    let result = panic::catch_unwind(|| {
        
        // 🧪 CRASH TEST DUMMY
        // Hum jaan boojh kar engine fail kar rahe hain
        panic!("Simulated Crash: Engine Overheat! 🔥💥");
        
        // Yeh line kabhi execute nahi hogi
        "This will never be reached".to_string()
    });

    // 🛡️ ERROR HANDLING
    let output_msg = match result {
        Ok(msg) => msg, // Agar sab sahi raha (Jo ke nahi hoga)
        Err(_) => String::from("❌ Error: Rust Engine Panicked! App Saved from Crash."), // 👈 Yeh return hona chahiye
    };

    let output_java_string = env.new_string(output_msg).expect("Couldn't create java string!");
    output_java_string.into_raw()
}

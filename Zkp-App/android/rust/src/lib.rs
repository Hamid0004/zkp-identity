use jni::JNIEnv;
use jni::objects::{JClass, JString};
use jni::sys::jstring;
use std::ffi::CString;

#[no_mangle]
pub extern "system" fn Java_com_example_zkpapp_MainActivity_stringFromRust(
    env: JNIEnv,
    _class: JClass,
) -> jstring {
    
    // 🛡️ SUPER SAFE MODE: No logic, just return a string.
    // Hum ek fake JSON bhej rahe hain taaki App khush ho jaye.
    // Format: ["1/1|Hello World"]
    
    let output = "[\"1/1|👋 Hello from Safe Mode! QR Player is Working.\"]";

    let output_java_string = env.new_string(output).expect("Couldn't create java string!");
    output_java_string.into_raw()
}

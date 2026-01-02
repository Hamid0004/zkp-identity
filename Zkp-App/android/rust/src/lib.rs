use jni::JNIEnv;
use jni::objects::{JClass, JString};
use jni::sys::jstring;

// 👇 Yeh naam bilkul sahi hona chahiye warna Crash hoga
#[no_mangle]
pub extern "system" fn Java_com_example_zkpapp_MainActivity_stringFromRust(
    env: JNIEnv,
    _class: JClass,
) -> jstring {
    // Rust ka message
    let output = "Hello from Rust Engine! 🦀⚡";

    // Isay Java String mein convert karo aur wapis bhejo
    let output_java_string = env.new_string(output).expect("Couldn't create java string!");
    
    // Return pointer
    output_java_string.into_raw()
}

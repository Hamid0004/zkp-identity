use jni::JNIEnv;
use jni::objects::{JClass, JString};
use jni::sys::jstring;
use plonky2_field::types::Field;
use plonky2_field::goldilocks_field::GoldilocksField;
use plonky2::hash::poseidon::PoseidonHash;
use plonky2::plonk::config::Hasher;

// 👇 Type alias taaki baar baar lamba naam na likhna pade
type F = GoldilocksField;

#[no_mangle]
pub extern "system" fn Java_com_example_zkpapp_MainActivity_stringFromRust(
    env: JNIEnv,
    _class: JClass,
) -> jstring {
    
    // 1. Inputs define karein (Hum '123' aur '456' ka hash nikalenge)
    let input_1 = F::from_canonical_u64(123);
    let input_2 = F::from_canonical_u64(456);

    // 2. Hashing shuru karein (Poseidon Hash - ZK friendly hash)
    // Yeh wahi hash hai jo Polygon zkEVM use karta hai!
    let hash_result = PoseidonHash::hash_no_pad(&[input_1, input_2]);

    // 3. Result ko string banayen
    let output_msg = format!(
        "🔥 Plonky2 Hash Generated!\nInput: [123, 456]\nHash: {}",
        hash_result.elements[0] // Hash ka pehla hissa dikhayenge
    );

    // 4. Java/Kotlin ko wapis bhejen
    let output_java_string = env.new_string(output_msg).expect("Couldn't create java string!");
    output_java_string.into_raw()
}

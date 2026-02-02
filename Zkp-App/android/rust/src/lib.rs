// 🦁 MAIN ENTRY POINT (MANAGER)

use android_logger::Config;
use log::LevelFilter;

// Logger Setup
fn init_logger() {
    let _ = android_logger::init_once(
        Config::default().with_max_level(LevelFilter::Debug).with_tag("RustZKP_Main"),
    );
}

// 2. MODULE DECLARATIONS
// Ab filenames small hain, toh code bhi small hoga.

// Phase 3 Logic (Balance Proof)
// File: offline_identity.rs
pub mod offline_identity;

// Phase 6 Logic (Passport NFC + RSA)
// File: passport_security.rs
pub mod passport_security;

// Phase 7 Logic (ZK Login)
// File: zk_auth.rs
pub mod zk_auth;
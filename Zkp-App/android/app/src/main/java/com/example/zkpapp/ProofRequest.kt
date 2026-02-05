package com.example.zkpapp.models

// Yeh wo "Lifafa" hai jo hum Server ko bhejenge
data class ProofRequest(
    val session_id: String, // QR Code se milegi
    val proof_data: String  // Rust se milega
)
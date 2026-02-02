package com.example.zkpapp

import android.util.Log

object ZkAuth {
    // Library Load karna (Agar pehle se loaded nahi hai)
    init {
        try {
            System.loadLibrary("zkp_mobile")
        } catch (e: UnsatisfiedLinkError) {
            Log.e("ZkAuth", "Error loading Rust library: ${e.message}")
        }
    }

    // 🦁 Rust Function Declaration
    // Yeh function Rust ke 'generateNullifier' ko call karega
    external fun generateNullifier(secret: String, domain: String): String
}
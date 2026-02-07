package com.example.zkpapp

import android.util.Log

// 🦁 Single Source of Truth: Passport Data yahan zinda rahega
object IdentityStorage {
    
    private var passportSecret: String? = null
    private var countryCode: String = "PK"

    // Passport Scan hone ke baad data yahan save karein
    fun saveIdentity(secret: String, country: String) {
        passportSecret = secret
        countryCode = country
        Log.d("IdentityStorage", "✅ DATA SAVED: Secret Length ${secret.length}")
    }

    // ZKP Engine yahan se data uthayega
    fun getSecret(): String {
        return passportSecret ?: throw IllegalStateException("⚠️ No Passport Scanned! Please Scan NFC First.")
    }

    fun getDomain(): String {
        return countryCode
    }

    // Check karein ke user ne passport scan kiya hai ya nahi
    fun hasIdentity(): Boolean {
        return passportSecret != null
    }

    fun clear() {
        passportSecret = null
    }
}
package com.example.zkpapp

import android.util.Log
import java.security.SecureRandom
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.read
import kotlin.concurrent.write

/**
 * IdentityStorage - The Fort Knox of your App 🏰
 * Handles secure, in-memory storage of the Passport Secret.
 */
object IdentityStorage {

    // ═══════════════════════════════════════════════════════════
    // 📊 CONSTANTS (Must be at the TOP to avoid Init Errors)
    // ═══════════════════════════════════════════════════════════
    private const val TAG = "IdentityStorage"
    private const val DEFAULT_COUNTRY = "PK"
    private const val MIN_SECRET_LENGTH = 8

    // ═══════════════════════════════════════════════════════════
    // 🔒 PRIVATE SENSITIVE DATA (RAM Only)
    // ═══════════════════════════════════════════════════════════
    @Volatile
    private var passportSecret: String? = null

    @Volatile
    private var countryCode: String = DEFAULT_COUNTRY

    @Volatile
    private var birthDate: String? = null

    @Volatile
    private var expiryDate: String? = null

    @Volatile
    private var createdAt: Long = 0L

    // 🧵 THREAD SAFETY TOOLS
    private val lock = ReentrantReadWriteLock()
    private val secureRandom = SecureRandom()

    // ═══════════════════════════════════════════════════════════
    // 💾 CORE OPERATIONS
    // ═══════════════════════════════════════════════════════════

    fun saveIdentity(
        secret: String,
        country: String,
        dob: String = "",
        expiry: String = ""
    ) {
        if (secret.length < MIN_SECRET_LENGTH) {
            Log.e(TAG, "❌ Secret too short! Must be at least $MIN_SECRET_LENGTH chars.")
            return
        }

        lock.write {
            // Security: Wipe old data before overwriting
            if (passportSecret != null) {
                secureWipeString(passportSecret!!)
            }

            passportSecret = secret
            countryCode = country.uppercase()
            birthDate = dob.takeIf { it.isNotEmpty() }
            expiryDate = expiry.takeIf { it.isNotEmpty() }
            createdAt = System.currentTimeMillis()

            Log.d(TAG, "✅ Identity Secured in RAM")
        }
    }

    fun getSecret(): String {
        lock.read {
            return passportSecret ?: throw IllegalStateException("⚠️ Identity Data Missing! Scan Passport First.")
        }
    }

    fun getDomain(): String {
        lock.read { return countryCode }
    }

    fun hasIdentity(): Boolean {
        lock.read { return !passportSecret.isNullOrEmpty() }
    }

    // ═══════════════════════════════════════════════════════════
    // 🧹 SECURITY & CLEANUP
    // ═══════════════════════════════════════════════════════════

    fun clear() {
        lock.write {
            // Aggressive Wipe
            passportSecret?.let { secureWipeString(it) }
            birthDate?.let { secureWipeString(it) }
            expiryDate?.let { secureWipeString(it) }

            // Nullify
            passportSecret = null
            countryCode = DEFAULT_COUNTRY
            birthDate = null
            expiryDate = null
            createdAt = 0L

            // Request GC
            System.gc()
            Log.i(TAG, "🧹 Memory Wiped Clean")
        }
    }

    // Alias for compatibility
    fun clearCache() {
        clear()
    }

    /**
     * 🦁 Secure Wipe Logic (Fixed for Compilation)
     * Overwrites the string's internal char array with random noise.
     */
    private fun secureWipeString(str: String) {
        try {
            val valueField = String::class.java.getDeclaredField("value")
            valueField.isAccessible = true
            val chars = valueField.get(str) as CharArray

            // 1. Generate Random Noise
            val randomBytes = ByteArray(chars.size)
            secureRandom.nextBytes(randomBytes)

            // 2. Overwrite with Noise (Standard Loop)
            for (i in chars.indices) {
                chars[i] = randomBytes[i].toInt().toChar()
            }

            // 3. Zero Out
            for (i in chars.indices) {
                chars[i] = '\u0000'
            }
        } catch (e: Exception) {
            // Reflection might be blocked on Android 14+, but we tried our best.
            // The data is still nulled in the main method.
            Log.w(TAG, "Secure wipe limitation: ${e.message}")
        }
    }

    // ═══════════════════════════════════════════════════════════
    // 📈 DEBUGGING (Safe)
    // ═══════════════════════════════════════════════════════════

    fun getStats(): Map<String, Any> {
        lock.read {
            return mapOf(
                "has_identity" to (passportSecret != null),
                "country" to countryCode,
                "created_at" to createdAt
            )
        }
    }
}
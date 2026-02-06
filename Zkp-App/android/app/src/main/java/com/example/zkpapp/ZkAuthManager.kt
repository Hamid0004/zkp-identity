package com.example.zkpapp.auth

import android.content.Context
import android.util.Log
import com.example.zkpapp.NetworkUtils
import com.example.zkpapp.ZkAuth // 🦁 Import ZkAuth (Wrapper)
import com.example.zkpapp.models.ProofRequest
import com.example.zkpapp.network.RelayApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Protocol
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.util.concurrent.TimeUnit

object ZkAuthManager {

    private const val BASE_URL = "https://crispy-dollop-97xj7vjgx4ph9pgg-3000.app.github.dev/"
    
    @Volatile
    private var isRunning = false

    // 🦁 API Client (Internally Managed)
    private val api: RelayApi by lazy {
        val client = OkHttpClient.Builder()
            .protocols(listOf(Protocol.HTTP_1_1)) // Stream Reset Fix
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .build()

        Retrofit.Builder()
            .baseUrl(BASE_URL)
            .client(client)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(RelayApi::class.java)
    }

    // 🦁 The Main Function
    suspend fun startUniversalLogin(
        context: Context,
        sessionId: String,
        onStatus: (String) -> Unit,
        onSuccess: () -> Unit,
        onError: (String) -> Unit
    ) {
        if (isRunning) return
        isRunning = true

        try {
            // 1. 🌐 Internet Check
            if (!NetworkUtils.isInternetAvailable(context)) {
                onError("❌ No Internet Connection")
                return
            }

            // 2. 🦁 Generate Proof (CPU Heavy -> Default Dispatcher)
            onStatus("🦁 Generating ZK Proof...")
            val proof = withContext(Dispatchers.Default) {
                // Hardcoded secret for Phase 7 (Real app mein Keystore se aayega)
                ZkAuth.generateSecureNullifier(
                    secret = "123456",
                    domain = "zk_login",
                    // 👇 FIXED: Parameter name changed from 'sessionId' to 'challenge'
                    challenge = sessionId 
                )
            }

            if (proof.startsWith("🔥") || proof.startsWith("Error")) {
                throw Exception("Proof generation failed")
            }

            // 3. ☁️ Upload to Server (IO Dispatcher)
            onStatus("☁️ Verifying with Server...")
            val response = withContext(Dispatchers.IO) {
                api.uploadProof(
                    ProofRequest(session_id = sessionId, proof_data = proof)
                )
            }

            // 4. ✅ Result Handling
            if (response.isSuccessful) {
                onSuccess()
            } else {
                onError(mapServerError(response.code()))
            }

        } catch (e: Exception) {
            Log.e("ZkAuthManager", "Login Error", e)
            onError("⚠️ Error: ${e.message}")
        } finally {
            isRunning = false
        }
    }

    private fun mapServerError(code: Int): String {
        return when (code) {
            401 -> "❌ Server Private (Check Port)"
            404 -> "❌ Session Expired"
            502 -> "❌ Invalid / Fake QR"
            else -> "❌ Server Error ($code)"
        }
    }
}
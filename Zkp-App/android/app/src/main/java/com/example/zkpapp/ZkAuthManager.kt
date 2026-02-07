package com.example.zkpapp.auth

import android.content.Context
import android.util.Log
import com.example.zkpapp.IdentityStorage
import com.example.zkpapp.NetworkUtils
import com.example.zkpapp.ZkAuth
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

    private const val BASE_URL =
        "https://crispy-dollop-97xj7vjgx4ph9pgg-3000.app.github.dev/"

    @Volatile
    private var running = false

    private val api: RelayApi by lazy {
        val client = OkHttpClient.Builder()
            .protocols(listOf(Protocol.HTTP_1_1))
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

    suspend fun startUniversalLogin(
        context: Context,
        sessionId: String,
        onStatus: (String) -> Unit,
        onSuccess: () -> Unit,
        onError: (String) -> Unit,
    ) {
        if (running) return
        running = true

        try {
            // 1. Internet Check
            if (!NetworkUtils.isInternetAvailable(context)) {
                onError("❌ No Internet")
                return
            }

            onStatus("🦁 Fetching Passport Identity...")

            // ⏱️ DAY 84: START BENCHMARK TIMER
            val startTime = System.currentTimeMillis()
            var proofDuration = 0L

            // 2. Generate Proof with REAL Data
            val proof = withContext(Dispatchers.Default) {

                // Check Storage First
                if (!IdentityStorage.hasIdentity()) {
                    throw Exception("⚠️ No Passport Data! Please Scan NFC First.")
                }

                val realSecret = IdentityStorage.getSecret()
                val realDomain = IdentityStorage.getDomain()

                // Use Real Data for Zero Knowledge Proof
                val result = ZkAuth.safeGenerateNullifier(
                    secret = realSecret,
                    domain = realDomain,
                    challenge = sessionId
                )
                
                // Calculate Time immediately after proof generation
                val endTime = System.currentTimeMillis()
                proofDuration = endTime - startTime
                
                return@withContext result
            }

            if (proof.startsWith("Error") || proof.startsWith("🔥")) {
                onError(proof)
                return
            }

            // 🦁 DISPLAY SPEED TO USER
            onStatus("⚡ Proof Generated in ${proofDuration}ms\n☁️ Verifying with Server...")

            // 3. Upload to Server
            val response = withContext(Dispatchers.IO) {
                api.uploadProof(ProofRequest(sessionId, proof))
            }

            if (response.isSuccessful) onSuccess()
            else onError(mapError(response.code()))

        } catch (e: Exception) {
            Log.e("ZkAuthManager", "Login failed", e)
            onError("⚠️ ${e.message}")
        } finally {
            running = false
        }
    }

    private fun mapError(code: Int) = when (code) {
        401 -> "❌ Server Private"
        404 -> "❌ Session Expired"
        502 -> "❌ Invalid QR"
        else -> "❌ Server Error ($code)"
    }
}
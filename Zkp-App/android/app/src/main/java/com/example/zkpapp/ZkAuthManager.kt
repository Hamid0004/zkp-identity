package com.example.zkpapp.auth

import android.content.Context
import android.util.Log
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
            if (!NetworkUtils.isInternetAvailable(context)) {
                onError("❌ No Internet")
                return
            }

            onStatus("🦁 Generating ZK Proof…")

            val proof = withContext(Dispatchers.Default) {
                ZkAuth.generateSecureNullifier(
                    secret = "123456",
                    domain = "zk_login",
                    challenge = sessionId
                )
            }

            if (proof.startsWith("Error")) {
                onError(proof)
                return
            }

            onStatus("☁️ Verifying with Server…")

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
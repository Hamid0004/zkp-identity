package com.example.zkpapp.network

import com.example.zkpapp.models.ProofRequest
import okhttp3.ResponseBody
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.POST

interface RelayApi {
    // Server ka wo darwaza jahan Proof jama hota hai
    @POST("api/upload-proof") 
    suspend fun uploadProof(@Body request: ProofRequest): Response<ResponseBody>
}
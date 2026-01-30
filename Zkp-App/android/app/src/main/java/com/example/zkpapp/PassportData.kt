package com.example.zkpapp

import android.graphics.Bitmap
import android.os.Parcelable
import kotlinx.parcelize.Parcelize

// Yeh class Data aur Photo ko hold karegi
@Parcelize
data class PassportData(
    val firstName: String,
    val lastName: String,
    val gender: String,
    val documentNumber: String,
    val dateOfBirth: String,
    val expiryDate: String,
    val facePhoto: Bitmap? // 🖼️ Photo yahan aayegi
) : Parcelable
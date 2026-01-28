package com.example.zkpapp

import android.util.Log
import org.jmrtd.BACKey
import org.jmrtd.BACKeySpec

object MrzUtil {

    // 🧠 Raw Text se BAC Key nikalne ka logic
    fun extractBacKey(rawMrz: String?): BACKeySpec? {
        if (rawMrz.isNullOrEmpty()) return null

        try {
            // Lines ko alag karo
            val lines = rawMrz.uppercase().lines().filter { it.length > 30 }
            if (lines.size < 2) return null

            // TD3 Standard (Passport) hamesha 2nd line mein hota hai data
            val line2 = lines[lines.size - 1].replace(" ", "")

            // 🔍 Parsing Logic (TD3 Standard positions)
            // Document Number: Characters 0 to 9
            val docNumber = line2.substring(0, 9).replace("<", "")
            
            // Date of Birth: Characters 13 to 19 (YYMMDD)
            val dob = line2.substring(13, 19)
            
            // Expiry Date: Characters 21 to 27 (YYMMDD)
            val expiry = line2.substring(21, 27)

            Log.d("MrzUtil", "Extracted Keys -> Doc: $docNumber, DOB: $dob, Exp: $expiry")

            // JMRTD Key generate karo
            return BACKey(docNumber, dob, expiry)

        } catch (e: Exception) {
            Log.e("MrzUtil", "Failed to parse MRZ", e)
            return null
        }
    }
}
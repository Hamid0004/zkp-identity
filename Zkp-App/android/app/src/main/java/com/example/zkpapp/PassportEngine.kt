package com.example.zkpapp

import android.graphics.*
import android.nfc.tech.IsoDep
import android.util.Log
import kotlinx.coroutines.delay
import net.sf.scuba.smartcards.CardService
import org.jmrtd.BACKeySpec
import org.jmrtd.PassportService
import org.jmrtd.lds.icao.DG1File
import org.jmrtd.lds.icao.DG2File
import org.jmrtd.lds.iso19794.FaceImageInfo
import org.spongycastle.jce.provider.BouncyCastleProvider
import java.io.ByteArrayInputStream // ✅ Needed for stream conversion
import java.security.Security

enum class PassportMode { REAL, SIMULATION }

sealed class PassportState {
    object IDLE : PassportState()
    object CONNECTING : PassportState()
    object ANALYZING_MRZ : PassportState()
    object BAC_AUTH : PassportState()
    object READING : PassportState()
    object DONE : PassportState()
    data class ERROR(val reason: String) : PassportState()
}

class PassportEngine(
    private val mode: PassportMode,
    private val isoDep: IsoDep?,
    private val mrz: String?
) {

    companion object {
        private const val TAG = "PassportEngine"

        init {
            Security.removeProvider("BC")
            Security.insertProviderAt(BouncyCastleProvider(), 1)
        }
    }

    var state: PassportState = PassportState.IDLE
        private set

    suspend fun start(): PassportData {
        state = PassportState.CONNECTING

        return try {
            when (mode) {
                PassportMode.REAL -> connectRealChip()
                PassportMode.SIMULATION -> simulateChip()
            }
        } catch (e: Exception) {
            state = PassportState.ERROR(e.message ?: "Unknown error")
            Log.e(TAG, "❌ ENGINE FAILURE", e)
            throw e
        }
    }

    // =====================================================
    // 🛂 REAL PASSPORT NFC FLOW (Day 69 Updated)
    // =====================================================
    private suspend fun connectRealChip(): PassportData {
        requireNotNull(isoDep) { "IsoDep missing" }
        require(!mrz.isNullOrBlank()) { "MRZ missing" }

        state = PassportState.ANALYZING_MRZ
        val bacKey: BACKeySpec = MrzUtil.extractBacKey(mrz!!)
            ?: throw Exception("MRZ parsing failed")

        isoDep.timeout = 8000
        if (!isoDep.isConnected) isoDep.connect()

        val cardService = CardService.getInstance(isoDep)
        val service = PassportService(
            cardService,
            PassportService.NORMAL_MAX_TRANCEIVE_LENGTH,
            PassportService.DEFAULT_MAX_BLOCKSIZE,
            false,
            false
        )

        try {
            cardService.open()
            service.open()

            state = PassportState.BAC_AUTH
            service.sendSelectApplet(false)

            try {
                service.doBAC(bacKey)
                Log.d(TAG, "✅ BAC success")
            } catch (e: Exception) {
                throw Exception("BAC authentication failed — wrong MRZ or weak NFC signal")
            }

            state = PassportState.READING

            // ---------- DG1 TEXT & BYTES (Day 69) ----------
            // ZKP ke liye humein 'Raw Bytes' chahiye.
            // Isliye hum stream ko direct parse karne ke bajaye pehle bytes mein padhenge.
            
            val dg1Stream = service.getInputStream(PassportService.EF_DG1)
            val dg1RawBytes = dg1Stream.readBytes() // 🔐 RAW DATA CAPTURED HERE

            // Ab in bytes se JMRTD file banayenge parsing ke liye
            val dg1 = DG1File(ByteArrayInputStream(dg1RawBytes))
            val info = dg1.mrzInfo

            val firstName = info.secondaryIdentifier.replace("<", " ").trim()
            val lastName = info.primaryIdentifier.replace("<", " ").trim()

            // ---------- DG2 PHOTO ----------
            var faceBitmap: Bitmap? = null
            try {
                val dg2 = DG2File(service.getInputStream(PassportService.EF_DG2))
                val faceInfos = dg2.faceInfos

                if (faceInfos.isNotEmpty()) {
                    val faceInfo = faceInfos[0] as FaceImageInfo
                    val dataInputStream = faceInfo.imageInputStream
                    faceBitmap = BitmapFactory.decodeStream(dataInputStream)
                }
            } catch (e: Exception) {
                Log.w(TAG, "⚠️ Photo read failed: ${e.message}")
            }

            state = PassportState.DONE

            // 📦 Return Data + RAW BYTES
            return PassportData(
                firstName = firstName,
                lastName = lastName,
                gender = info.gender.toString(),
                documentNumber = info.documentNumber,
                dateOfBirth = info.dateOfBirth,
                expiryDate = info.dateOfExpiry,
                facePhoto = faceBitmap,
                dg1Raw = dg1RawBytes // ✅ Passing Real Raw Bytes
            )

        } finally {
            try { service.close() } catch (_: Exception) {}
            try { cardService.close() } catch (_: Exception) {}
            try { if (isoDep.isConnected) isoDep.close() } catch (_: Exception) {}
        }
    }

    // =====================================================
    // 🧪 SIMULATION MODE (Day 69 Updated)
    // =====================================================
    private suspend fun simulateChip(): PassportData {
        state = PassportState.ANALYZING_MRZ
        delay(400)
        state = PassportState.BAC_AUTH
        delay(600)
        state = PassportState.READING
        delay(800)

        // 🖼️ Fake Photo
        val width = 300
        val height = 400
        val bmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        canvas.drawColor(Color.LTGRAY)
        val paint = Paint().apply {
            color = Color.BLUE
            textSize = 60f
            textAlign = Paint.Align.CENTER
            isAntiAlias = true
        }
        canvas.drawText("SIM USER", width / 2f, height / 2f, paint)

        // 🔐 Fake Raw Bytes (Mocking DG1 Data)
        // Real DG1 starts with tag 0x61, length, then tag 0x5F1F...
        // Hum bas random bytes bhej rahe hain test ke liye.
        val fakeDg1Bytes = byteArrayOf(
            0x61.toByte(), 0x10.toByte(), // Tag + Length
            0x5F.toByte(), 0x1F.toByte(), 0x05.toByte(), // Mock Content
            0x48.toByte(), 0x45.toByte(), 0x4C.toByte(), 0x4C.toByte(), 0x4F.toByte() // HELLO
        )

        state = PassportState.DONE

        return PassportData(
            firstName = "TEST",
            lastName = "USER",
            gender = "M",
            documentNumber = "PK1234567",
            dateOfBirth = "950101",
            expiryDate = "300101",
            facePhoto = bmp,
            dg1Raw = fakeDg1Bytes // ✅ Passing Fake Raw Bytes
        )
    }
}
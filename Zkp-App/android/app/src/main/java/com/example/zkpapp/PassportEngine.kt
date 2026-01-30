package com.example.zkpapp

import android.nfc.tech.IsoDep
import android.util.Log
import kotlinx.coroutines.delay
import net.sf.scuba.smartcards.CardService
import org.jmrtd.BACKeySpec
import org.jmrtd.PassportService
import org.jmrtd.lds.icao.DG1File
import java.io.InputStream
import java.security.Security
import org.spongycastle.jce.provider.BouncyCastleProvider

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
            Security.insertProviderAt(BouncyCastleProvider(), 1)
        }
    }

    var state: PassportState = PassportState.IDLE
        private set

    suspend fun start(): ByteArray {
        state = PassportState.CONNECTING

        return try {
            when (mode) {
                PassportMode.REAL -> connectRealChip()
                PassportMode.SIMULATION -> simulateChip()
            }
        } catch (e: Exception) {
            state = PassportState.ERROR(e.message ?: "Unknown error")
            Log.e(TAG, "ENGINE ERROR", e)
            throw e
        }
    }

    // =====================================================
    // 🛂 REAL NFC PASSPORT FLOW
    // =====================================================
    private suspend fun connectRealChip(): ByteArray {
        requireNotNull(isoDep) { "IsoDep required for REAL mode" }
        requireNotNull(mrz) { "MRZ Data required for BAC keys" }

        state = PassportState.ANALYZING_MRZ
        val bacKey: BACKeySpec = MrzUtil.extractBacKey(mrz)
            ?: throw Exception("Invalid MRZ → Cannot derive BAC key")

        isoDep.timeout = 10_000

        val cardService = CardService.getInstance(isoDep)
        val service = PassportService(
            cardService,
            PassportService.NORMAL_MAX_TRANCEIVE_LENGTH,
            PassportService.DEFAULT_MAX_BLOCKSIZE,
            false,
            false
        )

        try {
            // 📡 Open connection layers
            cardService.open()
            service.open()

            // 🔐 BAC Authentication
            state = PassportState.BAC_AUTH
            service.sendSelectApplet(false)
            service.doBAC(bacKey)
            Log.d(TAG, "✅ BAC Authentication Success")

            // 📖 Read DG1 (MRZ info stored on chip)
            state = PassportState.READING
            val dg1Stream: InputStream = service.getInputStream(PassportService.EF_DG1)
            val dg1 = DG1File(dg1Stream)

            val name = dg1.mrzInfo.secondaryIdentifier.replace("<", " ").trim()
            val passportNum = dg1.mrzInfo.documentNumber

            state = PassportState.DONE

            return "SUCCESS|$name|$passportNum".toByteArray(Charsets.UTF_8)

        } catch (e: Exception) {
            Log.e(TAG, "❌ NFC/BAC Failure", e)
            throw Exception("Access Denied: Keep passport steady on phone.")
        } finally {
            try { service.close() } catch (_: Exception) {}
            try { cardService.close() } catch (_: Exception) {}
            try { isoDep.close() } catch (_: Exception) {}
        }
    }

    // =====================================================
    // 🧪 SIMULATION MODE
    // =====================================================
    private suspend fun simulateChip(): ByteArray {
        Log.d(TAG, "Starting Simulation Flow")

        state = PassportState.ANALYZING_MRZ
        delay(500)

        state = PassportState.BAC_AUTH
        delay(700)

        state = PassportState.READING
        delay(900)

        state = PassportState.DONE

        return "SIMULATION|TEST USER|PK1234567".toByteArray(Charsets.UTF_8)
    }
}

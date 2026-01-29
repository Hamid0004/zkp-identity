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

// ---------------------------
// ENUMS & STATES
// ---------------------------
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

// ---------------------------
// PASSPORT ENGINE (THE BRAIN)
// ---------------------------
class PassportEngine(
    private val mode: PassportMode,
    private val isoDep: IsoDep?,
    private val mrz: String?
) {

    companion object {
        private const val TAG = "PassportEngine"
        
        // ✅ IMPORTANT: Crypto Provider Load karna zaroori hai
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

    // ---------------------------
    // 📲 REAL NFC FLOW (Asli Passport ke liye)
    // ---------------------------
    private suspend fun connectRealChip(): ByteArray {
        requireNotNull(isoDep) { "IsoDep required for REAL mode" }
        requireNotNull(mrz) { "MRZ Data required for keys" }

        // 1. MRZ se Key banao
        state = PassportState.ANALYZING_MRZ
        // Note: MrzUtil file humne pehle banayi thi, yeh wahan se key le raha hai
        val bacKey: BACKeySpec = MrzUtil.extractBacKey(mrz) 
            ?: throw Exception("Invalid MRZ Data. Cannot create key.")
        
        // 2. Chip se Connect Karo
        isoDep.timeout = 10000 // 10 seconds timeout
        val cardService = CardService.getInstance(isoDep)
        cardService.open()

        // 3. Passport Service Start
        val service = PassportService(cardService, 256, 224)
        service.open()

        var paceSucceeded = false
        try {
            // 4. Unlock the Chip (BAC)
            state = PassportState.BAC_AUTH
            service.sendSelectApplet(paceSucceeded)
            service.doBAC(bacKey)
            Log.d(TAG, "✅ BAC Authentication Success!")
        } catch (e: Exception) {
            Log.w(TAG, "BAC Failed", e)
            throw Exception("Access Denied: Please hold passport still.")
        }

        // 5. Data Read (DG1 File - Name/DocNum)
        state = PassportState.READING
        
        var inputStream: InputStream = service.getInputStream(PassportService.EF_DG1)
        val dg1 = DG1File(inputStream)
        
        val name = dg1.mrzInfo.secondaryIdentifier.replace("<", " ").trim()
        val passportNum = dg1.mrzInfo.documentNumber
        
        state = PassportState.DONE
        
        // ✅ Return Valid Format
        return "SUCCESS|$name|$passportNum".toByteArray()
    }

    // ---------------------------
    // 🧪 SIMULATION FLOW (Jugaad for Testing)
    // ---------------------------
    private suspend fun simulateChip(): ByteArray {
        Log.d(TAG, "Starting Simulation...")
        
        // Fake Delays to mimic real process
        state = PassportState.ANALYZING_MRZ
        delay(600)

        state = PassportState.BAC_AUTH
        delay(800)

        state = PassportState.READING
        delay(1000)

        state = PassportState.DONE
        
        // ✅ THE FIX: Yahan ab hum SAHI FORMAT bhej rahe hain
        // Pehle yahan '0xAA' bytes they jo error de rahe they.
        val fakeData = "SIMULATION|TEST USER|PK1234567"
        
        return fakeData.toByteArray()
    }
}
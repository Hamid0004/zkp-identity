package com.example.zkpapp

import android.app.Application
import android.content.Intent
import android.os.Process
import android.util.Log
import android.widget.Toast
import kotlin.system.exitProcess

class MyApplication : Application() {

    override fun onCreate() {
        super.onCreate()

        // 🦁 GLOBAL CRASH HANDLER
        // Yeh line har anjane crash ko pakad legi
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            handleUncaughtException(thread, throwable)
        }
    }

    private fun handleUncaughtException(thread: Thread, e: Throwable) {
        // 1. Log the Error (Developer ke liye)
        e.printStackTrace()
        Log.e("LionCrash", "🚨 CRASH DETECTED: ${e.message}")

        // 2. User ko dikhao ke kyun phata (Toast + Restart)
        // Note: Crash ke waqt UI thread mar chuka hota hai, isliye hum naya process start karte hain
        
        val intent = Intent(this, MainActivity::class.java).apply {
            // Error Message pass karein taaki user dekh sake
            putExtra("CRASH_REPORT", "💥 Error: ${e.message}\n\nLocation: ${e.stackTrace.firstOrNull()}")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        }

        // 3. App Restart karein
        startActivity(intent)

        // 4. Purana Process Kill karein
        Process.killProcess(Process.myPid())
        exitProcess(1)
    }
}
package com.example.zkpapp

import android.app.Application
import android.content.Intent
import android.os.Process
import android.util.Log
import java.io.PrintWriter
import java.io.StringWriter

class ZkpApplication : Application() {

    override fun onCreate() {
        super.onCreate()

        // 🦁 GLOBAL CRASH HANDLER
        // Yeh code puri app par nazar rakhega
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            handleUncaughtException(throwable)
        }
    }

    private fun handleUncaughtException(e: Throwable) {
        val sw = StringWriter()
        e.printStackTrace(PrintWriter(sw))
        val stackTrace = sw.toString()

        Log.e("ZkpCrash", stackTrace)

        // Error Activity ko start karo
        val intent = Intent(applicationContext, ErrorActivity::class.java)
        intent.putExtra("error_log", stackTrace)
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        startActivity(intent)

        Process.killProcess(Process.myPid())
        System.exit(1)
    }
}
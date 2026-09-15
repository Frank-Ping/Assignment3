package com.example.wear.presentation

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class DebugDemoReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.hasExtra("fault")) {
            val target = intent.getStringExtra("sensor")
            val name = intent.getStringExtra("fault")
            val allowed = setOf("CLEAR", "PERMISSION_REQUIRED", "WAITING_FOR_DATA", "UNAVAILABLE", "DATA_ERROR", "STOPPED")
            if (DemoControl.configure == null || target !in setOf("HR", "ACCEL") || name !in allowed) {
                resultCode = 1
                resultData = "Open watch sensor page; sensor=HR|ACCEL; fault=${allowed.joinToString()}"
                return
            }
            val status = if (name == "CLEAR") null else com.example.wear.presentation.data.SensorStatus.valueOf(name!!)
            if (target == "HR") DemoControl.heartRateFault = status else DemoControl.accelerationFault = status
            resultData = "DEMO fault $target=$name (affects fake sources only)"
            return
        }
        resultData = DemoControl.configureScenario(intent.getStringExtra("scenario") ?: "NORMAL")
    }
}

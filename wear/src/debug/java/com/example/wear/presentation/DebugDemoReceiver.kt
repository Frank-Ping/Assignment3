package com.example.wear.presentation

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class DebugDemoReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        resultData = DemoControl.configure?.invoke(intent.getStringExtra("scenario") ?: "NORMAL")
            ?: "Open watch SensorActivity first"
    }
}

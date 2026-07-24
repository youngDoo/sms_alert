package com.lightweight.smsalert.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

class BootAndPowerReceiver : BroadcastReceiver() {
    companion object {
        private const val TAG = "BootAndPowerReceiver"
    }

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        Log.d(TAG, "BootAndPowerReceiver triggered with action: $action")
    }
}

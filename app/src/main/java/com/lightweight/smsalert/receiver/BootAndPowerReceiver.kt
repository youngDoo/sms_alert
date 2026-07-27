package com.lightweight.smsalert.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.lightweight.smsalert.service.SmsBackupJobService

class BootAndPowerReceiver : BroadcastReceiver() {
    companion object {
        private const val TAG = "BootAndPowerReceiver"
    }

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        Log.d(TAG, "System event: $action")
        try {
            SmsReceiver.registerDynamic(context.applicationContext)
            SmsBackupJobService.schedule(context.applicationContext, immediate = false)
            Log.d(TAG, "Receivers re-registered after system event")
        } catch (e: Exception) {
            Log.e(TAG, "Error re-registering after system event: ${e.message}")
        }
    }
}

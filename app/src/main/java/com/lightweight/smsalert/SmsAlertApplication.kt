package com.lightweight.smsalert

import android.app.Application
import android.util.Log
import com.lightweight.smsalert.receiver.SmsReceiver
import com.lightweight.smsalert.service.SmsBackupJobService

class SmsAlertApplication : Application() {

    companion object {
        private const val TAG = "SmsAlertApplication"
    }

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "SmsAlertApplication onCreate triggered.")

        try {
            SmsReceiver.registerDynamic(this)
            SmsBackupJobService.schedule(this, immediate = true)
        } catch (e: Exception) {
            Log.e(TAG, "Error initializing on application start: ${e.message}")
        }
    }
}

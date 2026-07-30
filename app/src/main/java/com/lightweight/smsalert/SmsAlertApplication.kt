package com.lightweight.smsalert

import android.app.Application
import android.util.Log
import com.google.android.material.color.DynamicColors
import com.lightweight.smsalert.receiver.SmsReceiver
import com.lightweight.smsalert.service.SmsBackupJobService

class SmsAlertApplication : Application() {

    companion object {
        private const val TAG = "SmsAlertApplication"
    }

    override fun onCreate() {
        super.onCreate()

        // Android 12+ Material You 动态取色（自动回退到主题色）
        DynamicColors.applyToActivitiesIfAvailable(this)

        Log.i(TAG, "App initialized, re-registering receivers")
        try {
            SmsReceiver.registerDynamic(this)
            SmsBackupJobService.schedule(this, immediate = true)
        } catch (e: Exception) {
            Log.e(TAG, "Error initializing on application start: ${e.message}")
        }
    }
}

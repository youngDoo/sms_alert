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
        Log.d(TAG, "BootAndPowerReceiver triggered with action: $action")

        try {
            // 重新注册动态短信广播
            SmsReceiver.registerDynamic(context.applicationContext)
            // 重新调度 JobScheduler 兜底任务（非立即，按自适应间隔）
            SmsBackupJobService.schedule(context.applicationContext, immediate = false)
            Log.d(TAG, "Dynamic receiver and JobScheduler re-registered after system event.")
        } catch (e: Exception) {
            Log.e(TAG, "Error re-registering after boot/power event: ${e.message}")
        }
    }
}

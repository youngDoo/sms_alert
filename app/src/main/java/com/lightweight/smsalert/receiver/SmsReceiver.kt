package com.lightweight.smsalert.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.PowerManager
import android.provider.Telephony
import android.util.Log
import androidx.core.content.ContextCompat
import com.lightweight.smsalert.data.PrefsManager
import com.lightweight.smsalert.service.RingtoneService
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import androidx.core.app.NotificationCompat
import com.lightweight.smsalert.ui.MainActivity

class SmsReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "SmsReceiver"
        private const val WAKE_LOCK_TIMEOUT_MS = 10000L

        fun registerDynamic(context: Context) {
            val prefs = PrefsManager(context)
            if (!prefs.isListenerEnabled) return
            if (!prefs.isBroadcastEnabled) return
            RingtoneService.startMonitoring(context)
            syncStaticReceiverState(context, true)
        }

        fun unregisterDynamic(context: Context) {
            RingtoneService.stopMonitoring(context)
            syncStaticReceiverState(context, false)
        }

        @JvmStatic
        fun processIncomingSms(context: Context, smsId: String, sender: String, body: String, timestamp: Long) {
            val prefs = PrefsManager(context)

            if (prefs.isDuplicateSms(smsId, sender, timestamp)) {
                Log.d(TAG, "Duplicate SMS ignored: sender=$sender")
                return
            }

            val contact = prefs.findMatchingContact(sender)
            if (contact != null) {
                Log.i(TAG, "Contact matched: name=${contact.name} phone=${contact.phoneNumber}")
                RingtoneService.startRinging(context, contact.name, contact.phoneNumber, body, contact.ringtoneUri, contact.repeatIntervalSec)
            } else {
                val contentRule = prefs.findMatchingContentRule(body)
                if (contentRule != null) {
                    Log.i(TAG, "Content rule matched: name=${contentRule.name}")
                    RingtoneService.startRinging(context, contentRule.name, sender, body, contentRule.ringtoneUri, contentRule.repeatIntervalSec)
                }
            }
        }

        private fun syncStaticReceiverState(context: Context, enabled: Boolean) {
            val pm = context.packageManager
            val componentName = android.content.ComponentName(context, SmsReceiver::class.java)

            val shouldEnable = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU && enabled

            val newState = if (shouldEnable) {
                PackageManager.COMPONENT_ENABLED_STATE_ENABLED
            } else {
                PackageManager.COMPONENT_ENABLED_STATE_DISABLED
            }

            try {
                pm.setComponentEnabledSetting(componentName, newState, PackageManager.DONT_KILL_APP)
            } catch (e: Exception) {
                Log.e(TAG, "Error setting static receiver state: ${e.message}")
            }
        }
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != "android.provider.Telephony.SMS_RECEIVED") return

        if (!checkBcastPermissions(context)) {
            Log.w(TAG, "SMS permissions missing, showing permission notification")
            sendBcastPermissionNotification(context)
            return
        }

        val powerManager = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
        val wakeLock: PowerManager.WakeLock? = try {
            powerManager?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "SmsAlertTool:SmsWakeLock")
                ?.also { it.acquire(WAKE_LOCK_TIMEOUT_MS) }
        } catch (e: SecurityException) {
            Log.e(TAG, "WakeLock permission missing: ${e.message}")
            null
        }

        try {
            val messages = Telephony.Sms.Intents.getMessagesFromIntent(intent)
            if (messages.isNullOrEmpty()) return

            val firstMsg = messages[0]
            val sender = firstMsg.originatingAddress ?: return

            val bodyBuilder = StringBuilder()
            for (msg in messages) bodyBuilder.append(msg.messageBody)

            // 广播路径无法获取 _id，使用 sender+timestamp 作为去重标识
            val broadcastSmsId = "${sender}_${firstMsg.timestampMillis}"
            Companion.processIncomingSms(context,
                broadcastSmsId, sender,
                bodyBuilder.toString(), firstMsg.timestampMillis)

        } catch (e: Exception) {
            Log.e(TAG, "Error parsing SMS: ${e.message}")
        } finally {
            try {
                if (wakeLock != null && wakeLock.isHeld) wakeLock.release()
            } catch (e: Exception) {
                Log.e(TAG, "Error releasing WakeLock: ${e.message}")
            }
        }
    }

    private fun checkBcastPermissions(context: Context): Boolean {
        return ContextCompat.checkSelfPermission(context, android.Manifest.permission.RECEIVE_SMS) == PackageManager.PERMISSION_GRANTED
                && ContextCompat.checkSelfPermission(context, android.Manifest.permission.READ_SMS) == PackageManager.PERMISSION_GRANTED
    }

    private fun sendBcastPermissionNotification(context: Context) {
        val channelId = "sms_alert_permission_channel"
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            nm.createNotificationChannel(NotificationChannel(channelId, "权限异常通知", NotificationManager.IMPORTANCE_HIGH))
        }
        val pi = PendingIntent.getActivity(context, 0,
            Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        nm.notify(999, NotificationCompat.Builder(context, channelId)
            .setContentTitle("短信钉权限失效")
            .setContentText("检测到短信权限已失效，请点击去授权")
            .setSmallIcon(android.R.drawable.stat_notify_error)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(pi).setAutoCancel(true).build())
    }
}

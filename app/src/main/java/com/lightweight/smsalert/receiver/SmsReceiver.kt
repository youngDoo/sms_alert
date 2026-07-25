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
import com.lightweight.smsalert.model.SpecialContact
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
        private var dynamicReceiver: SmsReceiver? = null

        fun registerDynamic(context: Context) {
            val prefs = PrefsManager(context)
            if (!prefs.isListenerEnabled) return
            if (!prefs.isBroadcastEnabled) return

            if (dynamicReceiver == null) {
                dynamicReceiver = SmsReceiver()
                val filter = android.content.IntentFilter().apply {
                    addAction("android.provider.Telephony.SMS_RECEIVED")
                    priority = 80
                }
                context.applicationContext.registerReceiver(dynamicReceiver, filter)
                Log.d(TAG, "Dynamic SMS Receiver registered successfully with priority 80.")
            }

            syncStaticReceiverState(context, true)
        }

        fun unregisterDynamic(context: Context) {
            dynamicReceiver?.let {
                try {
                    context.applicationContext.unregisterReceiver(it)
                    Log.d(TAG, "Dynamic SMS Receiver unregistered.")
                } catch (e: Exception) {
                    Log.e(TAG, "Unregister dynamic receiver error: ${e.message}")
                }
                dynamicReceiver = null
            }
            syncStaticReceiverState(context, false)
        }

        private fun syncStaticReceiverState(context: Context, enabled: Boolean) {
            val pm = context.packageManager
            val componentName = android.content.ComponentName(context, SmsReceiver::class.java)
            
            val shouldEnable = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                false
            } else {
                enabled
            }

            val newState = if (shouldEnable) {
                PackageManager.COMPONENT_ENABLED_STATE_ENABLED
            } else {
                PackageManager.COMPONENT_ENABLED_STATE_DISABLED
            }

            try {
                pm.setComponentEnabledSetting(
                    componentName,
                    newState,
                    PackageManager.DONT_KILL_APP
                )
                Log.d(TAG, "Static SMS Receiver state set to: $newState")
            } catch (e: Exception) {
                Log.e(TAG, "Error setting static receiver state: ${e.message}")
            }
        }
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != "android.provider.Telephony.SMS_RECEIVED") return
        Log.d(TAG, "SMS Received broadcast triggered.")

        if (!checkPermissions(context)) {
            sendPermissionNotification(context)
            return
        }

        val powerManager = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
        var wakeLock: PowerManager.WakeLock? = null
        try {
            wakeLock = powerManager?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "SmsAlertTool:SmsWakeLock")
            wakeLock?.acquire(WAKE_LOCK_TIMEOUT_MS)
            Log.d(TAG, "WakeLock acquired for 10s.")
        } catch (e: SecurityException) {
            Log.e(TAG, "WakeLock permission missing or restricted: ${e.message}")
        }

        try {
            val messages = Telephony.Sms.Intents.getMessagesFromIntent(intent)
            if (messages.isNullOrEmpty()) return

            val firstMsg = messages[0]
            val sender = firstMsg.originatingAddress ?: return
            
            val bodyBuilder = StringBuilder()
            for (msg in messages) {
                bodyBuilder.append(msg.messageBody)
            }
            val body = bodyBuilder.toString()
            val timestamp = firstMsg.timestampMillis
            val smsId = firstMsg.indexOnIcc.toString()

            processIncomingSms(context, smsId, sender, body, timestamp)

        } catch (e: Exception) {
            Log.e(TAG, "Error parsing SMS: ${e.message}")
        } finally {
            try {
                android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                    if (wakeLock != null && wakeLock.isHeld) {
                        wakeLock.release()
                        Log.d(TAG, "WakeLock released after processing.")
                    }
                }, 1000)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    fun processIncomingSms(context: Context, smsId: String, sender: String, body: String, timestamp: Long) {
        val prefs = PrefsManager(context)

        if (prefs.isDuplicateSms(smsId, sender, timestamp)) {
            Log.d(TAG, "Duplicate SMS ignored: Sender=$sender, TS=$timestamp")
            return
        }

        val contact = prefs.findMatchingContact(sender)
        if (contact != null) {
            Log.d(TAG, "Matched Special Contact! Name=${contact.name}, Phone=${contact.phoneNumber}")
            RingtoneService.startRinging(context, contact.name, contact.phoneNumber, body, contact.ringtoneUri, contact.repeatIntervalSec)
        } else {
            Log.d(TAG, "SMS sender not in special contacts list: $sender")
        }
    }

    private fun checkPermissions(context: Context): Boolean {
        val hasReceive = ContextCompat.checkSelfPermission(context, android.Manifest.permission.RECEIVE_SMS) == PackageManager.PERMISSION_GRANTED
        val hasRead = ContextCompat.checkSelfPermission(context, android.Manifest.permission.READ_SMS) == PackageManager.PERMISSION_GRANTED
        return hasReceive && hasRead
    }

    private fun sendPermissionNotification(context: Context) {
        val channelId = "sms_alert_permission_channel"
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(channelId, "权限异常通知", NotificationManager.IMPORTANCE_HIGH)
            nm.createNotificationChannel(channel)
        }

        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }
        val pendingIntent = PendingIntent.getActivity(
            context, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(context, channelId)
            .setContentTitle("特别短信提醒权限失效")
            .setContentText("检测到短信读取与接收权限已失效，请点击去授权，避免漏提醒。")
            .setSmallIcon(android.R.drawable.stat_notify_error)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .build()

        nm.notify(999, notification)
    }
}

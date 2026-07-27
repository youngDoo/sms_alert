package com.lightweight.smsalert.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
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
            Log.w(TAG, "[DIAG] registerDynamic: isListenerEnabled=${prefs.isListenerEnabled}, isBroadcastEnabled=${prefs.isBroadcastEnabled}, SDK=${Build.VERSION.SDK_INT}")
            if (!prefs.isListenerEnabled) {
                Log.w(TAG, "[DIAG] registerDynamic BLOCKED: isListenerEnabled=false")
                return
            }
            if (!prefs.isBroadcastEnabled) {
                Log.w(TAG, "[DIAG] registerDynamic BLOCKED: isBroadcastEnabled=false")
                return
            }

            // 启动前台常驻 RingtoneService（ContentObserver 在 Service 内注册，进程不会被杀）
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

            val contacts = prefs.getContacts()
            Log.w(TAG, "[DIAG] processIncomingSms: sender=$sender, contactsCount=${contacts.size}, smsId=$smsId, ts=$timestamp")

            if (prefs.isDuplicateSms(smsId, sender, timestamp)) {
                Log.w(TAG, "[DIAG] processIncomingSms BLOCKED: duplicate SMS, sender=$sender")
                return
            }

            val contact = prefs.findMatchingContact(sender)
            if (contact != null) {
                Log.w(TAG, "[DIAG] processIncomingSms MATCHED contact: name=${contact.name}, phone=${contact.phoneNumber}")
                RingtoneService.startRinging(context, contact.name, contact.phoneNumber, body, contact.ringtoneUri, contact.repeatIntervalSec)
            } else {
                // 号码未匹配 → 尝试内容正则
                val contentRule = prefs.findMatchingContentRule(body)
                if (contentRule != null) {
                    Log.w(TAG, "[DIAG] processIncomingSms MATCHED contentRule: name=${contentRule.name}, pattern=${contentRule.pattern}")
                    RingtoneService.startRinging(context, contentRule.name, sender, body, contentRule.ringtoneUri, contentRule.repeatIntervalSec)
                } else {
                    Log.w(TAG, "[DIAG] processIncomingSms NO MATCH: sender=$sender")
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
                Log.d(TAG, "Static SMS Receiver state set to: $newState")
            } catch (e: Exception) {
                Log.e(TAG, "Error setting static receiver state: ${e.message}")
            }
        }
    }

    // ─── BroadcastReceiver 路径：Android 12 及以下兜底 ──────────────

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != "android.provider.Telephony.SMS_RECEIVED") return
        Log.w(TAG, "[DIAG] === SMS_RECEIVED broadcast FIRED (SDK=${Build.VERSION.SDK_INT}) ===")

        if (!checkBcastPermissions(context)) {
            Log.w(TAG, "[DIAG] onReceive BLOCKED: SMS permissions missing")
            sendBcastPermissionNotification(context)
            return
        }

        val powerManager = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
        var wakeLock: PowerManager.WakeLock? = null
        try {
            wakeLock = powerManager?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "SmsAlertTool:SmsWakeLock")
            wakeLock?.acquire(WAKE_LOCK_TIMEOUT_MS)
        } catch (e: SecurityException) {
            Log.e(TAG, "WakeLock permission missing: ${e.message}")
        }

        try {
            val messages = Telephony.Sms.Intents.getMessagesFromIntent(intent)
            if (messages.isNullOrEmpty()) return

            val firstMsg = messages[0]
            val sender = firstMsg.originatingAddress ?: return

            val bodyBuilder = StringBuilder()
            for (msg in messages) bodyBuilder.append(msg.messageBody)

            Companion.processIncomingSms(context,
                firstMsg.indexOnIcc.toString(), sender,
                bodyBuilder.toString(), firstMsg.timestampMillis)

        } catch (e: Exception) {
            Log.e(TAG, "Error parsing SMS: ${e.message}")
        } finally {
            try {
                Handler(Looper.getMainLooper()).postDelayed({
                    if (wakeLock != null && wakeLock!!.isHeld) wakeLock!!.release()
                }, 1000)
            } catch (e: Exception) { e.printStackTrace() }
        }
    }

    private fun checkBcastPermissions(context: Context): Boolean {
        val hasReceive = ContextCompat.checkSelfPermission(
            context, android.Manifest.permission.RECEIVE_SMS
        ) == PackageManager.PERMISSION_GRANTED
        val hasRead = ContextCompat.checkSelfPermission(
            context, android.Manifest.permission.READ_SMS
        ) == PackageManager.PERMISSION_GRANTED
        Log.w(TAG, "[DIAG] checkBcastPermissions: RECEIVE_SMS=$hasReceive, READ_SMS=$hasRead")
        return hasReceive && hasRead
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

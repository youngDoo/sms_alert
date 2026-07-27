package com.lightweight.smsalert.service

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.database.ContentObserver
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.provider.Telephony
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.lightweight.smsalert.receiver.SmsReceiver
import com.lightweight.smsalert.ui.AlertActivity
import com.lightweight.smsalert.ui.MainActivity

class RingtoneService : Service(), AudioManager.OnAudioFocusChangeListener {

    companion object {
        private const val TAG = "RingtoneService"
        private const val ACTION_START = "com.lightweight.smsalert.START_RINGING"
        private const val ACTION_STOP = "com.lightweight.smsalert.STOP_RINGING"
        private const val ACTION_POLL = "com.lightweight.smsalert.POLL"
        private const val EXTRA_SENDER_NAME = "sender_name"
        private const val EXTRA_SENDER_PHONE = "sender_phone"
        private const val EXTRA_SMS_BODY = "sms_body"
        private const val EXTRA_RINGTONE_URI = "ringtone_uri"
        private const val EXTRA_INTERVAL_SEC = "interval_sec"
        private const val NOTIFICATION_ID_MONITOR = 1001
        private const val NOTIFICATION_ID_RINGING = 1002
        private const val CHANNEL_ID_MONITOR = "sms_alert_monitoring"
        private const val CHANNEL_ID_RINGING = "sms_alert_ringing"
        private const val POLL_INTERVAL_MS = 5_000L
        private const val ALARM_REQUEST_CODE = 2001
        private var isRinging = false
        private var lastProcessedId: Long = 0L

        fun startMonitoring(context: Context) {
            Log.w(TAG, "[DIAG] startMonitoring")
            val intent = Intent(context, RingtoneService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) context.startForegroundService(intent)
            else context.startService(intent)
        }

        fun stopMonitoring(context: Context) {
            Log.w(TAG, "[DIAG] stopMonitoring")
            context.stopService(Intent(context, RingtoneService::class.java))
        }

        fun startRinging(context: Context, name: String, phone: String, body: String,
                         ringtoneUri: String, intervalSec: Int) {
            Log.w(TAG, "[DIAG] startRinging: name=$name, phone=$phone, ringtoneUri=$ringtoneUri")
            val intent = Intent(context, RingtoneService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_SENDER_NAME, name)
                putExtra(EXTRA_SENDER_PHONE, phone)
                putExtra(EXTRA_SMS_BODY, body)
                putExtra(EXTRA_RINGTONE_URI, ringtoneUri)
                putExtra(EXTRA_INTERVAL_SEC, intervalSec)
            }
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) context.startForegroundService(intent)
                else context.startService(intent)
            } catch (e: Exception) {
                Log.e(TAG, "[DIAG] startRinging FAILED: ${e.message}", e)
            }
        }

        fun stopRinging(context: Context) {
            context.startService(Intent(context, RingtoneService::class.java).apply { action = ACTION_STOP })
        }

        fun isRinging() = isRinging
    }

    private var mediaPlayer: MediaPlayer? = null
    private var vibrator: Vibrator? = null
    private var audioManager: AudioManager? = null
    private var focusRequest: AudioFocusRequest? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var smsObserver: ContentObserver? = null

    override fun onCreate() {
        super.onCreate()
        Log.w(TAG, "[DIAG] RingtoneService onCreate")
        audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
            (getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager)?.defaultVibrator
        else @Suppress("DEPRECATION") getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        try {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "SmsAlertTool:RingingWakeLock")
            wakeLock?.acquire(10 * 60 * 1000L)
        } catch (_: SecurityException) {}
        startForegroundMonitor()
        registerSmsContentObserver()
        scheduleNextPoll()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action
        if (action != ACTION_POLL) Log.w(TAG, "[DIAG] onStartCommand: action=$action")
        when (action) {
            ACTION_STOP -> { Log.w(TAG, "[DIAG] STOP audio"); stopAudio() }
            ACTION_START -> {
                val name = intent.getStringExtra(EXTRA_SENDER_NAME) ?: "未知"
                val phone = intent.getStringExtra(EXTRA_SENDER_PHONE) ?: ""
                val body = intent.getStringExtra(EXTRA_SMS_BODY) ?: ""
                val uri = intent.getStringExtra(EXTRA_RINGTONE_URI) ?: "default"
                Log.w(TAG, "[DIAG] RING: name=$name, phone=$phone, uri=$uri")
                isRinging = true
                showRingingNotification(name, phone)
                requestAlarmAudioFocus()
                playAlertRingtone(uri)
                startVibration()
                launchAlertActivity(name, phone, body)
            }
            ACTION_POLL -> { pollForNewSms(); scheduleNextPoll() }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.w(TAG, "[DIAG] onDestroy")
        cancelPollAlarm(); stopAudio()
        try { smsObserver?.let { contentResolver.unregisterContentObserver(it) } } catch (_: Exception) {}
        smsObserver = null
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) focusRequest?.let { audioManager?.abandonAudioFocusRequest(it) }
            else @Suppress("DEPRECATION") audioManager?.abandonAudioFocus(this)
        } catch (_: Exception) {}
        try { if (wakeLock != null && wakeLock!!.isHeld) wakeLock!!.release() } catch (_: Exception) {}
        isRinging = false
    }

    override fun onBind(intent: Intent?): IBinder? = null
    override fun onAudioFocusChange(fc: Int) {}

    private fun startForegroundMonitor() {
        createMonitorChannel()
        val n = buildMonitorNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
            startForeground(NOTIFICATION_ID_MONITOR, n, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        else startForeground(NOTIFICATION_ID_MONITOR, n)
    }

    private fun createMonitorChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
            .createNotificationChannel(NotificationChannel(CHANNEL_ID_MONITOR, "短信监听服务", NotificationManager.IMPORTANCE_LOW)
                .apply { description = "短信钉正在后台监听短信"; setShowBadge(false) })
    }

    private fun buildMonitorNotification(): Notification {
        val pi = PendingIntent.getActivity(this, 0,
            Intent(this, MainActivity::class.java).apply { flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(this, CHANNEL_ID_MONITOR)
            .setContentTitle("短信钉运行中").setContentText("正在监听特别关注联系人的短信")
            .setSmallIcon(android.R.drawable.ic_dialog_email).setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW).setContentIntent(pi).build()
    }

    private fun showRingingNotification(name: String, phone: String) {
        createRingingChannel()
        val stopPi = PendingIntent.getService(this, 3001,
            Intent(this, RingtoneService::class.java).apply { action = ACTION_STOP },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val alertPi = PendingIntent.getActivity(this, 3002,
            Intent(this, AlertActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                putExtra("sender_name", name); putExtra("sender_phone", phone); putExtra("sms_body", "")
            }, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val n = NotificationCompat.Builder(this, CHANNEL_ID_RINGING)
            .setContentTitle("短信钉 — $name 来信").setContentText(phone)
            .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
            .setPriority(NotificationCompat.PRIORITY_HIGH).setCategory(NotificationCompat.CATEGORY_ALARM)
            .setOngoing(true).setAutoCancel(false)
            .setFullScreenIntent(alertPi, true).addAction(0, "停止提醒", stopPi).build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
            startForeground(NOTIFICATION_ID_RINGING, n, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        else startForeground(NOTIFICATION_ID_RINGING, n)
        Log.w(TAG, "[DIAG] Ringing notification shown")
    }

    private fun createRingingChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
            .createNotificationChannel(NotificationChannel(CHANNEL_ID_RINGING, "短信提醒", NotificationManager.IMPORTANCE_HIGH)
                .apply { description = "特别关注短信到达时的高优先级提醒" })
    }

    private fun cancelRingingNotification() {
        stopForeground(STOP_FOREGROUND_REMOVE)
        startForegroundMonitor()
    }

    private fun registerSmsContentObserver() {
        if (smsObserver != null) return
        smsObserver = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean, uri: Uri?) {
                super.onChange(selfChange, uri)
                Log.w(TAG, "[DIAG] ContentObserver.onChange")
                if (hasReadSmsPermission()) queryAndProcessLatestSms("CO")
            }
        }
        contentResolver.registerContentObserver(Telephony.Sms.Inbox.CONTENT_URI, true, smsObserver!!)
    }

    private fun scheduleNextPoll() {
        val am = getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val pi = PendingIntent.getService(this, ALARM_REQUEST_CODE,
            Intent(this, RingtoneService::class.java).apply { action = ACTION_POLL },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val t = SystemClock.elapsedRealtime() + POLL_INTERVAL_MS
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !am.canScheduleExactAlarms()) {
            am.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, t, pi)
            Log.w(TAG, "[DIAG] Alarm: inexact (SCHEDULE_EXACT_ALARM denied)")
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            am.setExactAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, t, pi)
        } else {
            am.setExact(AlarmManager.ELAPSED_REALTIME_WAKEUP, t, pi)
        }
    }

    private fun cancelPollAlarm() {
        val am = getSystemService(Context.ALARM_SERVICE) as AlarmManager
        am.cancel(PendingIntent.getService(this, ALARM_REQUEST_CODE,
            Intent(this, RingtoneService::class.java).apply { action = ACTION_POLL },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
    }

    private fun pollForNewSms() {
        if (!hasReadSmsPermission()) return
        queryAndProcessLatestSms("Poll")
    }

    private fun queryAndProcessLatestSms(src: String) {
        Thread {
            try {
                contentResolver.query(Telephony.Sms.Inbox.CONTENT_URI,
                    arrayOf("_id", "address", "body", "date"),
                    "_id > ?", arrayOf(lastProcessedId.toString()), "date DESC LIMIT 1")?.use {
                    if (it.moveToFirst()) {
                        val id = it.getLong(it.getColumnIndexOrThrow("_id"))
                        val addr = it.getString(it.getColumnIndexOrThrow("address"))
                        val body = it.getString(it.getColumnIndexOrThrow("body")) ?: ""
                        val date = it.getLong(it.getColumnIndexOrThrow("date"))
                        if (addr.isNullOrEmpty()) return@use
                        Log.w(TAG, "[DIAG] $src newSMS: id=$id, sender=$addr")
                        lastProcessedId = id
                        SmsReceiver.processIncomingSms(this@RingtoneService, id.toString(), addr, body, date)
                    }
                }
            } catch (e: SecurityException) { Log.e(TAG, "[DIAG] $src denied: ${e.message}")
            } catch (e: Exception) { Log.e(TAG, "[DIAG] $src err: ${e.javaClass.simpleName}", e) }
        }.start()
    }

    private fun hasReadSmsPermission() =
        ContextCompat.checkSelfPermission(this, android.Manifest.permission.READ_SMS) == PackageManager.PERMISSION_GRANTED

    private fun stopAudio() {
        try { mediaPlayer?.stop(); mediaPlayer?.release(); mediaPlayer = null } catch (_: Exception) {}
        try { vibrator?.cancel() } catch (_: Exception) {}
        isRinging = false
        cancelRingingNotification()
        Log.w(TAG, "[DIAG] stopAudio")
    }

    private fun requestAlarmAudioFocus() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            focusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                .setAudioAttributes(AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ALARM).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
                .setAcceptsDelayedFocusGain(true).setOnAudioFocusChangeListener(this).build()
            focusRequest?.let { Log.w(TAG, "[DIAG] AudioFocus: ${audioManager?.requestAudioFocus(it)}") }
        } else @Suppress("DEPRECATION")
            Log.w(TAG, "[DIAG] AudioFocus: ${audioManager?.requestAudioFocus(this, AudioManager.STREAM_ALARM, AudioManager.AUDIOFOCUS_GAIN)}")
    }

    private fun resolveRingtoneUri(s: String): Uri {
        val uri = when (s) {
            "alarm" -> RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
            "ringtone" -> RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
            "notification" -> RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
            "default", "" -> RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
                ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
            else -> Uri.parse(s)
        }
        Log.w(TAG, "[DIAG] ringtoneUri: '$s' → $uri"); return uri
    }

    private fun playAlertRingtone(uriStr: String) {
        try {
            mediaPlayer?.release(); mediaPlayer = MediaPlayer()
            mediaPlayer?.apply {
                setDataSource(this@RingtoneService, resolveRingtoneUri(uriStr))
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP)
                    setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
                else @Suppress("DEPRECATION") setAudioStreamType(AudioManager.STREAM_ALARM)
                isLooping = true; prepare(); start()
            }
            Log.w(TAG, "[DIAG] playRingtone: OK, playing=${mediaPlayer?.isPlaying}")
        } catch (e: Exception) { Log.e(TAG, "[DIAG] playRingtone FAILED: ${e.javaClass.simpleName}: ${e.message}", e) }
    }

    private fun startVibration() {
        try {
            val p = longArrayOf(0, 800, 800)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) vibrator?.vibrate(VibrationEffect.createWaveform(p, 0))
            else @Suppress("DEPRECATION") vibrator?.vibrate(p, 0)
        } catch (e: Exception) { Log.e(TAG, "[DIAG] vibrate FAILED: ${e.message}", e) }
    }

    private fun launchAlertActivity(name: String, phone: String, body: String) {
        startActivity(Intent(this, AlertActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra("sender_name", name); putExtra("sender_phone", phone); putExtra("sms_body", body)
        })
    }
}

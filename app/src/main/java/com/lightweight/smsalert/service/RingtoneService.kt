package com.lightweight.smsalert.service

import android.app.Service
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log
import com.lightweight.smsalert.ui.AlertActivity

class RingtoneService : Service(), AudioManager.OnAudioFocusChangeListener {

    companion object {
        private const val TAG = "RingtoneService"
        
        private const val ACTION_START = "com.lightweight.smsalert.START_RINGING"
        private const val ACTION_STOP = "com.lightweight.smsalert.STOP_RINGING"

        private const val EXTRA_SENDER_NAME = "sender_name"
        private const val EXTRA_SENDER_PHONE = "sender_phone"
        private const val EXTRA_SMS_BODY = "sms_body"
        private const val EXTRA_RINGTONE_URI = "ringtone_uri"
        private const val EXTRA_INTERVAL_SEC = "interval_sec"

        private var isServiceRunning = false

        fun startRinging(
            context: Context,
            name: String,
            phone: String,
            body: String,
            ringtoneUri: String,
            intervalSec: Int
        ) {
            val intent = Intent(context, RingtoneService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_SENDER_NAME, name)
                putExtra(EXTRA_SENDER_PHONE, phone)
                putExtra(EXTRA_SMS_BODY, body)
                putExtra(EXTRA_RINGTONE_URI, ringtoneUri)
                putExtra(EXTRA_INTERVAL_SEC, intervalSec)
            }
            context.startService(intent)
        }

        fun stopRinging(context: Context) {
            val intent = Intent(context, RingtoneService::class.java).apply {
                action = ACTION_STOP
            }
            context.startService(intent)
        }

        fun isRinging(): Boolean {
            return isServiceRunning
        }
    }

    private var mediaPlayer: MediaPlayer? = null
    private var vibrator: Vibrator? = null
    private var audioManager: AudioManager? = null
    private var focusRequest: AudioFocusRequest? = null
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onCreate() {
        super.onCreate()
        audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        
        vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val vibratorManager = getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
            vibratorManager?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        }

        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "SmsAlertTool:RingingWakeLock")
        wakeLock?.acquire(10 * 60 * 1000L)
        Log.d(TAG, "RingtoneService created and WakeLock acquired.")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action
        Log.d(TAG, "RingtoneService onStartCommand with action: $action")

        if (action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }

        if (action == ACTION_START) {
            val name = intent.getStringExtra(EXTRA_SENDER_NAME) ?: "未知"
            val phone = intent.getStringExtra(EXTRA_SENDER_PHONE) ?: ""
            val body = intent.getStringExtra(EXTRA_SMS_BODY) ?: ""
            val ringtoneUri = intent.getStringExtra(EXTRA_RINGTONE_URI) ?: "default"
            val intervalSec = intent.getIntExtra(EXTRA_INTERVAL_SEC, 30)

            isServiceRunning = true

            requestAlarmAudioFocus()
            playAlertRingtone(ringtoneUri)
            startVibration()
            launchAlertActivity(name, phone, body)
        }

        return START_STICKY
    }

    private fun requestAlarmAudioFocus() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val playbackAttributes = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ALARM)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build()

            focusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
                .setAudioAttributes(playbackAttributes)
                .setAcceptsDelayedFocusGain(true)
                .setOnAudioFocusChangeListener(this)
                .build()

            focusRequest?.let {
                val res = audioManager?.requestAudioFocus(it)
                Log.d(TAG, "O+ AudioFocus request result: $res")
            }
        } else {
            @Suppress("DEPRECATION")
            val res = audioManager?.requestAudioFocus(
                this,
                AudioManager.STREAM_ALARM,
                AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK
            )
            Log.d(TAG, "Legacy AudioFocus request result: $res")
        }
    }

    private fun playAlertRingtone(ringtoneUriStr: String) {
        try {
            mediaPlayer?.release()
            mediaPlayer = MediaPlayer()

            val uri = if (ringtoneUriStr == "default" || ringtoneUriStr.isEmpty()) {
                RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
                    ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
            } else {
                Uri.parse(ringtoneUriStr)
            }

            mediaPlayer?.apply {
                setDataSource(this@RingtoneService, uri)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                    setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_ALARM)
                            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                            .build()
                    )
                } else {
                    @Suppress("DEPRECATION")
                    setAudioStreamType(AudioManager.STREAM_ALARM)
                }
                isLooping = true
                prepare()
                start()
            }
            Log.d(TAG, "Playing ringtone: $uri")
        } catch (e: Exception) {
            Log.e(TAG, "Error playing ringtone: ${e.message}")
        }
    }

    private fun startVibration() {
        try {
            val pattern = longArrayOf(0, 800, 800)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrator?.vibrate(VibrationEffect.createWaveform(pattern, 0))
            } else {
                @Suppress("DEPRECATION")
                vibrator?.vibrate(pattern, 0)
            }
            Log.d(TAG, "Vibration started.")
        } catch (e: Exception) {
            Log.e(TAG, "Error starting vibration: ${e.message}")
        }
    }

    private fun launchAlertActivity(name: String, phone: String, body: String) {
        val intent = Intent(this, AlertActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra("sender_name", name)
            putExtra("sender_phone", phone)
            putExtra("sms_body", body)
        }
        startActivity(intent)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onAudioFocusChange(focusChange: Int) {
        Log.d(TAG, "AudioFocus changed: $focusChange")
    }

    override fun onDestroy() {
        super.onDestroy()
        isServiceRunning = false

        try {
            mediaPlayer?.stop()
            mediaPlayer?.release()
            mediaPlayer = null
            Log.d(TAG, "MediaPlayer released.")
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping media player: ${e.message}")
        }

        try {
            vibrator?.cancel()
            Log.d(TAG, "Vibration stopped.")
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping vibration: ${e.message}")
        }

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                focusRequest?.let { audioManager?.abandonAudioFocusRequest(it) }
            } else {
                @Suppress("DEPRECATION")
                audioManager?.abandonAudioFocus(this)
            }
            Log.d(TAG, "AudioFocus abandoned.")
        } catch (e: Exception) {
            Log.e(TAG, "Error releasing audio focus: ${e.message}")
        }

        try {
            if (wakeLock != null && wakeLock!!.isHeld) {
                wakeLock!!.release()
                Log.d(TAG, "WakeLock released.")
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }

        Log.d(TAG, "RingtoneService destroyed.")
    }
}

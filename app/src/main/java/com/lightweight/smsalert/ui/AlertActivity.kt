package com.lightweight.smsalert.ui

import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.appcompat.app.AppCompatActivity
import com.lightweight.smsalert.databinding.ActivityAlertFullscreenBinding
import com.lightweight.smsalert.service.RingtoneService

class AlertActivity : AppCompatActivity() {

    private lateinit var binding: ActivityAlertFullscreenBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setupLockScreenBypass()

        binding = ActivityAlertFullscreenBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setFinishOnTouchOutside(false)
        updateSenderInfo(intent)

        binding.btnDismiss.setOnClickListener {
            RingtoneService.stopRinging(this)
            finish()
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        // 只在有新数据时更新，防止被 MainActivity 恢复逻辑的空 Intent 覆盖
        if (intent.hasExtra("sender_name")) {
            updateSenderInfo(intent)
        }
    }

    private fun updateSenderInfo(intent: Intent) {
        val senderName = intent.getStringExtra("sender_name") ?: "未知"
        val senderPhone = intent.getStringExtra("sender_phone") ?: ""
        val smsBody = intent.getStringExtra("sms_body") ?: ""

        binding.tvAlertSender.text = if (senderPhone.isNotEmpty()) {
            "$senderName ($senderPhone)"
        } else {
            senderName
        }
        binding.tvAlertContent.text = smsBody
    }

    private fun setupLockScreenBypass() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON or
                WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD or
                WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
            )
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        // Block back button
    }
}

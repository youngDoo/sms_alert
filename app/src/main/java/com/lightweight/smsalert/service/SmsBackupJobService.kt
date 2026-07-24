package com.lightweight.smsalert.service

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.database.Cursor
import android.net.Uri
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager
import android.util.Log
import com.lightweight.smsalert.data.PrefsManager
import com.lightweight.smsalert.receiver.SmsReceiver

class SmsBackupJobService : JobService() {

    companion object {
        private const val TAG = "SmsBackupJobService"
        private const val JOB_ID = 2001

        fun schedule(context: Context, immediate: Boolean = false) {
            val prefs = PrefsManager(context)
            if (!prefs.isListenerEnabled) {
                cancel(context)
                return
            }

            val jobScheduler = context.getSystemService(Context.JOB_SCHEDULER_SERVICE) as JobScheduler
            
            val delayMs = if (immediate) {
                0L
            } else {
                getIntervalMs(context)
            }

            val component = ComponentName(context, SmsBackupJobService::class.java)
            val builder = JobInfo.Builder(JOB_ID, component)
                .setMinimumLatency(delayMs)
                .setOverrideDeadline(delayMs + 10000)
                .setRequiresDeviceIdle(false)
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_NONE)

            try {
                jobScheduler.schedule(builder.build())
                Log.d(TAG, "SmsBackupJobService scheduled with delay: ${delayMs / 1000}s")
            } catch (e: Exception) {
                Log.e(TAG, "Error scheduling job: ${e.message}")
            }
        }

        fun cancel(context: Context) {
            val jobScheduler = context.getSystemService(Context.JOB_SCHEDULER_SERVICE) as JobScheduler
            jobScheduler.cancel(JOB_ID)
            Log.d(TAG, "SmsBackupJobService cancelled.")
        }

        private fun getIntervalMs(context: Context): Long {
            val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
            val isScreenOn = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT_WATCH) {
                powerManager.isInteractive
            } else {
                @Suppress("DEPRECATION")
                powerManager.isScreenOn
            }

            val intent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            val status = intent?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
            val isCharging = status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL

            return if (isScreenOn || isCharging) {
                1 * 60000L
            } else {
                3 * 60000L
            }
        }
    }

    override fun onStartJob(params: JobParameters?): Boolean {
        Log.d(TAG, "Backup Job started running.")
        
        Thread {
            try {
                scanNewSms(this)
            } catch (e: Exception) {
                Log.e(TAG, "Error scanning SMS database: ${e.message}")
            } finally {
                schedule(this)
                jobFinished(params, false)
            }
        }.start()

        return true
    }

    override fun onStopJob(params: JobParameters?): Boolean {
        Log.d(TAG, "Backup Job stopped prematurely.")
        return true
    }

    private fun scanNewSms(context: Context) {
        val uri = Uri.parse("content://sms/inbox")
        val projection = arrayOf("_id", "address", "body", "date")
        
        val threeMinutesAgo = System.currentTimeMillis() - (3 * 60 * 1000L)
        val selection = "date >= ?"
        val selectionArgs = arrayOf(threeMinutesAgo.toString())

        var cursor: Cursor? = null
        val startTime = System.currentTimeMillis()
        try {
            cursor = context.contentResolver.query(
                uri,
                projection,
                selection,
                selectionArgs,
                "date DESC"
            )

            if (cursor != null && cursor.moveToFirst()) {
                val idIndex = cursor.getColumnIndexOrThrow("_id")
                val addressIndex = cursor.getColumnIndexOrThrow("address")
                val bodyIndex = cursor.getColumnIndexOrThrow("body")
                val dateIndex = cursor.getColumnIndexOrThrow("date")

                val smsReceiver = SmsReceiver()
                do {
                    val smsId = cursor.getString(idIndex)
                    val sender = cursor.getString(addressIndex)
                    val body = cursor.getString(bodyIndex)
                    val date = cursor.getLong(dateIndex)

                    Log.d(TAG, "Job scanned SMS: ID=$smsId, Sender=$sender, Date=$date")
                    smsReceiver.processIncomingSms(context, smsId, sender, body, date)

                    if (System.currentTimeMillis() - startTime > 100) {
                        Log.w(TAG, "Job scan execution exceeded 100ms, breaking.")
                        break
                    }
                } while (cursor.moveToNext())
            }
        } catch (e: Exception) {
            Log.e(TAG, "Cursor query error: ${e.message}")
        } finally {
            cursor?.close()
        }
    }
}

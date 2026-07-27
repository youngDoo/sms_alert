package com.lightweight.smsalert.ui

import android.Manifest
import android.annotation.SuppressLint
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.database.Cursor
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.ContactsContract
import android.provider.Settings
import android.util.Log
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import com.lightweight.smsalert.R
import com.lightweight.smsalert.data.PrefsManager
import com.lightweight.smsalert.databinding.ActivityMainBinding
import com.lightweight.smsalert.databinding.DialogContactEditBinding
import com.lightweight.smsalert.model.SpecialContact
import com.lightweight.smsalert.receiver.SmsReceiver
import com.lightweight.smsalert.service.SmsBackupJobService
import java.util.UUID

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var prefsManager: PrefsManager

    private val contactPickerLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            val contactUri = result.data?.data ?: return@registerForActivityResult
            parseSelectedContact(contactUri)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Edge-to-Edge：内容延伸到系统栏区域（挖孔屏/刘海屏适配）
        setSupportActionBar(binding.toolbar)

        prefsManager = PrefsManager(this)

        setupRecyclerView()
        setupUI()
        checkAndRequestFirstLaunchPermissions()
    }

    override fun onResume() {
        super.onResume()
        updatePermissionStatus()
        refreshContactList()
    }

    private fun setupRecyclerView() {
        binding.rvContacts.layoutManager = LinearLayoutManager(this)
        contactAdapter = ContactAdapter(emptyList()) { contact ->
            showDeleteConfirmation(contact)
        }
        binding.rvContacts.adapter = contactAdapter
    }

    private lateinit var contactAdapter: ContactAdapter

    private fun setupUI() {
        // 主开关
        binding.switchListener.isChecked = prefsManager.isListenerEnabled
        binding.switchBroadcast.isChecked = prefsManager.isBroadcastEnabled
        binding.switchScan.isChecked = prefsManager.isScanEnabled
        binding.layoutSubSwitches.visibility = if (prefsManager.isListenerEnabled) View.VISIBLE else View.GONE

        binding.switchListener.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked) {
                if (hasSmsPermissions()) {
                    prefsManager.isListenerEnabled = true
                    binding.layoutSubSwitches.visibility = View.VISIBLE
                    applySubSwitchStates()
                    Toast.makeText(this, "短信监听服务已启用", Toast.LENGTH_SHORT).show()
                } else {
                    binding.switchListener.isChecked = false
                    Toast.makeText(this, "请先授予短信读取权限", Toast.LENGTH_LONG).show()
                    jumpToSettings()
                }
            } else {
                prefsManager.isListenerEnabled = false
                binding.layoutSubSwitches.visibility = View.GONE
                SmsReceiver.unregisterDynamic(this)
                SmsBackupJobService.cancel(this)
                Toast.makeText(this, "短信监听服务已关闭", Toast.LENGTH_SHORT).show()
            }
        }

        // 子开关：即时广播
        binding.switchBroadcast.setOnCheckedChangeListener { _, isChecked ->
            prefsManager.isBroadcastEnabled = isChecked
            if (prefsManager.isListenerEnabled) {
                if (isChecked) SmsReceiver.registerDynamic(this)
                else SmsReceiver.unregisterDynamic(this)
            }
        }

        // 子开关：定时扫描
        binding.switchScan.setOnCheckedChangeListener { _, isChecked ->
            prefsManager.isScanEnabled = isChecked
            if (prefsManager.isListenerEnabled) {
                if (isChecked) SmsBackupJobService.schedule(this, immediate = true)
                else SmsBackupJobService.cancel(this)
            }
        }

        // 手动添加按钮
        binding.btnAddContact.setOnClickListener {
            showAddEditContactDialog(null, null)
        }

        // 通讯录导入 FAB
        binding.btnImportContact.setOnClickListener {
            try {
                val intent = Intent(Intent.ACTION_PICK, ContactsContract.CommonDataKinds.Phone.CONTENT_URI)
                contactPickerLauncher.launch(intent)
            } catch (e: Exception) {
                Toast.makeText(this, "无法打开通讯录，请检查权限", Toast.LENGTH_SHORT).show()
            }
        }

        // 权限卡片点击 → 去设置
        binding.cardPermissions.setOnClickListener {
            if (!hasSmsPermissions() || !hasNotificationPermission()) {
                jumpToSettings()
            }
        }

        // 电池优化行点击 → 跳转后台高耗电设置
        binding.layoutBatteryOpt.setOnClickListener {
            if (!isIgnoringBatteryOptimizations()) {
                jumpToBatterySettings()
            }
        }
    }

    private fun applySubSwitchStates() {
        if (prefsManager.isBroadcastEnabled) SmsReceiver.registerDynamic(this)
        else SmsReceiver.unregisterDynamic(this)
        if (prefsManager.isScanEnabled) SmsBackupJobService.schedule(this, immediate = true)
        else SmsBackupJobService.cancel(this)
    }

    private fun checkAndRequestFirstLaunchPermissions() {
        val sp = getSharedPreferences("sms_alert_app", MODE_PRIVATE)
        val isFirstLaunch = sp.getBoolean("is_first_launch", true)
        if (isFirstLaunch) {
            sp.edit().putBoolean("is_first_launch", false).apply()
            if (!hasSmsPermissions() || !hasNotificationPermission()) {
                Toast.makeText(this, "首次启动，请先开启短信和通知权限", Toast.LENGTH_LONG).show()
                jumpToSettings()
            }
        }
    }

    private fun hasSmsPermissions(): Boolean {
        return ContextCompat.checkSelfPermission(this, Manifest.permission.RECEIVE_SMS) == PackageManager.PERMISSION_GRANTED
                && ContextCompat.checkSelfPermission(this, Manifest.permission.READ_SMS) == PackageManager.PERMISSION_GRANTED
    }

    private fun hasNotificationPermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        } else true
    }

    private fun isIgnoringBatteryOptimizations(): Boolean {
        val pm = getSystemService(POWER_SERVICE) as android.os.PowerManager
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            pm.isIgnoringBatteryOptimizations(packageName)
        } else true
    }

    private fun updatePermissionStatus() {
        // 短信权限
        if (hasSmsPermissions()) {
            binding.ivSmsStatus.setImageResource(android.R.drawable.presence_online)
            binding.ivSmsStatus.setColorFilter(ContextCompat.getColor(this, R.color.success))
            binding.chipSmsStatus.text = "已授权"
            binding.chipSmsStatus.setTextColor(ContextCompat.getColor(this, R.color.success))
            binding.chipSmsStatus.background = ContextCompat.getDrawable(this, R.drawable.bg_chip_success)
        } else {
            binding.ivSmsStatus.setImageResource(android.R.drawable.presence_offline)
            binding.ivSmsStatus.setColorFilter(ContextCompat.getColor(this, R.color.error))
            binding.chipSmsStatus.text = "未授权"
            binding.chipSmsStatus.setTextColor(ContextCompat.getColor(this, R.color.error))
            binding.chipSmsStatus.background = ContextCompat.getDrawable(this, R.drawable.bg_chip_error)
        }

        // 通知权限
        if (hasNotificationPermission()) {
            binding.ivNotifyStatus.setImageResource(android.R.drawable.presence_online)
            binding.ivNotifyStatus.setColorFilter(ContextCompat.getColor(this, R.color.success))
            binding.chipNotifyStatus.text = "已授权"
            binding.chipNotifyStatus.setTextColor(ContextCompat.getColor(this, R.color.success))
            binding.chipNotifyStatus.background = ContextCompat.getDrawable(this, R.drawable.bg_chip_success)
        } else {
            binding.ivNotifyStatus.setImageResource(android.R.drawable.presence_offline)
            binding.ivNotifyStatus.setColorFilter(ContextCompat.getColor(this, R.color.error))
            binding.chipNotifyStatus.text = "未授权"
            binding.chipNotifyStatus.setTextColor(ContextCompat.getColor(this, R.color.error))
            binding.chipNotifyStatus.background = ContextCompat.getDrawable(this, R.drawable.bg_chip_error)
        }

        // 电池优化（后台高耗电）
        if (isIgnoringBatteryOptimizations()) {
            binding.ivBatteryStatus.setImageResource(android.R.drawable.presence_online)
            binding.ivBatteryStatus.setColorFilter(ContextCompat.getColor(this, R.color.success))
            binding.chipBatteryStatus.text = "已授权"
            binding.chipBatteryStatus.setTextColor(ContextCompat.getColor(this, R.color.success))
            binding.chipBatteryStatus.background = ContextCompat.getDrawable(this, R.drawable.bg_chip_success)
        } else {
            binding.ivBatteryStatus.setImageResource(android.R.drawable.presence_offline)
            binding.ivBatteryStatus.setColorFilter(ContextCompat.getColor(this, R.color.error))
            binding.chipBatteryStatus.text = "未授权"
            binding.chipBatteryStatus.setTextColor(ContextCompat.getColor(this, R.color.error))
            binding.chipBatteryStatus.background = ContextCompat.getDrawable(this, R.drawable.bg_chip_error)
        }
    }

    private fun refreshContactList() {
        val contacts = prefsManager.getContacts()
        binding.tvContactCount.text = "${contacts.size}人"
        if (contacts.isEmpty()) {
            binding.tvNoContacts.visibility = View.VISIBLE
            binding.rvContacts.visibility = View.GONE
        } else {
            binding.tvNoContacts.visibility = View.GONE
            binding.rvContacts.visibility = View.VISIBLE
            contactAdapter.updateContacts(contacts)
        }
    }

    @SuppressLint("Range")
    private fun parseSelectedContact(contactUri: Uri) {
        val projection = arrayOf(
            ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
            ContactsContract.CommonDataKinds.Phone.NUMBER
        )
        var cursor: Cursor? = null
        try {
            cursor = contentResolver.query(contactUri, projection, null, null, null)
            if (cursor != null && cursor.moveToFirst()) {
                val name = cursor.getString(cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME))
                val phone = cursor.getString(cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER))
                showAddEditContactDialog(name, phone)
            }
        } catch (e: Exception) {
            Log.e("MainActivity", "Failed to parse contact: ${e.message}")
            Toast.makeText(this, "解析联系人失败", Toast.LENGTH_SHORT).show()
        } finally {
            cursor?.close()
        }
    }

    private fun showAddEditContactDialog(prefilledName: String?, prefilledPhone: String?) {
        val dialogBinding = DialogContactEditBinding.inflate(layoutInflater)
        prefilledName?.let { dialogBinding.etName.setText(it) }
        prefilledPhone?.let { dialogBinding.etPhone.setText(it) }

        val ringtoneOptions = arrayOf("系统默认闹钟音", "系统默认电话铃声", "系统默认提示音")
        val ringtoneAdapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, ringtoneOptions)
        ringtoneAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        dialogBinding.spinnerRingtone.adapter = ringtoneAdapter

        val intervalOptions = arrayOf("30秒", "1分钟", "2分钟", "3分钟", "5分钟")
        val intervalAdapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, intervalOptions)
        intervalAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        dialogBinding.spinnerInterval.adapter = intervalAdapter

        AlertDialog.Builder(this)
            .setView(dialogBinding.root)
            .setPositiveButton("保存") { dialog, _ ->
                val name = dialogBinding.etName.text.toString().trim()
                val phone = dialogBinding.etPhone.text.toString().trim()
                if (phone.isEmpty()) {
                    Toast.makeText(this, "手机号码不能为空", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                val ringtoneValue = when (dialogBinding.spinnerRingtone.selectedItemPosition) {
                    0 -> "alarm"
                    1 -> "ringtone"
                    2 -> "notification"
                    else -> "alarm"
                }
                val intervalValue = when (dialogBinding.spinnerInterval.selectedItemPosition) {
                    0 -> 30
                    1 -> 60
                    2 -> 120
                    3 -> 180
                    4 -> 300
                    else -> 30
                }
                val newContact = SpecialContact(
                    id = UUID.randomUUID().toString(),
                    name = name,
                    phoneNumber = phone,
                    ringtoneUri = ringtoneValue,
                    repeatIntervalSec = intervalValue
                )
                prefsManager.addContact(newContact)
                refreshContactList()
                dialog.dismiss()
            }
            .setNegativeButton("取消") { dialog, _ -> dialog.dismiss() }
            .show()
    }

    private fun showDeleteConfirmation(contact: SpecialContact) {
        AlertDialog.Builder(this)
            .setTitle("删除特别关注")
            .setMessage("确定要删除对「${contact.name.ifEmpty { contact.phoneNumber }}」的特别关注提醒吗？")
            .setPositiveButton("删除") { dialog, _ ->
                prefsManager.removeContact(contact.id)
                refreshContactList()
                dialog.dismiss()
            }
            .setNegativeButton("取消") { dialog, _ -> dialog.dismiss() }
            .show()
    }

    private fun jumpToSettings() {
        try {
            val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = Uri.fromParts("package", packageName, null)
            }
            startActivity(intent)
        } catch (e: Exception) {
            Log.e("MainActivity", "Failed to open settings: ${e.message}")
            Toast.makeText(this, "跳转设置页面失败，请手动到系统设置中开启权限", Toast.LENGTH_SHORT).show()
        }
    }

    private fun jumpToBatterySettings() {
        try {
            val intent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                    data = Uri.fromParts("package", packageName, null)
                }
            } else {
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                    data = Uri.fromParts("package", packageName, null)
                }
            }
            startActivity(intent)
        } catch (e: Exception) {
            Log.e("MainActivity", "Failed to open battery settings: ${e.message}")
            // fallback to general settings
            jumpToSettings()
        }
    }
}

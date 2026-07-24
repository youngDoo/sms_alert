package com.lightweight.smsalert.ui

import android.Manifest
import android.annotation.SuppressLint
import android.app.AlertDialog
import android.content.Context
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
    private lateinit var contactAdapter: ContactAdapter

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

    private fun setupUI() {
        binding.switchListener.isChecked = prefsManager.isListenerEnabled
        binding.switchListener.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked) {
                if (hasSmsPermissions()) {
                    prefsManager.isListenerEnabled = true
                    SmsReceiver.registerDynamic(this)
                    SmsBackupJobService.schedule(this, immediate = true)
                    Toast.makeText(this, "短信监听服务已启用", Toast.LENGTH_SHORT).show()
                } else {
                    binding.switchListener.isChecked = false
                    Toast.makeText(this, "请先授予短信读取权限", Toast.LENGTH_LONG).show()
                    jumpToSettings()
                }
            } else {
                prefsManager.isListenerEnabled = false
                SmsReceiver.unregisterDynamic(this)
                SmsBackupJobService.cancel(this)
                Toast.makeText(this, "短信监听服务已关闭", Toast.LENGTH_SHORT).show()
            }
        }

        binding.btnRequestPermissions.setOnClickListener {
            jumpToSettings()
        }

        binding.btnAddContact.setOnClickListener {
            showAddEditContactDialog(null, null)
        }

        binding.btnImportContact.setOnClickListener {
            try {
                val intent = Intent(Intent.ACTION_PICK, ContactsContract.CommonDataKinds.Phone.CONTENT_URI)
                contactPickerLauncher.launch(intent)
            } catch (e: Exception) {
                Toast.makeText(this, "无法打开通讯录，请检查权限", Toast.LENGTH_SHORT).show()
            }
        }
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
        val hasReceive = ContextCompat.checkSelfPermission(this, Manifest.permission.RECEIVE_SMS) == PackageManager.PERMISSION_GRANTED
        val hasRead = ContextCompat.checkSelfPermission(this, Manifest.permission.READ_SMS) == PackageManager.PERMISSION_GRANTED
        return hasReceive && hasRead
    }

    private fun hasNotificationPermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        } else {
            true
        }
    }

    private fun updatePermissionStatus() {
        if (hasSmsPermissions()) {
            binding.tvPermissionSms.text = "• 短信读取与接收权限：已授权"
            binding.tvPermissionSms.setTextColor(ContextCompat.getColor(this, com.lightweight.smsalert.R.color.green))
        } else {
            binding.tvPermissionSms.text = "• 短信读取与接收权限：未授权 (必填)"
            binding.tvPermissionSms.setTextColor(ContextCompat.getColor(this, com.lightweight.smsalert.R.color.red))
        }

        if (hasNotificationPermission()) {
            binding.tvPermissionNotification.text = "• 发送通知权限：已授权"
            binding.tvPermissionNotification.setTextColor(ContextCompat.getColor(this, com.lightweight.smsalert.R.color.green))
        } else {
            binding.tvPermissionNotification.text = "• 发送通知权限：未授权 (影响提醒)"
            binding.tvPermissionNotification.setTextColor(ContextCompat.getColor(this, com.lightweight.smsalert.R.color.red))
        }
    }

    private fun refreshContactList() {
        val contacts = prefsManager.getContacts()
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
            .setMessage("确定要删除对“${contact.name.ifEmpty { contact.phoneNumber }}”的特别关注提醒吗？")
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
}

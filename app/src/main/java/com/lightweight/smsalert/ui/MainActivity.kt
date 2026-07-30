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
import android.view.LayoutInflater
import android.view.View
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.ImageView
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.DefaultItemAnimator
import androidx.recyclerview.widget.LinearLayoutManager
import com.lightweight.smsalert.R
import com.lightweight.smsalert.data.PrefsManager
import com.lightweight.smsalert.databinding.ActivityMainBinding
import com.lightweight.smsalert.databinding.DialogContactEditBinding
import com.lightweight.smsalert.model.ContentRule
import com.lightweight.smsalert.model.SpecialContact
import com.lightweight.smsalert.receiver.SmsReceiver
import com.lightweight.smsalert.service.RingtoneService
import com.lightweight.smsalert.service.SmsBackupJobService
import java.util.UUID

class MainActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "MainActivity"
    }

    private lateinit var binding: ActivityMainBinding
    private lateinit var prefsManager: PrefsManager
    private var lastResumeAlertMs = 0L

    // Adapter 缓存（性能：避免每次 onResume 重建）
    private lateinit var contactAdapter: ContactAdapter
    private lateinit var contentRuleAdapter: ContentRuleAdapter

    // Ringtone spinner 选项（每次 Dialog 独立创建 adapter 避免状态冲突）
    private val ringtoneOptions = arrayOf("系统默认闹钟音", "系统默认电话铃声", "系统默认提示音")

    private val contactPickerLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            val contactUri = result.data?.data ?: return@registerForActivityResult
            parseSelectedContact(contactUri)
        }
    }

    // ─── 生命周期 ─────────────────────────────────────────────────

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setSupportActionBar(binding.toolbar)

        prefsManager = PrefsManager(this)

        setupRecyclerViews()
        setupUI()
        checkAndRequestFirstLaunchPermissions()
    }

    override fun onResume() {
        super.onResume()
        updatePermissionStatus()
        refreshContactList()
        refreshContentRules()
        // 恢复机制：从桌面图标进入时若正在响铃，弹出 AlertActivity（3 秒防抖防死循环）
        if (RingtoneService.isRinging() && System.currentTimeMillis() - lastResumeAlertMs > 3000) {
            lastResumeAlertMs = System.currentTimeMillis()
            startActivity(Intent(this, AlertActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
                putExtra("sender_name", RingtoneService.getLastSenderName())
                putExtra("sender_phone", RingtoneService.getLastSenderPhone())
                putExtra("sms_body", RingtoneService.getLastSmsBody())
            })
        }
    }

    // ─── RecyclerView 初始化 ───────────────────────────────────────

    private fun setupRecyclerViews() {
        // 联系人列表：ListAdapter + DiffUtil + 逐条动画
        contactAdapter = ContactAdapter { contact ->
            showDeleteConfirmation(contact)
        }
        binding.rvContacts.apply {
            layoutManager = LinearLayoutManager(this@MainActivity)
            adapter = contactAdapter
            itemAnimator = DefaultItemAnimator().apply {
                addDuration = 200
                removeDuration = 200
            }
        }

        // 内容规则列表：ListAdapter + DiffUtil
        contentRuleAdapter = ContentRuleAdapter { rule ->
            prefsManager.removeContentRule(rule.id)
            refreshContentRules()
        }
        binding.rvContentRules.apply {
            layoutManager = LinearLayoutManager(this@MainActivity)
            adapter = contentRuleAdapter
            itemAnimator = DefaultItemAnimator().apply {
                addDuration = 200
                removeDuration = 200
            }
        }
    }

    // ─── UI 绑定 ──────────────────────────────────────────────────

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
                    animateSubSwitches(true)
                    applySubSwitchStates()
                    Toast.makeText(this, "短信监听服务已启用", Toast.LENGTH_SHORT).show()
                } else {
                    binding.switchListener.isChecked = false
                    Toast.makeText(this, "请先授予短信读取权限", Toast.LENGTH_LONG).show()
                    jumpToSettings()
                }
            } else {
                prefsManager.isListenerEnabled = false
                animateSubSwitches(false)
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

        // 手动添加联系人
        binding.btnAddContact.setOnClickListener {
            showAddEditContactDialog(null, null)
        }

        // 通讯录导入
        binding.btnImportContact.setOnClickListener {
            try {
                val intent = Intent(Intent.ACTION_PICK, ContactsContract.CommonDataKinds.Phone.CONTENT_URI)
                contactPickerLauncher.launch(intent)
            } catch (e: Exception) {
                Toast.makeText(this, "无法打开通讯录，请检查权限", Toast.LENGTH_SHORT).show()
            }
        }

        // 添加内容规则
        binding.btnAddContentRule.setOnClickListener {
            showAddContentRuleDialog()
        }

        // 权限卡片整体点击 → 去设置
        binding.cardPermissions.setOnClickListener {
            jumpToSettings()
        }

        // 电池优化行点击 → 跳转后台高耗电设置
        binding.layoutBatteryOpt.setOnClickListener {
            jumpToBatterySettings()
        }

        // 后台弹出界面行点击（Vivo 专属）
        binding.layoutBgPopup.setOnClickListener {
            jumpToSettings()
        }
    }

    // ─── 子开关展开/收起动画 ─────────────────────────────────────

    private fun animateSubSwitches(expand: Boolean) {
        if (expand) {
            binding.layoutSubSwitches.apply {
                alpha = 0f
                translationY = -20f
                visibility = View.VISIBLE
                animate()
                    .alpha(1f)
                    .translationY(0f)
                    .setDuration(250)
                    .start()
            }
        } else {
            binding.layoutSubSwitches.animate()
                .alpha(0f)
                .translationY(-20f)
                .setDuration(200)
                .withEndAction { binding.layoutSubSwitches.visibility = View.GONE }
                .start()
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

    // ─── 工具栏菜单 ────────────────────────────────────────────────

    override fun onCreateOptionsMenu(menu: android.view.Menu): Boolean {
        menuInflater.inflate(R.menu.toolbar_menu, menu)
        return true
    }

    override fun onOptionsItemSelected(item: android.view.MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_about -> {
                showAboutDialog()
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    // ─── 权限检测 ──────────────────────────────────────────────────

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

    private fun isVivoOrBbk(): Boolean {
        val mfr = Build.MANUFACTURER.lowercase()
        return mfr == "vivo" || mfr == "bbk"
    }

    /** DRY 权限状态行更新：只切 3 个 View 的状态 */
    private fun updatePermissionRow(
        iconView: ImageView,
        chipView: TextView,
        isGranted: Boolean,
        grantedText: String = "已授权",
        deniedText: String = "未授权"
    ) {
        if (isGranted) {
            iconView.setImageResource(R.drawable.ic_status_ok)
            chipView.text = grantedText
            chipView.setTextColor(ContextCompat.getColor(this, R.color.success))
            chipView.background = ContextCompat.getDrawable(this, R.drawable.bg_chip_success)
        } else {
            iconView.setImageResource(R.drawable.ic_status_error)
            chipView.text = deniedText
            chipView.setTextColor(ContextCompat.getColor(this, R.color.error))
            chipView.background = ContextCompat.getDrawable(this, R.drawable.bg_chip_error)
        }
    }

    private fun updatePermissionStatus() {
        updatePermissionRow(binding.ivSmsStatus, binding.chipSmsStatus, hasSmsPermissions())
        updatePermissionRow(binding.ivNotifyStatus, binding.chipNotifyStatus, hasNotificationPermission())
        updatePermissionRow(binding.ivBatteryStatus, binding.chipBatteryStatus, isIgnoringBatteryOptimizations())

        // 后台弹出界面（Vivo/BBK 专属）
        binding.layoutBgPopup.visibility = if (isVivoOrBbk()) View.VISIBLE else View.GONE
    }

    // ─── 联系人列表 ────────────────────────────────────────────────

    private fun refreshContactList() {
        val contacts = prefsManager.getContacts()
        binding.tvContactCount.text = "${contacts.size}人"
        val isEmpty = contacts.isEmpty()
        binding.tvNoContacts.visibility = if (isEmpty) View.VISIBLE else View.GONE
        binding.rvContacts.visibility = if (isEmpty) View.GONE else View.VISIBLE
        contactAdapter.submitList(contacts)
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
            Log.e(TAG, "Failed to parse contact: ${e.message}")
            Toast.makeText(this, "解析联系人失败", Toast.LENGTH_SHORT).show()
        } finally {
            cursor?.close()
        }
    }

    // ─── 联系人对话框 ──────────────────────────────────────────────

    private fun showAddEditContactDialog(prefilledName: String?, prefilledPhone: String?) {
        val dialogBinding = DialogContactEditBinding.inflate(layoutInflater)
        prefilledName?.let { dialogBinding.etName.setText(it) }
        prefilledPhone?.let { dialogBinding.etPhone.setText(it) }
        dialogBinding.spinnerRingtone.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, ringtoneOptions).apply {
            setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        }

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
                val newContact = SpecialContact(
                    id = UUID.randomUUID().toString(),
                    name = name,
                    phoneNumber = phone,
                    ringtoneUri = ringtoneValue,
                    repeatIntervalSec = 30
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

    // ─── 内容规则 ──────────────────────────────────────────────────

    private fun refreshContentRules() {
        val rules = prefsManager.getContentRules()
        binding.tvContentRuleCount.text = "${rules.size}条"
        val isEmpty = rules.isEmpty()
        binding.tvNoContentRules.visibility = if (isEmpty) View.VISIBLE else View.GONE
        binding.rvContentRules.visibility = if (isEmpty) View.GONE else View.VISIBLE
        contentRuleAdapter.submitList(rules)
    }

    private fun showAddContentRuleDialog() {
        val dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_content_rule_edit, null)
        val etName = dialogView.findViewById<EditText>(R.id.etRuleName)
        val etPattern = dialogView.findViewById<EditText>(R.id.etRulePattern)
        val spinnerRingtone = dialogView.findViewById<Spinner>(R.id.spinnerRingtone)
        spinnerRingtone.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, ringtoneOptions).apply {
            setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        }

        AlertDialog.Builder(this)
            .setTitle("添加内容规则")
            .setView(dialogView)
            .setPositiveButton("保存") { dialog, _ ->
                val name = etName.text.toString().trim()
                val pattern = etPattern.text.toString().trim()
                if (name.isEmpty() || pattern.isEmpty()) {
                    Toast.makeText(this, "名称和正则表达式不能为空", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                val ringtone = when (spinnerRingtone.selectedItemPosition) {
                    0 -> "alarm"; 1 -> "ringtone"; 2 -> "notification"; else -> "alarm"
                }
                prefsManager.addContentRule(ContentRule(
                    id = UUID.randomUUID().toString(), name = name, pattern = pattern,
                    ringtoneUri = ringtone, repeatIntervalSec = 30
                ))
                refreshContentRules()
                dialog.dismiss()
            }
            .setNegativeButton("取消") { dialog, _ -> dialog.dismiss() }
            .show()
    }

    // ─── 关于对话框 ────────────────────────────────────────────────

    private fun showAboutDialog() {
        val aboutView = LayoutInflater.from(this).inflate(R.layout.dialog_about, null)

        // 设置版本号
        val versionName = try {
            packageManager.getPackageInfo(packageName, 0).versionName ?: "1.0.0"
        } catch (e: PackageManager.NameNotFoundException) {
            "1.0.0"
        }
        aboutView.findViewById<TextView>(R.id.tvAboutAppName).text = "短信钉  $versionName"

        // GitHub 链接可点击
        aboutView.findViewById<TextView>(R.id.tvAboutGithub).apply {
            paintFlags = paintFlags or android.graphics.Paint.UNDERLINE_TEXT_FLAG
            setOnClickListener {
                try {
                    startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/youngDoo/sms_alert")))
                } catch (e: Exception) {
                    Toast.makeText(this@MainActivity, "无法打开浏览器", Toast.LENGTH_SHORT).show()
                }
            }
        }

        AlertDialog.Builder(this)
            .setView(aboutView)
            .setPositiveButton("确定") { dialog, _ -> dialog.dismiss() }
            .show()
    }

    // ─── 跳转系统设置 ──────────────────────────────────────────────

    private fun jumpToSettings() {
        try {
            val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = Uri.fromParts("package", packageName, null)
            }
            startActivity(intent)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to open settings: ${e.message}")
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
            Log.e(TAG, "Failed to open battery settings: ${e.message}")
            jumpToSettings()
        }
    }
}

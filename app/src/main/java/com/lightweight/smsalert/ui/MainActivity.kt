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
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
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
    private var lastResumeAlertMs = 0L  // 防抖：onResume 恢复弹窗的最小间隔

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

        // 权限卡片整体点击 → 去设置（始终可点击，已授权也能进去查看）
        binding.cardPermissions.setOnClickListener {
            jumpToSettings()
        }

        // 电池优化行点击 → 跳转后台高耗电设置（始终可点击）
        binding.layoutBatteryOpt.setOnClickListener {
            jumpToBatterySettings()
        }

        // 后台弹出界面行点击 → 跳转系统设置（Vivo 专属）
        binding.layoutBgPopup.setOnClickListener {
            jumpToSettings()
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

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.toolbar_menu, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
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

    /** Vivo/BBK 设备检测：这些设备需要在设置中额外开启「后台弹出界面」权限 */
    private fun isVivoOrBbk(): Boolean {
        val mfr = Build.MANUFACTURER.lowercase()
        return mfr == "vivo" || mfr == "bbk"
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

        // 后台弹出界面（Vivo/BBK 专属，无法用 API 检测 → 仅显示引导入口，不显示假状态）
        if (isVivoOrBbk()) {
            binding.layoutBgPopup.visibility = View.VISIBLE
        } else {
            binding.layoutBgPopup.visibility = View.GONE
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
            Log.e(TAG, "Failed to parse contact: ${e.message}")
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
                    repeatIntervalSec = 30  // 保留字段向后兼容，当前行为：响铃无限循环直到手动停止
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

    // ─── 内容规则 ──────────────────────────────────────────────────

    private var contentRulesContainer: LinearLayout? = null

    private fun refreshContentRules() {
        val rules = prefsManager.getContentRules()
        contentRulesContainer?.removeAllViews()

        val parent = binding.rvContacts.parent as? LinearLayout ?: return
        contentRulesContainer?.let { parent.removeView(it) }

        // 容器
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = 16.dp() }
            setPadding(0, 8.dp(), 0, 0)
        }

        // 标题行（与联系人标题行保持一致）
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
        }
        val title = TextView(this).apply {
            text = "内容规则"
            setTextColor(ContextCompat.getColor(this@MainActivity, R.color.text_main))
            textSize = 17f
            setTypeface(null, android.graphics.Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        val count = TextView(this).apply {
            text = "${rules.size}条"
            setTextColor(ContextCompat.getColor(this@MainActivity, R.color.text_muted))
            textSize = 12f
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { marginEnd = 12.dp() }
        }
        // ★ 添加按钮：inflate XML 布局，与联系人按钮 100% 一致的 TonalButton.Icon 样式
        val addBtn = LayoutInflater.from(this).inflate(
            R.layout.btn_add_tonal, container, false
        ) as com.google.android.material.button.MaterialButton
        addBtn.setOnClickListener { showAddContentRuleDialog() }
        header.addView(title)
        header.addView(count)
        header.addView(addBtn)
        container.addView(header)

        // ★ 规则列表：使用 item_content_rule.xml 卡片布局，与联系人卡片风格统一
        for (rule in rules) {
            val itemView = LayoutInflater.from(this).inflate(R.layout.item_content_rule, container, false)
            val tvIcon = itemView.findViewById<TextView>(R.id.tvRuleIcon)
            val tvName = itemView.findViewById<TextView>(R.id.tvRuleName)
            val tvPattern = itemView.findViewById<TextView>(R.id.tvRulePattern)
            val tvRingtone = itemView.findViewById<TextView>(R.id.tvRuleRingtone)
            val btnDelete = itemView.findViewById<ImageButton>(R.id.btnDeleteRule)

            tvIcon.text = rule.name.firstOrNull()?.toString() ?: "规"
            tvName.text = rule.name
            tvPattern.text = rule.pattern
            tvRingtone.text = when (rule.ringtoneUri) {
                "alarm" -> "闹钟音"
                "ringtone" -> "电话铃"
                "notification" -> "提示音"
                else -> "系统默认"
            }
            btnDelete.setOnClickListener {
                prefsManager.removeContentRule(rule.id)
                refreshContentRules()
            }
            container.addView(itemView)
        }

        parent.addView(container)
        contentRulesContainer = container
    }

    private fun showAddContentRuleDialog() {
        val dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_content_rule_edit, null)
        val etName = dialogView.findViewById<EditText>(R.id.etRuleName)
        val etPattern = dialogView.findViewById<EditText>(R.id.etRulePattern)
        val spinnerRingtone = dialogView.findViewById<Spinner>(R.id.spinnerRingtone)

        val ringtoneOptions = arrayOf("系统默认闹钟音", "系统默认电话铃声", "系统默认提示音")
        val ringtoneAdapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, ringtoneOptions)
        ringtoneAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        spinnerRingtone.adapter = ringtoneAdapter

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

    // ─── 关于 ──────────────────────────────────────────────────────

    private fun showAboutDialog() {
        val versionName = try {
            packageManager.getPackageInfo(packageName, 0).versionName ?: "1.0.0"
        } catch (e: PackageManager.NameNotFoundException) {
            "1.0.0"
        }

        val aboutLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = android.view.Gravity.CENTER_HORIZONTAL
            setPadding(48.dp(), 32.dp(), 48.dp(), 16.dp())
        }

        // 应用图标
        val iconView = TextView(this).apply {
            text = "钉"
            textSize = 32f
            setTextColor(ContextCompat.getColor(this@MainActivity, R.color.primary))
            setTypeface(null, android.graphics.Typeface.BOLD)
            gravity = android.view.Gravity.CENTER
            width = 72.dp()
            height = 72.dp()
            background = ContextCompat.getDrawable(this@MainActivity, R.drawable.bg_avatar_circle)
        }
        aboutLayout.addView(iconView)

        // 应用名 + 版本
        val nameView = TextView(this).apply {
            text = "短信钉  $versionName"
            textSize = 18f
            setTextColor(ContextCompat.getColor(this@MainActivity, R.color.text_main))
            setTypeface(null, android.graphics.Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = 16.dp() }
        }
        aboutLayout.addView(nameView)

        // 描述
        val descView = TextView(this).apply {
            text = "零后台 · 不耗电\n特别关注短信提醒工具"
            textSize = 13f
            setTextColor(ContextCompat.getColor(this@MainActivity, R.color.text_secondary))
            gravity = android.view.Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = 12.dp() }
            setLineSpacing(4.dp().toFloat(), 1.0f)
        }
        aboutLayout.addView(descView)

        // 分隔线
        val divider = View(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 1.dp()
            ).apply {
                topMargin = 20.dp()
                marginStart = 16.dp()
                marginEnd = 16.dp()
            }
            setBackgroundColor(ContextCompat.getColor(this@MainActivity, R.color.divider))
        }
        aboutLayout.addView(divider)

        // 开源信息标题
        val ossLabel = TextView(this).apply {
            text = "开源地址"
            textSize = 13f
            setTextColor(ContextCompat.getColor(this@MainActivity, R.color.text_muted))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = 16.dp() }
        }
        aboutLayout.addView(ossLabel)

        // GitHub 链接（可点击）
        val githubView = TextView(this).apply {
            text = "github.com/youngDoo/sms_alert"
            textSize = 13f
            setTextColor(ContextCompat.getColor(this@MainActivity, R.color.primary))
            paintFlags = paintFlags or android.graphics.Paint.UNDERLINE_TEXT_FLAG
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = 4.dp() }
            setOnClickListener {
                try {
                    startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/youngDoo/sms_alert")))
                } catch (e: Exception) {
                    Toast.makeText(this@MainActivity, "无法打开浏览器", Toast.LENGTH_SHORT).show()
                }
            }
        }
        aboutLayout.addView(githubView)

        // Star 邀请
        val starView = TextView(this).apply {
            text = "如果觉得好用，欢迎到 GitHub 点个 Star ⭐"
            textSize = 12f
            setTextColor(ContextCompat.getColor(this@MainActivity, R.color.text_secondary))
            gravity = android.view.Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = 12.dp() }
        }
        aboutLayout.addView(starView)

        // 许可证
        val licenseView = TextView(this).apply {
            text = "MIT License"
            textSize = 11f
            setTextColor(ContextCompat.getColor(this@MainActivity, R.color.text_muted))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = 8.dp() }
        }
        aboutLayout.addView(licenseView)

        // 版权
        val copyrightView = TextView(this).apply {
            text = "© 2026 推陈出新"
            textSize = 11f
            setTextColor(ContextCompat.getColor(this@MainActivity, R.color.text_muted))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = 20.dp() }
        }
        aboutLayout.addView(copyrightView)

        AlertDialog.Builder(this)
            .setView(aboutLayout)
            .setPositiveButton("确定") { dialog, _ -> dialog.dismiss() }
            .show()
    }

    private fun Int.dp(): Int = (this * resources.displayMetrics.density).toInt()
}

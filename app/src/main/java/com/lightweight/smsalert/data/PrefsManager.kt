package com.lightweight.smsalert.data

import android.content.Context
import android.content.SharedPreferences
import com.lightweight.smsalert.model.ContentRule
import com.lightweight.smsalert.model.SpecialContact
import org.json.JSONArray

class PrefsManager(context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences("sms_alert_prefs", Context.MODE_PRIVATE)

    companion object {
        private const val KEY_LISTENER_ENABLED = "listener_enabled"
        private const val KEY_BROADCAST_ENABLED = "broadcast_enabled"
        private const val KEY_SCAN_ENABLED = "scan_enabled"
        private const val KEY_CONTACTS = "special_contacts"
        private const val KEY_CONTENT_RULES = "content_rules"
        private const val KEY_SMS_CACHE = "processed_sms_cache"
        private const val MAX_CACHE_SIZE = 10
    }

    var isListenerEnabled: Boolean
        get() = prefs.getBoolean(KEY_LISTENER_ENABLED, false)
        set(value) = prefs.edit().putBoolean(KEY_LISTENER_ENABLED, value).apply()

    var isBroadcastEnabled: Boolean
        get() = prefs.getBoolean(KEY_BROADCAST_ENABLED, true)
        set(value) = prefs.edit().putBoolean(KEY_BROADCAST_ENABLED, value).apply()

    var isScanEnabled: Boolean
        get() = prefs.getBoolean(KEY_SCAN_ENABLED, true)
        set(value) = prefs.edit().putBoolean(KEY_SCAN_ENABLED, value).apply()

    fun getContacts(): List<SpecialContact> {
        val jsonStr = prefs.getString(KEY_CONTACTS, null) ?: return emptyList()
        val list = mutableListOf<SpecialContact>()
        try {
            val jsonArray = JSONArray(jsonStr)
            for (i in 0 until jsonArray.length()) {
                list.add(SpecialContact.fromJson(jsonArray.getString(i)))
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return list
    }

    fun saveContacts(contacts: List<SpecialContact>) {
        val jsonArray = JSONArray()
        for (contact in contacts) {
            jsonArray.put(contact.toJsonObject().toString())
        }
        prefs.edit().putString(KEY_CONTACTS, jsonArray.toString()).apply()
    }

    fun addContact(contact: SpecialContact) {
        val contacts = getContacts().toMutableList()
        contacts.removeAll { isSamePhoneNumber(it.phoneNumber, contact.phoneNumber) }
        contacts.add(contact)
        saveContacts(contacts)
    }

    fun removeContact(contactId: String) {
        val contacts = getContacts().toMutableList()
        contacts.removeAll { it.id == contactId }
        saveContacts(contacts)
    }

    fun clearContacts() {
        prefs.edit().remove(KEY_CONTACTS).apply()
    }

    fun findMatchingContact(incomingNumber: String?): SpecialContact? {
        if (incomingNumber.isNullOrEmpty()) return null
        val contacts = getContacts()
        return contacts.firstOrNull { isSamePhoneNumber(it.phoneNumber, incomingNumber) }
    }

    // ─── 内容规则 ──────────────────────────────────────────────────

    fun getContentRules(): List<ContentRule> {
        val jsonStr = prefs.getString(KEY_CONTENT_RULES, null) ?: return emptyList()
        val list = mutableListOf<ContentRule>()
        try {
            val jsonArray = JSONArray(jsonStr)
            for (i in 0 until jsonArray.length()) {
                list.add(ContentRule.fromJson(jsonArray.getString(i)))
            }
        } catch (e: Exception) { e.printStackTrace() }
        return list
    }

    private fun saveContentRules(rules: List<ContentRule>) {
        val jsonArray = JSONArray()
        for (rule in rules) jsonArray.put(rule.toJsonObject().toString())
        prefs.edit().putString(KEY_CONTENT_RULES, jsonArray.toString()).apply()
    }

    fun addContentRule(rule: ContentRule) {
        val rules = getContentRules().toMutableList()
        // 同名规则覆盖
        rules.removeAll { it.name == rule.name }
        rules.add(rule)
        saveContentRules(rules)
    }

    fun removeContentRule(ruleId: String) {
        val rules = getContentRules().toMutableList()
        rules.removeAll { it.id == ruleId }
        saveContentRules(rules)
    }

    /** 返回第一个匹配短信正文的正则规则，未匹配返回 null */
    fun findMatchingContentRule(smsBody: String?): ContentRule? {
        if (smsBody.isNullOrEmpty()) return null
        return getContentRules().firstOrNull { rule ->
            try {
                Regex(rule.pattern).containsMatchIn(smsBody)
            } catch (e: Exception) {
                false // 无效正则跳过
            }
        }
    }

    // ─── 去重 ─────────────────────────────────────────────────────

    @Synchronized
    fun isDuplicateSms(smsId: String, sender: String, timestampMs: Long): Boolean {
        val minuteTimestamp = timestampMs / 60000
        val smsKey = "${smsId}_${normalizePhone(sender)}_${minuteTimestamp}"

        val cacheList = getSmsCache().toMutableList()
        if (cacheList.contains(smsKey)) {
            return true
        }

        cacheList.add(smsKey)
        while (cacheList.size > MAX_CACHE_SIZE) {
            cacheList.removeAt(0)
        }
        saveSmsCache(cacheList)
        return false
    }

    private fun getSmsCache(): List<String> {
        val jsonStr = prefs.getString(KEY_SMS_CACHE, null) ?: return emptyList()
        val list = mutableListOf<String>()
        try {
            val jsonArray = JSONArray(jsonStr)
            for (i in 0 until jsonArray.length()) {
                list.add(jsonArray.getString(i))
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return list
    }

    private fun saveSmsCache(cache: List<String>) {
        val jsonArray = JSONArray()
        for (item in cache) {
            jsonArray.put(item)
        }
        prefs.edit().putString(KEY_SMS_CACHE, jsonArray.toString()).apply()
    }

    internal fun normalizePhone(phone: String): String = PhoneUtils.normalize(phone)

    internal fun isSamePhoneNumber(p1: String, p2: String): Boolean {
        val n1 = normalizePhone(p1)
        val n2 = normalizePhone(p2)
        return n1 == n2 || n1.endsWith(n2) || n2.endsWith(n1)
    }
}

package com.lightweight.smsalert.model

import org.json.JSONObject

data class ContentRule(
    val id: String,
    val name: String,           // 规则名称，如 "验证码"、"银行通知"
    val pattern: String,        // 正则表达式，匹配短信正文
    val ringtoneUri: String = "default",
    val repeatIntervalSec: Int = 30
) {
    fun toJsonObject(): JSONObject = JSONObject().apply {
        put("id", id)
        put("name", name)
        put("pattern", pattern)
        put("ringtoneUri", ringtoneUri)
        put("repeatIntervalSec", repeatIntervalSec)
    }

    companion object {
        fun fromJson(jsonStr: String): ContentRule {
            val json = JSONObject(jsonStr)
            return ContentRule(
                id = json.getString("id"),
                name = json.getString("name"),
                pattern = json.getString("pattern"),
                ringtoneUri = json.optString("ringtoneUri", "default"),
                repeatIntervalSec = json.optInt("repeatIntervalSec", 30)
            )
        }
    }
}

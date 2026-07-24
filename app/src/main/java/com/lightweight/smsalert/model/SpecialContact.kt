package com.lightweight.smsalert.model

import org.json.JSONObject

data class SpecialContact(
    val id: String,
    val name: String,
    val phoneNumber: String,
    val ringtoneUri: String = "default",
    val repeatIntervalSec: Int = 30
) {
    fun toJsonObject(): JSONObject {
        val json = JSONObject()
        json.put("id", id)
        json.put("name", name)
        json.put("phoneNumber", phoneNumber)
        json.put("ringtoneUri", ringtoneUri)
        json.put("repeatIntervalSec", repeatIntervalSec)
        return json
    }

    companion object {
        fun fromJson(jsonStr: String): SpecialContact {
            val json = JSONObject(jsonStr)
            return SpecialContact(
                id = json.getString("id"),
                name = json.getString("name"),
                phoneNumber = json.getString("phoneNumber"),
                ringtoneUri = json.optString("ringtoneUri", "default"),
                repeatIntervalSec = json.optInt("repeatIntervalSec", 30)
            )
        }
    }
}

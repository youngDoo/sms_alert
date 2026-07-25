package com.lightweight.smsalert.model

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class SpecialContactTest {

    @Test
    fun `toJsonObject produces valid JSON with all fields`() {
        val contact = SpecialContact(
            id = "abc-123",
            name = "张三",
            phoneNumber = "13800138000",
            ringtoneUri = "alarm",
            repeatIntervalSec = 60
        )

        val json = contact.toJsonObject()

        assertEquals("abc-123", json.getString("id"))
        assertEquals("张三", json.getString("name"))
        assertEquals("13800138000", json.getString("phoneNumber"))
        assertEquals("alarm", json.getString("ringtoneUri"))
        assertEquals(60, json.getInt("repeatIntervalSec"))
    }

    @Test
    fun `fromJson deserializes correctly`() {
        val json = JSONObject().apply {
            put("id", "xyz-789")
            put("name", "李四")
            put("phoneNumber", "13900139000")
            put("ringtoneUri", "ringtone")
            put("repeatIntervalSec", 120)
        }

        val contact = SpecialContact.fromJson(json.toString())

        assertEquals("xyz-789", contact.id)
        assertEquals("李四", contact.name)
        assertEquals("13900139000", contact.phoneNumber)
        assertEquals("ringtone", contact.ringtoneUri)
        assertEquals(120, contact.repeatIntervalSec)
    }

    @Test
    fun `fromJson uses defaults for missing fields`() {
        val json = JSONObject().apply {
            put("id", "min-001")
            put("name", "王五")
            put("phoneNumber", "13700137000")
        }

        val contact = SpecialContact.fromJson(json.toString())

        assertEquals("default", contact.ringtoneUri)
        assertEquals(30, contact.repeatIntervalSec)
    }

    @Test
    fun `roundtrip preserves all fields`() {
        val original = SpecialContact(
            id = "round-001",
            name = "赵六",
            phoneNumber = "13600136000",
            ringtoneUri = "notification",
            repeatIntervalSec = 180
        )

        val restored = SpecialContact.fromJson(original.toJsonObject().toString())

        assertEquals(original, restored)
    }

    @Test
    fun `equality is based on all fields`() {
        val a = SpecialContact("1", "A", "100", "alarm", 30)
        val b = SpecialContact("1", "A", "100", "alarm", 30)
        val c = SpecialContact("1", "A", "100", "ringtone", 30)

        assertEquals(a, b)
        assertNotEquals(a, c)
    }
}

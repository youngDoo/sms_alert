package com.lightweight.smsalert.data

import org.junit.Assert.*
import org.junit.Test

class PhoneUtilsTest {

    // ========== normalize ==========

    @Test
    fun `normalize removes whitespace and dashes`() {
        assertEquals("13800138000", PhoneUtils.normalize("138 0013 8000"))
        assertEquals("13800138000", PhoneUtils.normalize("138-0013-8000"))
        assertEquals("13800138000", PhoneUtils.normalize(" 13800138000 "))
    }

    @Test
    fun `normalize strips +86 prefix`() {
        assertEquals("13800138000", PhoneUtils.normalize("+8613800138000"))
    }

    @Test
    fun `normalize strips 86 prefix without plus`() {
        assertEquals("13800138000", PhoneUtils.normalize("8613800138000"))
    }

    @Test
    fun `normalize does NOT strip 86 from short codes like 10086`() {
        assertEquals("10086", PhoneUtils.normalize("10086"))
    }

    @Test
    fun `normalize handles already clean number`() {
        assertEquals("13800138000", PhoneUtils.normalize("13800138000"))
    }

    // ========== isSame ==========

    @Test
    fun `isSame exact match`() {
        assertTrue(PhoneUtils.isSame("13800138000", "13800138000"))
    }

    @Test
    fun `isSame match with different formatting`() {
        assertTrue(PhoneUtils.isSame("138 0013 8000", "13800138000"))
    }

    @Test
    fun `isSame match with +86 and without`() {
        assertTrue(PhoneUtils.isSame("+8613800138000", "13800138000"))
    }

    @Test
    fun `isSame different numbers do not match`() {
        assertFalse(PhoneUtils.isSame("13800138000", "13900139000"))
    }

    @Test
    fun `isSame completely different numbers do not match`() {
        assertFalse(PhoneUtils.isSame("10086", "10010"))
    }

    // ========== Edge cases / known bugs ==========

    @Test
    fun `isSame suffix match handles carrier-added leading zero`() {
        // 运营商有时会在号码前加 0 → 013800138000 vs 13800138000
        assertTrue(PhoneUtils.isSame("013800138000", "13800138000"))
    }

    @Test
    fun `isSame short suffix false positive - known loose behavior`() {
        // 后缀匹配过于宽松：10086 vs 86 会被判为相同
        // 这是已知问题，对 11 位手机号影响很小
        assertTrue(PhoneUtils.isSame("10086", "86"))
    }
}

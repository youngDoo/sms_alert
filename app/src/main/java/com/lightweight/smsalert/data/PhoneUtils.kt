package com.lightweight.smsalert.data

/**
 * 电话号码归一化与匹配工具（纯函数，不依赖 Android SDK，可直接单元测试）
 */
object PhoneUtils {

    /**
     * 归一化电话号码：去空格、去横线、去 +86/86 前缀
     */
    fun normalize(phone: String): String {
        var clean = phone.replace("\\s".toRegex(), "").replace("-", "")
        if (clean.startsWith("+86")) {
            clean = clean.substring(3)
        } else if (clean.startsWith("86") && clean.length > 10) {
            clean = clean.substring(2)
        }
        return clean
    }

    /**
     * 判断两个号码是否相同（归一化后精确匹配 + 后缀兜底匹配）
     * 后缀匹配用于处理运营商可能添加前导 0 的场景
     */
    fun isSame(p1: String, p2: String): Boolean {
        val n1 = normalize(p1)
        val n2 = normalize(p2)
        return n1 == n2 || n1.endsWith(n2) || n2.endsWith(n1)
    }
}

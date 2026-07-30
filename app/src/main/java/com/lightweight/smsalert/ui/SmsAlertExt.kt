package com.lightweight.smsalert.ui

import android.content.Context

/** dp → px 转换，使用指定 Context 的 DisplayMetrics（比 Resources.getSystem() 更准确） */
fun Context.dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

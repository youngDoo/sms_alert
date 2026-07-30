package com.lightweight.smsalert.ui

import android.content.res.Resources

/** 像素 → dp 转换工具 */
fun Int.dp(): Int = (this * Resources.getSystem().displayMetrics.density).toInt()

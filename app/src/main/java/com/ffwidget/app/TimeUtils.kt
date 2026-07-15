package com.ffwidget.app

import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone

object TimeUtils {

    // ForexFactory 日期带时区偏移（如 2026-07-14T08:30:00-04:00）。
    // Android 6.0（API 23）的 SimpleDateFormat 不支持 X/XXX，所以先去掉时区冒号，再用 Z 解析。
    private val tzColon = Regex("([+-]\\d{2}):(\\d{2})$")
    private val parseFmt = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssZ", Locale.US)

    // 美国东部时区（ForexFactory 原始时区）
    private val ET = TimeZone.getTimeZone("America/New_York")

    // 条目内时间：HH:mm（美东）
    private val timeFmt = SimpleDateFormat("HH:mm", Locale.US).apply { timeZone = ET }

    // 按天分组用的日键：yyyy-MM-dd（美东）
    private val dayKeyFmt = SimpleDateFormat("yyyy-MM-dd", Locale.US).apply { timeZone = ET }

    // 分隔条文字：中文周几 + MM-dd（美东），如「周一 07-13」
    private val dayLabelFmt = SimpleDateFormat("EEE MM-dd", Locale.CHINA).apply { timeZone = ET }

    private fun normalize(iso: String): String {
        return tzColon.replace(iso) { "${it.groupValues[1]}${it.groupValues[2]}" }
    }

    private fun parse(iso: String): Date? {
        return try {
            parseFmt.parse(normalize(iso))
        } catch (e: Exception) {
            null
        }
    }

    fun toET(iso: String): String {
        val d = parse(iso)
        return if (d == null) "" else timeFmt.format(d)
    }

    fun dayKey(iso: String): String {
        val d = parse(iso)
        return if (d == null) "" else dayKeyFmt.format(d)
    }

    fun dayLabel(iso: String): String {
        val d = parse(iso)
        return if (d == null) "" else dayLabelFmt.format(d)
    }

    // 今天（美国东部时区）的日期键 yyyy-MM-dd，用于判断「今日是否有重大新闻」
    fun todayETKey(): String {
        val cal = Calendar.getInstance(ET)
        return dayKeyFmt.format(cal.time)
    }

    fun updatedAgo(ts: Long): String {
        if (ts == 0L) return "尚未更新"
        val min = (System.currentTimeMillis() - ts) / 60000
        return if (min < 1) "刚刚更新" else "$min 分钟前更新"
    }
}

package com.ffwidget.app

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

object FFRepository {

    // ForexFactory 官方每周导出端点（已限定本周）。主数据源。
    private const val ENDPOINT = "https://nfs.faireconomy.media/ff_calendar_thisweek.json"

    // 镜像1：jsDelivr（GitHub 文件，国内相对可达）
    private const val MIRROR_JSDELIVR =
        "https://cdn.jsdelivr.net/gh/20101214am/ForexFactoryWidgetapp@main/ff_data.json"

    // 镜像2：GitHub 原始文件（有时可达）
    private const val MIRROR =
        "https://raw.githubusercontent.com/20101214am/ForexFactoryWidgetapp/main/ff_data.json"

    private const val PREFS = "ff_cache"
    private const val KEY_EVENTS = "events_json"
    private const val KEY_UPDATED = "updated_ts"
    private const val KEY_STATUS = "fetch_status"   // ok / fail
    private const val KEY_SOURCE = "fetch_source"   // net / offline

    // 拉取 -> 解析 -> 仅保留 High + Holiday -> 按时间排序 -> 写入缓存
    // 顺序：网络（主源+两个镜像）优先，全部失败再试内置离线数据（assets/ff_data.json）。
    // 任何一个候选能解析成功即视为成功，保证国内连不上外网时也能显示内置数据。
    fun refresh(context: Context): Boolean {
        val net = download()
        val order = mutableListOf<String>()
        if (net != null) order.add(net)            // 网络数据优先（更鲜）
        loadBundled(context)?.let { order.add(it) } // 内置离线兜底
        for (raw in order) {
            val source = if (raw === net) "net" else "offline"
            if (cacheParsed(context, raw, source)) return true
        }
        prefs(context).edit().putString(KEY_STATUS, "fail").apply()
        return false
    }

    // 首次确保有数据可显示：仅当缓存为空时用内置数据填充（<1ms，杜绝「一直加载中」）
    fun ensureBaseline(context: Context) {
        if (loadCached(context).isNotEmpty()) return
        val raw = loadBundled(context) ?: return
        cacheParsed(context, raw, "offline")
    }

    // 返回首个成功拿到的原始 JSON；全部失败返回 null
    private fun download(): String? {
        val errors = mutableListOf<String>()
        for (url in listOf(ENDPOINT, MIRROR_JSDELIVR, MIRROR)) {
            try {
                return fetchFrom(url)
            } catch (e: Exception) {
                errors.add("$url -> ${e.message}")
            }
        }
        Log.e("FFRepo", "all endpoints failed: ${errors.joinToString(" | ")}")
        return null
    }

    private fun fetchFrom(urlStr: String): String {
        val conn = URL(urlStr).openConnection() as HttpURLConnection
        conn.connectTimeout = 5000
        conn.readTimeout = 5000
        conn.requestMethod = "GET"
        conn.setRequestProperty("User-Agent", "Mozilla/5.0")
        try {
            if (conn.responseCode != 200) throw RuntimeException("HTTP ${conn.responseCode}")
            return conn.inputStream.bufferedReader().use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }

    // 读取内置离线数据（打包进 APK 的 assets/ff_data.json）
    private fun loadBundled(context: Context): String? {
        return try {
            context.assets.open("ff_data.json").bufferedReader().use { it.readText() }
        } catch (e: Exception) {
            null
        }
    }

    private fun cacheParsed(context: Context, raw: String, source: String): Boolean {
        return try {
            val events = parse(raw)
            val arr = JSONArray()
            for (e in events) {
                arr.put(JSONObject().apply {
                    put("title", e.title)
                    put("country", e.country)
                    put("date", e.dateIso)
                    put("impact", e.impact)
                    put("forecast", e.forecast)
                    put("previous", e.previous)
                })
            }
            prefs(context).edit().apply {
                putString(KEY_EVENTS, arr.toString())
                putLong(KEY_UPDATED, System.currentTimeMillis())
                putString(KEY_STATUS, "ok")
                putString(KEY_SOURCE, source)
                apply()
            }
            true
        } catch (e: Exception) {
            Log.e("FFRepo", "parse failed ($source): ${e.message}")
            false
        }
    }

    private fun parse(json: String): List<CalEvent> {
        val arr = JSONArray(json)
        val out = mutableListOf<CalEvent>()
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            val impact = o.optString("impact", "")
            if (impact != "High" && impact != "Holiday") continue
            out.add(
                CalEvent(
                    title = o.optString("title", ""),
                    country = o.optString("country", ""),
                    dateIso = o.optString("date", ""),
                    impact = impact,
                    forecast = o.optString("forecast", ""),
                    previous = o.optString("previous", "")
                )
            )
        }
        // 用 TimeUtils 统一解析，正确处理 -04:00 这类带冒号时区
        out.sortBy { TimeUtils.toDate(it.dateIso)?.time ?: Long.MAX_VALUE }
        return out
    }

    fun loadCached(context: Context): List<CalEvent> {
        return try {
            val s = prefs(context).getString(KEY_EVENTS, null) ?: return emptyList()
            val arr = JSONArray(s)
            val out = mutableListOf<CalEvent>()
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                out.add(
                    CalEvent(
                        o.optString("title", ""),
                        o.optString("country", ""),
                        o.optString("date", ""),
                        o.optString("impact", ""),
                        o.optString("forecast", ""),
                        o.optString("previous", "")
                    )
                )
            }
            out
        } catch (e: Exception) {
            // 缓存损坏（如旧版本写入的不同格式）时清空，让 ensureBaseline 重建，绝不让 onUpdate 崩溃
            Log.e("FFRepo", "loadCached failed, clearing: ${e.message}")
            prefs(context).edit().remove(KEY_EVENTS).apply()
            emptyList()
        }
    }

    fun lastUpdated(context: Context): Long = prefs(context).getLong(KEY_UPDATED, 0)

    // 数据来源：net=网络，offline=内置离线
    fun source(context: Context): String = prefs(context).getString(KEY_SOURCE, "net") ?: "net"

    // 是否曾尝试拉取但失败（用于显示「加载失败」而不是一直「加载中」）
    fun lastFailed(context: Context): Boolean =
        prefs(context).getString(KEY_STATUS, "") == "fail" && lastUpdated(context) == 0L

    private fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}

package com.ffwidget.app

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Locale

object FFRepository {

    // ForexFactory 官方每周导出端点（已限定本周）。主数据源。
    private const val ENDPOINT = "https://nfs.faireconomy.media/ff_calendar_thisweek.json"

    // 镜像兜底：数据每周由 GitHub Actions 抓取并提交到仓库，国内手机通常能稳定访问。
    // 若你的仓库名/分支不同，改这一行即可。
    private const val MIRROR =
        "https://raw.githubusercontent.com/20101214am/ForexFactoryWidgetapp/main/ff_data.json"

    private const val PREFS = "ff_cache"
    private const val KEY_EVENTS = "events_json"
    private const val KEY_UPDATED = "updated_ts"
    private const val KEY_STATUS = "fetch_status"   // ok / fail

    // 拉取 -> 解析 -> 仅保留 High + Holiday -> 按时间排序 -> 写入缓存
    // 先试主源，失败再试镜像。
    fun refresh(context: Context): Boolean {
        val raw = download() ?: return false
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
                apply()
            }
            true
        } catch (e: Exception) {
            Log.e("FFRepo", "parse failed: ${e.message}")
            prefs(context).edit().putString(KEY_STATUS, "fail").apply()
            false
        }
    }

    // 返回首个成功拿到的原始 JSON；全部失败返回 null
    private fun download(): String? {
        val errors = mutableListOf<String>()
        for (url in listOf(ENDPOINT, MIRROR)) {
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
        conn.connectTimeout = 10000
        conn.readTimeout = 10000
        conn.requestMethod = "GET"
        conn.setRequestProperty("User-Agent", "Mozilla/5.0")
        try {
            if (conn.responseCode != 200) throw RuntimeException("HTTP ${conn.responseCode}")
            return conn.inputStream.bufferedReader().use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }

    private fun parse(json: String): List<CalEvent> {
        val arr = JSONArray(json)
        val out = mutableListOf<CalEvent>()
        val fmt = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssX", Locale.US)
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
        out.sortBy { fmt.parse(it.dateIso)?.time ?: Long.MAX_VALUE }
        return out
    }

    fun loadCached(context: Context): List<CalEvent> {
        val s = prefs(context).getString(KEY_EVENTS, null) ?: return emptyList()
        val arr = JSONArray(s)
        val out = mutableListOf<CalEvent>()
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            out.add(
                CalEvent(
                    o.getString("title"),
                    o.getString("country"),
                    o.getString("date"),
                    o.getString("impact"),
                    o.optString("forecast"),
                    o.optString("previous")
                )
            )
        }
        return out
    }

    fun lastUpdated(context: Context): Long = prefs(context).getLong(KEY_UPDATED, 0)

    // 是否曾尝试拉取但失败（用于显示「加载失败」而不是一直「加载中」）
    fun lastFailed(context: Context): Boolean =
        prefs(context).getString(KEY_STATUS, "") == "fail" && lastUpdated(context) == 0L

    private fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}

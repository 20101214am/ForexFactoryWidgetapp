package com.ffwidget.app

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.view.View
import android.widget.RemoteViews
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit

class FFWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(context: Context, mgr: AppWidgetManager, ids: IntArray) {
        ids.forEach { updateWidget(context, mgr, it) }
        scheduleRefresh(context)
    }

    override fun onEnabled(context: Context) {
        scheduleRefresh(context)
        WorkManager.getInstance(context)
            .enqueueUniqueWork(
                "ff-init", ExistingWorkPolicy.REPLACE,
                OneTimeWorkRequestBuilder<CalendarWorker>().build()
            )
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        when (intent.action) {
            ACTION_REFRESH -> WorkManager.getInstance(context)
                .enqueueUniqueWork(
                    "ff-refresh", ExistingWorkPolicy.REPLACE,
                    OneTimeWorkRequestBuilder<CalendarWorker>().build()
                )
            // 覆盖安装 / 升级后，自动刷新桌面上已有的小部件（避免旧「加载中」残留）
            Intent.ACTION_MY_PACKAGE_REPLACED -> updateAll(context)
        }
    }

    companion object {
        const val ACTION_REFRESH = "com.ffwidget.app.ACTION_REFRESH"
        private const val MAX_ROWS = 80

        private val MAJOR_KEYWORDS = listOf(
            "cpi", "nfp", "nonfarm", "non-farm", "fomc", "federal open"
        )

        private val COUNTRY3 = mapOf(
            "US" to "USA", "CA" to "CAD", "EU" to "EUR",
            "GB" to "GBR", "FR" to "FRA", "DE" to "DEU", "JP" to "JPN",
            "AU" to "AUS", "CN" to "CHN", "CH" to "CHE", "IT" to "ITA",
            "ES" to "ESP", "NZ" to "NZL", "ZA" to "ZAF", "BR" to "BRA",
            "MX" to "MEX", "IN" to "IND", "RU" to "RUS", "KR" to "KOR",
            "SE" to "SWE", "NO" to "NOR", "DK" to "DNK"
        )

        private fun country3(c: String): String = COUNTRY3[c.uppercase()] ?: c.uppercase()

        private fun hasMajorToday(events: List<CalEvent>): Boolean {
            val today = TimeUtils.todayETKey()
            return events.any { e ->
                TimeUtils.dayKey(e.dateIso) == today &&
                        MAJOR_KEYWORDS.any { kw -> e.title.contains(kw, ignoreCase = true) }
            }
        }

        // 把所有行拼成一段带换行的文本，用单个 TextView 一次性渲染。
        // 不使用 addView 嵌套 RemoteViews，规避国产 ROM / 旧版本的兼容性崩溃。
        private fun buildText(events: List<CalEvent>): String {
            return try {
                val sorted = events.sortedBy { TimeUtils.toDate(it.dateIso)?.time ?: Long.MAX_VALUE }
                val sb = StringBuilder()
                var lastDay = ""
                var count = 0
                for (e in sorted) {
                    if (count >= MAX_ROWS) {
                        sb.append("\n… 还有更多，请把小部件拉高")
                        break
                    }
                    val day = TimeUtils.dayKey(e.dateIso)
                    if (day.isNotEmpty() && day != lastDay) {
                        sb.append("\n").append(TimeUtils.dayLabel(e.dateIso)).append("\n")
                        lastDay = day
                    }
                    val time = if (e.impact == "Holiday") "全天" else (TimeUtils.toET(e.dateIso).ifBlank { "----" })
                    val tag = if (e.impact == "Holiday") "假期" else "●"
                    sb.append("  ").append(time).append("  ").append(country3(e.country))
                        .append("  [").append(tag).append("] ").append(e.title).append("\n")
                    count++
                }
                sb.toString().trimEnd()
            } catch (e: Exception) {
                "数据解析异常: ${e.message}"
            }
        }

        fun updateAll(context: Context) {
            val mgr = AppWidgetManager.getInstance(context)
            val ids = mgr.getAppWidgetIds(ComponentName(context, FFWidgetProvider::class.java))
            ids.forEach { updateWidget(context, mgr, it) }
        }

        fun updateWidget(context: Context, mgr: AppWidgetManager, id: Int) {
            try {
                updateWidgetInner(context, mgr, id)
            } catch (e: Exception) {
                // 任何异常都降级为可读错误，绝不让系统弹出「加载出现问题」
                try {
                    showError(context, mgr, id, "ERR: ${e.message}")
                } catch (_: Exception) {
                }
            }
        }

        private fun updateWidgetInner(context: Context, mgr: AppWidgetManager, id: Int) {
            val pkg = context.packageName
            val rv = RemoteViews(pkg, R.layout.widget_static)

            // 数据：缓存为空时用内置离线数据兜底（同步、即时，杜绝「一直加载中」）
            var events = FFRepository.loadCached(context)
            if (events.isEmpty()) {
                FFRepository.ensureBaseline(context)
                events = FFRepository.loadCached(context)
            }

            // 重大新闻红色警告横幅
            if (hasMajorToday(events)) {
                rv.setTextViewText(R.id.widget_banner, context.getString(R.string.banner_major))
                rv.setViewVisibility(R.id.widget_banner, View.VISIBLE)
            } else {
                rv.setViewVisibility(R.id.widget_banner, View.GONE)
            }

            // 刷新按钮
            val refresh = Intent(context, FFWidgetProvider::class.java).apply { action = ACTION_REFRESH }
            val refreshPi = PendingIntent.getBroadcast(
                context, 0, refresh,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            rv.setOnClickPendingIntent(R.id.widget_refresh, refreshPi)

            // 副标题：数据来源 / 更新时间
            val src = FFRepository.source(context)
            val ago = TimeUtils.updatedAgo(FFRepository.lastUpdated(context))
            rv.setTextViewText(R.id.widget_sub, if (src == "offline") "$ago · 离线内置" else ago)

            // 逐行文本（单 TextView）
            val text = if (events.isEmpty()) context.getString(R.string.widget_empty) else buildText(events)
            rv.setTextViewText(R.id.widget_content, text)

            mgr.updateAppWidget(id, rv)
        }

        private fun showError(context: Context, mgr: AppWidgetManager, id: Int, msg: String) {
            val pkg = context.packageName
            val rv = RemoteViews(pkg, R.layout.widget_static)
            rv.setTextViewText(R.id.widget_title, "FF小部件出错")
            rv.setViewVisibility(R.id.widget_banner, View.VISIBLE)
            rv.setTextViewText(R.id.widget_banner, "加载失败")
            rv.setTextViewText(R.id.widget_sub, msg.take(80))
            rv.setTextViewText(
                R.id.widget_content,
                "请长按删除本小部件，再从桌面「小部件」列表重新添加一次。"
            )
            mgr.updateAppWidget(id, rv)
        }

        private fun scheduleRefresh(context: Context) {
            val req = PeriodicWorkRequestBuilder<CalendarWorker>(1, TimeUnit.HOURS)
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build()
                )
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                "ff-periodic", ExistingPeriodicWorkPolicy.UPDATE, req
            )
        }
    }
}

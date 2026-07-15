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
        // 首次添加立即拉取一次
        val req = OneTimeWorkRequestBuilder<CalendarWorker>().build()
        WorkManager.getInstance(context)
            .enqueueUniqueWork("ff-init", ExistingWorkPolicy.REPLACE, req)
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        when (intent.action) {
            ACTION_REFRESH -> {
                val req = OneTimeWorkRequestBuilder<CalendarWorker>().build()
                WorkManager.getInstance(context)
                    .enqueueUniqueWork("ff-refresh", ExistingWorkPolicy.REPLACE, req)
            }
            // 覆盖安装 / 升级后，自动刷新桌面上已有的小部件（避免旧「加载中」残留）
            Intent.ACTION_MY_PACKAGE_REPLACED -> {
                updateAll(context)
            }
        }
    }

    companion object {
        const val ACTION_REFRESH = "com.ffwidget.app.ACTION_REFRESH"

        private val MAJOR_KEYWORDS = listOf(
            "cpi", "nfp", "nonfarm", "non-farm", "fomc", "federal open"
        )

        private sealed class Row {
            data class Header(val label: String) : Row()
            data class Item(val e: CalEvent) : Row()
        }

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

        private fun buildRows(events: List<CalEvent>): List<Row> {
            val sorted = events.sortedBy { TimeUtils.toDate(it.dateIso)?.time ?: Long.MAX_VALUE }
            val out = mutableListOf<Row>()
            var lastDay = ""
            for (e in sorted) {
                val day = TimeUtils.dayKey(e.dateIso)
                if (day.isNotEmpty() && day != lastDay) {
                    out.add(Row.Header(TimeUtils.dayLabel(e.dateIso)))
                    lastDay = day
                }
                out.add(Row.Item(e))
            }
            return out
        }

        fun updateAll(context: Context) {
            val mgr = AppWidgetManager.getInstance(context)
            val ids = mgr.getAppWidgetIds(ComponentName(context, FFWidgetProvider::class.java))
            ids.forEach { updateWidget(context, mgr, it) }
        }

        fun updateWidget(context: Context, mgr: AppWidgetManager, id: Int) {
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

            // 逐行渲染（静态 RemoteViews，不依赖 ListView，任何启动器都能显示）
            rv.removeAllViews(R.id.widget_rows)
            val rows = buildRows(events)
            if (rows.isEmpty()) {
                val empty = RemoteViews(pkg, R.layout.widget_empty_row)
                empty.setTextViewText(R.id.empty_text, context.getString(R.string.widget_empty))
                rv.addView(R.id.widget_rows, empty)
            } else {
                for (row in rows) {
                    val rowRv = when (row) {
                        is Row.Header -> {
                            val r = RemoteViews(pkg, R.layout.widget_header)
                            r.setTextViewText(R.id.header_text, row.label)
                            r
                        }
                        is Row.Item -> {
                            val e = row.e
                            val r = RemoteViews(pkg, R.layout.widget_item)
                            r.setTextViewText(
                                R.id.item_time,
                                if (e.impact == "Holiday") "全天" else TimeUtils.toET(e.dateIso)
                            )
                            r.setTextViewText(R.id.item_country, country3(e.country))
                            r.setTextViewText(R.id.item_title, e.title)
                            val color = if (e.impact == "Holiday") R.color.holiday else R.color.high
                            r.setInt(R.id.item_bar, "setBackgroundResource", color)
                            r
                        }
                    }
                    rv.addView(R.id.widget_rows, rowRv)
                }
            }

            mgr.updateAppWidget(id, rv)
        }

        fun scheduleRefresh(context: Context) {
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

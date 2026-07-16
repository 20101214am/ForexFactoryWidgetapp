package com.ffwidget.app

import android.provider.AlarmClock
import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.provider.AlarmClock.EXTRA_HOUR
import android.provider.AlarmClock.EXTRA_MINUTES
import android.provider.AlarmClock.EXTRA_MESSAGE
import android.util.TypedValue
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

        // 重大红色新闻关键词（CPI / NFP 等）。FOMC 不再整体算重大，需单独判断利率决议。
        private val MAJOR_KEYWORDS = listOf(
            "cpi", "nfp", "nonfarm", "non-farm"
        )
        // FOMC 利率决议才视为重大红色新闻；其余 FOMC（讲话 / 纪要 / 证词等）只是普通红色新闻
        private val FOMC_RATE_DECISION = listOf(
            "rate decision", "interest rate decision", "利率决议", "联邦基金利率"
        )

        val COUNTRY3 = mapOf(
            "US" to "USA", "CA" to "CAD", "EU" to "EUR",
            "GB" to "GBR", "FR" to "FRA", "DE" to "DEU", "JP" to "JPN",
            "AU" to "AUS", "CN" to "CHN", "CH" to "CHE", "IT" to "ITA",
            "ES" to "ESP", "NZ" to "NZL", "ZA" to "ZAF", "BR" to "BRA",
            "MX" to "MEX", "IN" to "IND", "RU" to "RUS", "KR" to "KOR",
            "SE" to "SWE", "NO" to "NOR", "DK" to "DNK"
        )

        private fun country3(c: String): String = COUNTRY3[c.uppercase()] ?: c.uppercase()

        // 对外暴露给 MainActivity 用
        fun country3Public(c: String): String = country3(c)

        private fun hasMajorToday(events: List<CalEvent>): Boolean {
            val today = TimeUtils.todayETKey()
            return events.any { e ->
                TimeUtils.dayKey(e.dateIso) == today && isMajorTitle(e.title)
            }
        }

        // 是否「重大红色新闻」：CPI/NFP 直接算；FOMC 仅利率决议算，其余 FOMC 不算
        private fun isMajorTitle(title: String): Boolean {
            if (MAJOR_KEYWORDS.any { title.contains(it, ignoreCase = true) }) return true
            val isFomc = title.contains("fomc", ignoreCase = true) ||
                    title.contains("federal open", ignoreCase = true)
            return isFomc && FOMC_RATE_DECISION.any { title.contains(it, ignoreCase = true) }
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

            // 点击小部件任意区域打开完整日历页（内容显示不全时可用）
            val openApp = Intent(context, MainActivity::class.java).apply { flags = Intent.FLAG_ACTIVITY_NEW_TASK }
            val openPi = PendingIntent.getActivity(
                context, 1, openApp,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            rv.setOnClickPendingIntent(R.id.widget_root, openPi)

            // 副标题：数据来源 / 更新时间
            val src = FFRepository.source(context)
            val ago = TimeUtils.updatedAgo(FFRepository.lastUpdated(context))
            rv.setTextViewText(R.id.widget_sub, if (src == "offline") "$ago · 离线内置" else ago)

            // 页脚：本周红色新闻与假期汇总说明（逐行与单 TextView 兜底两种路径都会显示）
            rv.setViewVisibility(R.id.widget_footer, View.VISIBLE)
            rv.setTextViewText(R.id.widget_footer, context.getString(R.string.footer_text))

            // 正常路径：逐行渲染（红色新闻行带可点击闹铃图标）
            try {
                buildRows(context, pkg, rv, events, openPi)
                rv.setViewVisibility(R.id.widget_content, View.GONE)
            } catch (e: Exception) {
                // 逐行失败则降级为单 TextView（兼容个别 ROM 对 addView 的异常）
                rv.setViewVisibility(R.id.widget_list, View.GONE)
                rv.setViewVisibility(R.id.widget_content, View.VISIBLE)
                val text = if (events.isEmpty()) context.getString(R.string.widget_empty) else buildText(events)
                rv.setTextViewText(R.id.widget_content, text)
            }

            mgr.updateAppWidget(id, rv)
        }

        private fun buildRows(
            context: Context,
            pkg: String,
            rv: RemoteViews,
            events: List<CalEvent>,
            openPi: PendingIntent
        ) {
            rv.removeAllViews(R.id.widget_list) // 先清空，避免国产 ROM 叠加旧视图导致内容显示两遍
            val sorted = events.sortedBy { TimeUtils.toDate(it.dateIso)?.time ?: Long.MAX_VALUE }
            var lastDay = ""
            var count = 0
            var alarmReq = 1000 // 每行闹铃 PendingIntent 必须唯一，否则会互相覆盖
            for (e in sorted) {
                if (count >= MAX_ROWS) break
                val day = TimeUtils.dayKey(e.dateIso)
                if (day.isNotEmpty() && day != lastDay) {
                    val h = RemoteViews(pkg, R.layout.widget_row)
                    h.setTextViewText(R.id.row_text, TimeUtils.dayLabel(e.dateIso))
                    h.setTextViewTextSize(R.id.row_text, TypedValue.COMPLEX_UNIT_SP, 13f)
                    h.setViewVisibility(R.id.row_alarm, View.GONE)
                    h.setViewVisibility(R.id.row_dot, View.GONE)
                    rv.addView(R.id.widget_list, h)
                    lastDay = day
                }
                val row = RemoteViews(pkg, R.layout.widget_row)
                val time = if (e.impact == "Holiday") "全天" else (TimeUtils.toET(e.dateIso).ifBlank { "----" })
                // 类型标记：假期=[⚪] 白色圆点，红色新闻=[🔴] 红色圆点（括号圆点放行中间，替代旧 [假期]/[红新]）
                val dot = if (e.impact == "Holiday") "⚪" else "🔴"
                row.setViewVisibility(R.id.row_dot, View.GONE) // 不再使用左侧独立圆点图标
                row.setTextViewText(R.id.row_text, "  $time  ${country3(e.country)}  [$dot] ${e.title}")

                if (e.impact == "High") {
                    row.setViewVisibility(R.id.row_alarm, View.VISIBLE)
                    val (h, m) = TimeUtils.toLocalHM(e.dateIso)
                    val alarmIntent = Intent(AlarmClock.ACTION_SET_ALARM).apply {
                        putExtra(EXTRA_HOUR, h)
                        putExtra(EXTRA_MINUTES, m)
                        putExtra(EXTRA_MESSAGE, "FF: ${e.title}")
                    }
                    val alarmPi = PendingIntent.getActivity(
                        context, alarmReq++, alarmIntent,
                        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                    )
                    row.setOnClickPendingIntent(R.id.row_alarm, alarmPi)
                } else {
                    row.setViewVisibility(R.id.row_alarm, View.GONE)
                }
                // 点击行内文字打开完整日历（闹铃图标点击优先于它）
                row.setOnClickPendingIntent(R.id.row_root, openPi)
                rv.addView(R.id.widget_list, row)
                count++
            }
        }

        // 单 TextView 兜底渲染（逐行路径异常时启用）
        private fun buildText(events: List<CalEvent>): String {
            return try {
                val sorted = events.sortedBy { TimeUtils.toDate(it.dateIso)?.time ?: Long.MAX_VALUE }
                val sb = StringBuilder()
                var lastDay = ""
                var count = 0
                for (e in sorted) {
                    if (count >= MAX_ROWS) {
                        sb.append("\n… 更多，点击小部件看完整")
                        break
                    }
                    val day = TimeUtils.dayKey(e.dateIso)
                    if (day.isNotEmpty() && day != lastDay) {
                        sb.append("\n").append(TimeUtils.dayLabel(e.dateIso)).append("\n")
                        lastDay = day
                    }
                    val time = if (e.impact == "Holiday") "全天" else (TimeUtils.toET(e.dateIso).ifBlank { "----" })
                    val dot = if (e.impact == "Holiday") "[⚪]" else "[🔴]"
                    sb.append("  ").append(time).append("  ").append(country3(e.country))
                        .append("  ").append(dot).append(" ").append(e.title).append("\n")
                    count++
                }
                sb.toString().trimEnd()
            } catch (e: Exception) {
                "数据解析异常: ${e.message}"
            }
        }

        private fun showError(context: Context, mgr: AppWidgetManager, id: Int, msg: String) {
            val pkg = context.packageName
            val rv = RemoteViews(pkg, R.layout.widget_static)
            rv.setTextViewText(R.id.widget_title, "FF小部件出错")
            rv.setViewVisibility(R.id.widget_banner, View.VISIBLE)
            rv.setTextViewText(R.id.widget_banner, "加载失败")
            rv.setTextViewText(R.id.widget_sub, msg.take(80))
            rv.setViewVisibility(R.id.widget_list, View.GONE)
            rv.setViewVisibility(R.id.widget_footer, View.GONE)
            rv.setViewVisibility(R.id.widget_content, View.VISIBLE)
            rv.setTextViewText(
                R.id.widget_content,
                "请长按删除本小部件，再从桌面「小部件」列表重新添加一次。"
            )
            mgr.updateAppWidget(id, rv)
        }

        private fun scheduleRefresh(context: Context) {
            // 本周日历数据无需实时更新：每 6 小时拉取一次足够覆盖 FF 的时间修订/临时新增，省电省流量
            val req = PeriodicWorkRequestBuilder<CalendarWorker>(6, TimeUnit.HOURS)
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

package com.ffwidget.app

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
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
        if (intent.action == ACTION_REFRESH) {
            val req = OneTimeWorkRequestBuilder<CalendarWorker>().build()
            WorkManager.getInstance(context)
                .enqueueUniqueWork("ff-refresh", ExistingWorkPolicy.REPLACE, req)
        }
    }

    companion object {
        const val ACTION_REFRESH = "com.ffwidget.app.ACTION_REFRESH"

        // 重大新闻关键词（美东今日命中其一即显示红色警告横幅）
        private val MAJOR_KEYWORDS = listOf(
            "cpi", "nfp", "nonfarm", "non-farm", "fomc", "federal open"
        )

        // 判断今日（美东）是否有 CPI / NFP / FOMC 等重大新闻
        private fun hasMajorToday(events: List<CalEvent>): Boolean {
            val today = TimeUtils.todayETKey()
            return events.any { e ->
                TimeUtils.dayKey(e.dateIso) == today &&
                MAJOR_KEYWORDS.any { kw -> e.title.contains(kw, ignoreCase = true) }
            }
        }

        fun updateAll(context: Context) {
            val mgr = AppWidgetManager.getInstance(context)
            val ids = mgr.getAppWidgetIds(ComponentName(context, FFWidgetProvider::class.java))
            ids.forEach { updateWidget(context, mgr, it) }
        }

        fun updateWidget(context: Context, mgr: AppWidgetManager, id: Int) {
            val rv = RemoteViews(context.packageName, R.layout.widget_layout)

            val serviceIntent = Intent(context, EventWidgetService::class.java).apply {
                putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id)
                data = Uri.parse(toUri(Intent.URI_INTENT_SCHEME))
            }
            rv.setRemoteAdapter(R.id.widget_list, serviceIntent)
            rv.setEmptyView(R.id.widget_list, R.id.widget_empty)

            // 点击条目 -> 打开 ForexFactory 日历页
            val open = Intent(Intent.ACTION_VIEW, Uri.parse("https://www.forexfactory.com/calendar"))
            val openPi = PendingIntent.getActivity(
                context, 0, open,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            rv.setPendingIntentTemplate(R.id.widget_list, openPi)

            // 重大新闻红色警告横幅
            val major = hasMajorToday(FFRepository.loadCached(context))
            if (major) {
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

            rv.setTextViewText(R.id.widget_sub, TimeUtils.updatedAgo(FFRepository.lastUpdated(context)))

            mgr.updateAppWidget(id, rv)
            mgr.notifyAppWidgetViewDataChanged(id, R.id.widget_list)
        }

        // 每小时刷新一次（ForexFactory 限制每 IP 每 5 分钟最多 2 次请求）
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

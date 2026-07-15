package com.ffwidget.app

import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import android.widget.RemoteViewsService

class EventRemoteViewsFactory(
    private val ctx: Context,
    private val intent: Intent
) : RemoteViewsService.RemoteViewsFactory {

    // 列表同时容纳「按天分隔条」与「事件行」
    private sealed class Row {
        data class Header(val label: String) : Row()
        data class Item(val e: CalEvent) : Row()
    }

    private var rows: List<Row> = emptyList()

    // 两字母国家代码 -> 三字母显示（US->USA、CA->CAD 按用户指定，其余标准 ISO alpha-3）
    private val COUNTRY3 = mapOf(
        "US" to "USA", "CA" to "CAD", "EU" to "EUR",
        "GB" to "GBR", "FR" to "FRA", "DE" to "DEU", "JP" to "JPN",
        "AU" to "AUS", "CN" to "CHN", "CH" to "CHE", "IT" to "ITA",
        "ES" to "ESP", "NZ" to "NZL", "ZA" to "ZAF", "BR" to "BRA",
        "MX" to "MEX", "IN" to "IND", "RU" to "RUS", "KR" to "KOR",
        "SE" to "SWE", "NO" to "NOR", "DK" to "DNK", "CA" to "CAD"
    )

    private fun country3(c: String): String = COUNTRY3[c.uppercase()] ?: c.uppercase()

    override fun onCreate() {}

    override fun onDataSetChanged() {
        // 立刻用内置离线数据填充，杜绝「一直加载中」
        if (FFRepository.loadCached(ctx).isEmpty()) {
            FFRepository.ensureBaseline(ctx)
        }
        buildRows(FFRepository.loadCached(ctx))
        // 后台异步刷新网络数据（不阻塞首次渲染；完成后会重新通知重建）
        Thread {
            FFRepository.refresh(ctx)
            FFWidgetProvider.updateAll(ctx)
        }.start()
    }

    private fun buildRows(events: List<CalEvent>) {
        val sorted = events.sortedBy { it.dateIso }
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
        rows = out
    }

    override fun onDestroy() {
        rows = emptyList()
    }

    override fun getCount(): Int = rows.size

    override fun getViewAt(position: Int): RemoteViews {
        return when (val row = rows[position]) {
            is Row.Header -> {
                val rv = RemoteViews(ctx.packageName, R.layout.widget_header)
                rv.setTextViewText(R.id.header_text, row.label)
                rv.setOnClickFillInIntent(R.id.header_root, Intent())
                rv
            }
            is Row.Item -> {
                val e = row.e
                val rv = RemoteViews(ctx.packageName, R.layout.widget_item)
                rv.setTextViewText(R.id.item_time, if (e.impact == "Holiday") "全天" else TimeUtils.toET(e.dateIso))
                rv.setTextViewText(R.id.item_country, country3(e.country))
                rv.setTextViewText(R.id.item_title, e.title)
                val color = if (e.impact == "Holiday") R.color.holiday else R.color.high
                rv.setInt(R.id.item_bar, "setBackgroundResource", color)
                rv.setOnClickFillInIntent(R.id.item_root, Intent())
                rv
            }
        }
    }

    override fun getLoadingView(): RemoteViews? = null

    override fun getViewTypeCount(): Int = 2

    override fun getItemId(position: Int): Long = position.toLong()

    override fun hasStableIds(): Boolean = true
}

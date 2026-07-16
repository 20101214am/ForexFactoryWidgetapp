package com.ffwidget.app

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.text.SpannableStringBuilder
import android.widget.Button
import android.widget.TextView

/**
 * 启动页：纯小组件应用没有入口界面，Android 8+ 会因此不把小部件列入桌面小部件列表。
 * 同时作为「完整日历查看页」，供用户点击桌面小部件后查看全部内容。
 */
class MainActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val tv = findViewById<TextView>(R.id.full_content)

        // 确保有数据（先尝试读缓存，没有就用内置离线）
        var events = FFRepository.loadCached(this)
        if (events.isEmpty()) {
            FFRepository.ensureBaseline(this)
            events = FFRepository.loadCached(this)
        }
        tv.text = if (events.isEmpty()) {
            getString(R.string.widget_empty)
        } else {
            buildFullText(events)
        }

        findViewById<Button>(R.id.btn_open).setOnClickListener {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://www.forexfactory.com/calendar"))
            startActivity(intent)
        }
    }

    private fun buildFullText(events: List<CalEvent>): CharSequence {
        val sorted = events.sortedBy { TimeUtils.toDate(it.dateIso)?.time ?: Long.MAX_VALUE }
        val sb = SpannableStringBuilder()
        var lastDay = ""
        for (e in sorted) {
            val day = TimeUtils.dayKey(e.dateIso)
            if (day.isNotEmpty() && day != lastDay) {
                sb.append("\n").append(TimeUtils.dayLabel(e.dateIso)).append("\n")
                lastDay = day
            }
            val time = if (e.impact == "Holiday") "全天" else (TimeUtils.toET(e.dateIso).ifBlank { "----" })
            val b = SpannableStringBuilder()
            b.append("  ").append(time).append("  ").append(country3(e.country)).append("  [")
            b.append(FFWidgetProvider.rowDot(e))
            b.append("] ").append(e.title).append("\n")
            sb.append(b)
        }
        sb.append("\n").append(getString(R.string.footer_text))
        return sb
    }

    private fun country3(c: String): String = FFWidgetProvider.country3Public(c)
}

package com.ffwidget.app

import android.content.Context
import androidx.work.Worker
import androidx.work.WorkerParameters

// 后台拉取 ForexFactory 数据并刷新所有小组件
class CalendarWorker(context: Context, params: WorkerParameters) : Worker(context, params) {
    override fun doWork(): Result {
        FFRepository.refresh(applicationContext)
        FFWidgetProvider.updateAll(applicationContext)
        return Result.success()
    }
}

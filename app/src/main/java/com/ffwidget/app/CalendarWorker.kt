package com.ffwidget.app

import android.content.Context
import androidx.work.Worker
import androidx.work.WorkerParameters

// 后台拉取 ForexFactory 数据并刷新所有小组件
class CalendarWorker(context: Context, params: WorkerParameters) : Worker(context, params) {
    override fun doWork(): Result {
        // 即便刷新失败（如断网），也用已有缓存/内置数据重绘，保证小部件一定刷新
        try {
            FFRepository.refresh(applicationContext)
        } catch (e: Exception) {
            // 忽略，交给下面的 updateAll 用缓存重绘
        }
        FFWidgetProvider.updateAll(applicationContext)
        return Result.success()
    }
}

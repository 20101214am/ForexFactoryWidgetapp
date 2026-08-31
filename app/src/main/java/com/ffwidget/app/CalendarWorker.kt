package com.ffwidget.app

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import androidx.work.Worker
import androidx.work.WorkerParameters

// 后台拉取 ForexFactory 数据并刷新所有小组件
class CalendarWorker(context: Context, params: WorkerParameters) : Worker(context, params) {
    override fun doWork(): Result {
        // 即便刷新失败（如断网），也用已有缓存/内置数据重绘，保证小部件一定刷新
        try {
            // 仅在确有可用网络时尝试拉取，避免无网时白白超时等待
            if (isNetworkAvailable(applicationContext)) {
                FFRepository.refresh(applicationContext)
            }
        } catch (e: Exception) {
            // 忽略，交给下面的 updateAll 用缓存重绘
        }
        FFWidgetProvider.updateAll(applicationContext)
        return Result.success()
    }

    private fun isNetworkAvailable(context: Context): Boolean {
        return try {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val active = cm.activeNetwork ?: return false
            val caps = cm.getNetworkCapabilities(active) ?: return false
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        } catch (_: Exception) {
            true // 拿不到连接状态时保守地尝试拉取
        }
    }
}

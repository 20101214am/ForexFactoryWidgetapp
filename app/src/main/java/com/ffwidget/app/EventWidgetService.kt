package com.ffwidget.app

import android.content.Intent
import android.widget.RemoteViewsService

class EventWidgetService : RemoteViewsService() {
    override fun onGetViewFactory(intent: Intent): RemoteViewsFactory {
        return EventRemoteViewsFactory(applicationContext, intent)
    }
}

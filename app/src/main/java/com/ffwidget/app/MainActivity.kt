package com.ffwidget.app

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Button

/**
 * 启动页：纯小组件应用没有入口界面，Android 8+ 会因此不把小部件列入桌面小部件列表。
 * 加上这个 Activity 后，用户首次打开 App（点图标）即可激活应用，
 * 之后小部件就会出现在桌面小部件列表里。
 */
class MainActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        findViewById<Button>(R.id.btn_open).setOnClickListener {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://www.forexfactory.com/calendar"))
            startActivity(intent)
        }
    }
}

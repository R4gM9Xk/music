package com.local.music

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.local.music.crash.CrashHandler
import com.local.music.ui.GlassApp

/** 唯一 Activity：液态玻璃 UI 宿主 */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        // 崩溃采集必须在任何 UI 之前装好：越早装，能捕获的启动期崩溃越多
        CrashHandler.install(this)
        super.onCreate(savedInstanceState)
        enableEdgeToEdge() // 沉浸式，极光铺满全屏
        setContent { GlassApp() }
    }
}

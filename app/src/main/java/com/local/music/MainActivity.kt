package com.local.music

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.local.music.ui.GlassApp

/** 唯一 Activity：液态玻璃 UI 宿主 */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge() // 沉浸式，极光铺满全屏
        setContent { GlassApp() }
    }
}

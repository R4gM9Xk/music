package com.local.music.player

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings

/**
 * 自启动引导（E2）：ColorOS / 国产 ROM 的激进省电会杀后台播放，
 * 用户需要手动把应用加入「自启动 / 后台运行白名单」，否则息屏连播会被中断。
 *
 * 这些设置页都是厂商私有 Activity，action 名不稳定、跨版本会变——
 * 所以采用「候选列表逐个尝试 + 失败兜底到应用详情页」的策略：
 * 任一候选能解析就跳转，全失败则跳系统应用详情页（用户仍能从那里进电池设置）。
 */
object AutoStartGuide {

    /** 是否需要提示（仅国产激进省电 ROM；其余系统无此概念，提示反而干扰） */
    fun shouldSuggest(): Boolean {
        val brand = Build.BRAND.orEmpty().lowercase()
        val manufacturer = Build.MANUFACTURER.orEmpty().lowercase()
        return listOf("oppo", "oneplus", "realme", "vivo", "iqoo", "xiaomi", "redmi", "huawei", "honor", "meizu")
            .any { brand.contains(it) || manufacturer.contains(it) }
    }

    /** 是否为 ColorOS / ColorOS 衍生（OPPO/OnePlus/realme）：主推自启动管理页 */
    fun isColorOsFamily(): Boolean {
        val brand = Build.BRAND.orEmpty().lowercase()
        val manufacturer = Build.MANUFACTURER.orEmpty().lowercase()
        return listOf("oppo", "oneplus", "realme").any { brand.contains(it) || manufacturer.contains(it) }
    }

    /**
     * 候选设置页（按优先级）。每项 = 组件全名，逐个尝试解析。
     * 覆盖 ColorOS 各版本已知入口 + 通用电池优化页。
     */
    private val CANDIDATES: List<ComponentName> = listOf(
        // ColorOS 自启动管理（多版本）
        ComponentName(
            "com.coloros.safecenter",
            "com.coloros.safecenter.permission.startup.StartupAppListActivity",
        ),
        ComponentName(
            "com.coloros.safecenter",
            "com.coloros.safecenter.startupapp.StartupAppListActivity",
        ),
        ComponentName(
            "com.oplus.safecenter",
            "com.oplus.safecenter.permission.startup.StartupAppListActivity",
        ),
        // 电池 / 耗电管理
        ComponentName(
            "com.coloros.oppoguardelf",
            "com.coloros.powermanager.fuelgaue.PowerUsageModelActivity",
        ),
        ComponentName(
            "com.oplus.battery",
            "com.oplus.powermanager.fuelgaue.PowerUsageModelActivity",
        ),
        // 通用：应用详情页（所有 ROM 都有，作为最终兜底前的尝试）
        ComponentName(
            "com.android.settings",
            "com.android.settings.applications.InstalledAppDetails",
        ),
    )

    /**
     * 跳转到自启动/电池设置。
     * 返回 true = 已跳转成功；false = 全部失败（调用方应提示用户手动前往）。
     */
    fun open(context: Context): Boolean {
        // 1) 厂商私有 Activity（需带 package 参数才落在本应用详情）
        for (component in CANDIDATES) {
            val intent = Intent().apply {
                this.component = component
                // InstalledAppDetails 需要 package 参数；其余厂商页忽略多余参数
                putExtra("package", context.packageName)
                putExtra("packageName", context.packageName)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            if (intent.resolveActivity(context.packageManager) != null) {
                val ok = runCatching { context.startActivity(intent) }.isSuccess
                if (ok) return true
            }
        }
        // 2) 系统电池优化页（部分 ROM 有此页，用户可自行找应用）
        val batteryIntent = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (batteryIntent.resolveActivity(context.packageManager) != null) {
            if (runCatching { context.startActivity(batteryIntent) }.isSuccess) return true
        }
        // 3) 最终兜底：本应用系统详情页（一定能开）
        return runCatching {
            context.startActivity(
                Intent(
                    Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.fromParts("package", context.packageName, null),
                ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }.isSuccess
    }

    /** 引导文案（设置页副标题 / 首次提示条共用） */
    fun subtitle(): String = if (isColorOsFamily()) {
        "ColorOS 省电会中断后台播放，建议加入自启动白名单"
    } else {
        "系统省电可能中断后台播放，建议加入后台白名单"
    }
}

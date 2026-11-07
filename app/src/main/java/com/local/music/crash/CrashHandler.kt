package com.local.music.crash

import android.content.Context
import android.util.Log
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 极简崩溃采集：把未捕获异常写到 `filesDir/crash/crash-<时间戳>.txt`。
 *
 * 为什么不用 Crashlytics / Sentry：
 * - 本项目 `allowBackup=false`、自更新走 GitHub、无隐私政策，接第三方 SDK 反而增加合规负担；
 * - 需要的只是「用户能给你发一份证据」，落盘 + 手动导出就够了。
 *
 * 设计约束：
 * 1. **必须转交原 handler**：只记录不吞，否则系统认为异常被处理，进程不会正常崩溃重启，
 *    会掩盖真实问题（例如 ANR 边界行为）。
 * 2. **保留最近 [MAX_FILES] 份**：循环崩溃时不能无限占盘。按文件名时间戳排序，删最旧的。
 * 3. **写盘失败绝不能再抛**：崩溃路径上任何二次异常都会变成「崩溃时又崩一次」，
 *    用户看到的是更莫名的现象。全程 runCatching 兜住。
 * 4. **不记录任何用户数据**：只有异常栈 + 设备/版本信息，不采集曲库、路径、账号。
 */
object CrashHandler {

    private const val TAG = "CrashHandler"
    private const val DIR = "crash"
    private const val MAX_FILES = 5
    private const val PREFIX = "crash-"
    private const val SUFFIX = ".txt"

    /** 安装到当前进程。重复调用安全（第二次会保留第一次装好的链路）。 */
    fun install(context: Context) {
        val appContext = context.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        if (previous is CrashHandler.Handler) return // 已装过

        Thread.setDefaultUncaughtExceptionHandler(Handler(appContext, previous))
    }

    /** 崩溃日志目录；不存在时创建。 */
    fun dir(context: Context): File = File(context.filesDir, DIR)

    /**
     * 列出全部崩溃日志，**新的在前**（文件名时间戳倒序）。
     * 无目录 / 读取失败返回空表，调用方无需判空。
     */
    fun list(context: Context): List<File> = runCatching {
        dir(context).listFiles { f -> f.isFile && f.name.startsWith(PREFIX) && f.name.endsWith(SUFFIX) }
            ?.sortedByDescending { it.name }
            ?: emptyList()
    }.getOrElse { emptyList() }

    /** 打包全部日志为单个文本（导出时用：一个文件比多个文件好发给开发者）。 */
    fun bundle(context: Context): String? {
        val files = list(context)
        if (files.isEmpty()) return null
        return runCatching {
            buildString {
                appendLine("# ColorOS 音乐崩溃日志合集")
                appendLine("# 生成时间：${timestamp()}")
                appendLine("# 共 ${files.size} 份")
                files.forEach { f ->
                    appendLine()
                    appendLine("=".repeat(72))
                    appendLine("## ${f.name}")
                    appendLine("=".repeat(72))
                    appendLine(f.readText())
                }
            }
        }.getOrNull()
    }

    /** 清空全部日志（用户隐私：导出后可自选删除）。 */
    fun clear(context: Context): Int = runCatching {
        val files = list(context)
        files.count { it.delete() }
    }.getOrElse { 0 }

    private class Handler(
        private val context: Context,
        private val previous: Thread.UncaughtExceptionHandler?,
    ) : Thread.UncaughtExceptionHandler {
        override fun uncaughtException(thread: Thread, throwable: Throwable) {
            // 先记录：写盘失败也绝不能影响后续转交
            runCatching { write(thread, throwable) }
                .onFailure { Log.w(TAG, "写崩溃日志失败", it) }
            // 再转交：保持系统默认崩溃行为（进程终止、系统统计、下次启动的「应用已停止」）
            previous?.uncaughtException(thread, throwable)
        }

        private fun write(thread: Thread, throwable: Throwable) {
            val dir = dir(context)
            if (!dir.exists() && !dir.mkdirs()) return

            val name = PREFIX + SimpleDateFormat("yyyyMMdd-HHmmss-SSS", Locale.US).format(Date()) + SUFFIX
            val text = buildString {
                appendLine("时间：${timestamp()}")
                appendLine("线程：${thread.name}（id=${thread.id}）")
                appendLine("设备：${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}")
                appendLine("系统：Android ${android.os.Build.VERSION.RELEASE}（API ${android.os.Build.VERSION.SDK_INT}）")
                appendLine("应用：${appVersion()}")
                appendLine()
                appendLine("--- 异常栈 ---")
                val sw = StringWriter()
                PrintWriter(sw).use { throwable.printStackTrace(it) }
                append(sw.toString())
            }

            File(dir, name).writeText(text)

            // 只留最近 MAX_FILES 份：list() 已按新→旧排序，drop 头部保留即可
            list(context).drop(MAX_FILES).forEach { runCatching { it.delete() } }
        }

        private fun appVersion(): String = runCatching {
            val pm = context.packageManager
            @Suppress("DEPRECATION")
            val info = pm.getPackageInfo(context.packageName, 0)
            "${info.versionName}（${info.versionCode}）/${context.packageName}"
        }.getOrElse { "unknown" }
    }

    private fun timestamp(): String =
        SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
}

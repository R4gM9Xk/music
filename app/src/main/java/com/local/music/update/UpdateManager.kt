package com.local.music.update

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.FileProvider
import com.local.music.feature.settings.screen.UpdateUiState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * 应用内更新（GitHub 仓库 git 分发，无 API 依赖）：
 * - 清单 update.json 与 APK 均为仓库内 git 文件，发版 = git push（release.sh）
 * - 清单获取三链回退：gh-proxy → raw 直连 → jsDelivr（国内可达性优先）
 * - APK 下载按清单给出的地址逐个尝试，边下边算 sha256 校验
 * - 下载完成自动拉起系统安装器；未授权"安装未知应用"时先跳授权页
 */
class UpdateManager(
    private val context: Context,
    private val scope: CoroutineScope,
) {
    var state by mutableStateOf(UpdateUiState())
        private set

    private var pendingInfo: UpdateInfo? = null
    private val apkFile: File get() = File(context.filesDir, "update/app-update.apk")

    /** 手动检查（设置页点击；失败显示原因） */
    fun check() {
        scope.launch {
            state = UpdateUiState(status = UpdateUiState.Status.Checking)
            val info = fetchManifest()
            state = when {
                info == null -> UpdateUiState(status = UpdateUiState.Status.Failed, message = "无法连接更新源")
                info.versionCode <= currentVersionCode() ->
                    UpdateUiState(status = UpdateUiState.Status.UpToDate)
                else -> UpdateUiState(
                    status = UpdateUiState.Status.Available,
                    versionName = info.versionName,
                    changelog = info.changelog,
                ).also { pendingInfo = info }
            }
        }
    }

    /** 启动静默检查：失败无声、有新版仅点亮设置行 */
    fun checkSilently() {
        scope.launch {
            val info = fetchManifest() ?: return@launch
            if (info.versionCode > currentVersionCode() &&
                state.status == UpdateUiState.Status.Idle
            ) {
                pendingInfo = info
                state = UpdateUiState(
                    status = UpdateUiState.Status.Available,
                    versionName = info.versionName,
                    changelog = info.changelog,
                )
            }
        }
    }

    /** 一键更新：下载 → 校验 → 自动拉起安装器 */
    fun startUpdate() {
        val info = pendingInfo ?: return
        scope.launch {
            state = UpdateUiState(
                status = UpdateUiState.Status.Downloading,
                versionName = info.versionName,
                changelog = info.changelog,
                progress = 0f,
            )
            val downloaded = withContext(Dispatchers.IO) { downloadApk(info) }
            when {
                downloaded != null -> {
                    state = state.copy(status = UpdateUiState.Status.ReadyToInstall)
                    install()
                }
                else -> state = state.copy(
                    status = UpdateUiState.Status.Failed,
                    message = "下载失败，请重试",
                )
            }
        }
    }

    /** 拉起系统安装器（已下载即可反复调用）；未授权时跳授权页 */
    fun install() {
        if (!apkFile.isFile) return
        if (!context.packageManager.canRequestPackageInstalls()) {
            context.startActivity(
                Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
            return
        }
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", apkFile)
        context.startActivity(
            Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION),
        )
    }

    // ── 内部实现 ──────────────────────────────────────────

    private data class UpdateInfo(
        val versionCode: Int,
        val versionName: String,
        val changelog: String?,
        val apkUrls: List<String>,
        val sha256: String?,
    )

    private fun currentVersionCode(): Int = try {
        context.packageManager.getPackageInfo(context.packageName, 0).longVersionCode.toInt()
    } catch (e: Exception) {
        0
    }

    /** 清单三链回退，单链 5s 超时；任一成功即返回 */
    private suspend fun fetchManifest(): UpdateInfo? = withContext(Dispatchers.IO) {
        MANIFEST_URLS.firstNotNullOfOrNull { url ->
            runCatching {
                val body = httpGet(url, timeoutMs = 5_000) ?: return@runCatching null
                parseManifest(JSONObject(body))
            }.getOrNull()
        }
    }

    private fun parseManifest(o: JSONObject): UpdateInfo? {
        val code = o.optInt("versionCode", 0)
        if (code <= 0) return null
        val urls = listOfNotNull(
            o.optString("apk").ifEmpty { null },
            o.optString("apkMirror").ifEmpty { null },
            o.optString("apkAlt").ifEmpty { null },
        )
        if (urls.isEmpty()) return null
        return UpdateInfo(
            versionCode = code,
            versionName = o.optString("versionName", code.toString()),
            changelog = o.optString("changelog").ifEmpty { null },
            apkUrls = urls,
            sha256 = o.optString("sha256").ifEmpty { null }?.lowercase(),
        )
    }

    /** 按清单地址逐个尝试下载；成功时校验 sha256（清单给出时） */
    private fun downloadApk(info: UpdateInfo): File? {
        apkFile.parentFile?.mkdirs()
        val tmp = File(apkFile.parentFile, "app-update.tmp")
        for (url in info.apkUrls) {
            val ok = runCatching {
                downloadTo(url, tmp) { read, total -> updateProgress(read, total) }
                val actual = sha256Of(tmp)
                if (info.sha256 != null && actual != info.sha256) {
                    tmp.delete()
                    return@runCatching false // 哈希不符：换下一个源
                }
                tmp.renameTo(apkFile) || (apkFile.delete() && tmp.renameTo(apkFile))
            }.getOrDefault(false)
            if (ok) return apkFile
        }
        tmp.delete()
        return null
    }

    private fun updateProgress(read: Long, total: Long) {
        if (total > 0) {
            val p = (read.toFloat() / total).coerceIn(0f, 1f)
            if (state.status == UpdateUiState.Status.Downloading) {
                state = state.copy(progress = p)
            }
        }
    }

    private fun httpGet(url: String, timeoutMs: Int): String? = runCatching {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = timeoutMs
        conn.readTimeout = timeoutMs
        conn.instanceFollowRedirects = true
        conn.setRequestProperty("User-Agent", "local-music-updater")
        try {
            if (conn.responseCode !in 200..299) return@runCatching null
            conn.inputStream.bufferedReader().use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }.getOrNull()

    private fun downloadTo(url: String, target: File, onProgress: (Long, Long) -> Unit): Boolean =
        runCatching {
            val conn = URL(url).openConnection() as HttpURLConnection
            conn.connectTimeout = 10_000
            conn.readTimeout = 30_000
            conn.instanceFollowRedirects = true
            conn.setRequestProperty("User-Agent", "local-music-updater")
            try {
                if (conn.responseCode !in 200..299) return@runCatching false
                val total = conn.contentLengthLong
                target.outputStream().use { out ->
                    conn.inputStream.use { input ->
                        val buf = ByteArray(64 * 1024)
                        var read = 0L
                        while (true) {
                            val n = input.read(buf)
                            if (n < 0) break
                            out.write(buf, 0, n)
                            read += n
                            onProgress(read, total)
                        }
                    }
                }
                target.length() > 0L
            } finally {
                conn.disconnect()
            }
        }.getOrDefault(false)

    private fun sha256Of(file: File): String = runCatching {
        val md = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                md.update(buf, 0, n)
            }
        }
        md.digest().joinToString("") { "%02x".format(it) }
    }.getOrDefault("")

    companion object {
        /** 清单地址链：gh-proxy（国内可达优先）→ raw 直连 → jsDelivr（CDN，可能有最长数小时缓存） */
        private val MANIFEST_URLS = listOf(
            "https://gh-proxy.com/https://raw.githubusercontent.com/R4gM9Xk/music/main/update.json",
            "https://raw.githubusercontent.com/R4gM9Xk/music/main/update.json",
            "https://cdn.jsdelivr.net/gh/R4gM9Xk/music@main/update.json",
        )
    }
}

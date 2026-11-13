package com.local.music.update

import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.content.pm.Signature
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.FileProvider
import com.local.music.feature.settings.screen.UpdateUiState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.Locale

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

    /**
     * 检查在飞标记：手动检查与启动静默检查共用一把锁。
     *
     * 为什么不只看 [state]：状态置位是协程体里的第一句，但 `scope.launch` 只是把任务排进队列——
     * 从「判断状态」到「协程真正跑起来置位」之间有一段窗口，启动 5s 静默检查与用户点设置页
     * 恰好落在这个窗口里就会双进：两路 fetchManifest（整体 15s 预算）白跑一遍，并互相覆盖
     * 彼此的 state（静默检查先回来置 Available，手动检查后回来又覆盖）。
     * AtomicBoolean 是同步置位，跨线程/跨调度都能立刻看到，窗口不存在。
     *
     * 代价（有意为之）：静默检查在飞时用户点「检查更新」会被合并进那一路，不再单独发请求；
     * 静默检查最长 15s（整体预算，见 [fetchManifest]）内必然收敛并更新 state，
     * 不值得为这点体感再开一路并发拉清单。
     */
    private val checkInFlight = java.util.concurrent.atomic.AtomicBoolean(false)

    /**
     * 下载在飞标记：与 [checkInFlight] 同型，但守的是**下载**这条链。
     *
     * 为什么不能用 `state.status == Downloading` 代替：状态置位发生在协程体里，
     * 而 `scope.launch` 只是排队 —— 两次点击都能落在「已排队、未执行」的窗口内。
     * AtomicBoolean 是同步置位，跨线程立即可见，窗口不存在（详见 [startUpdate]）。
     */
    private val downloadInFlight = java.util.concurrent.atomic.AtomicBoolean(false)

    // 下载进度节流基准：上一次真正写进 Compose state 的整数百分比与时刻（见 updateProgress）。
    // @Volatile：写入发生在 downloadTo 的 IO 线程，读也只在同一线程，但重置点在主线程的
    // startUpdate 里——加 volatile 保证主线程的复位对下载线程可见，不必再上锁
    @Volatile private var lastProgressPercent = -1
    @Volatile private var lastProgressAtMs = 0L

    // P3-50 不定长模式的节流基准：已读字节换算的 MB 数（见 updateProgress 的 total <= 0 分支）
    @Volatile private var lastIndeterminateReadMb = -1

    /** 手动检查（设置页点击；失败显示原因） */
    fun check() {
        // 并发防护：检查中忽略重复点击（含静默检查在飞）
        if (!checkInFlight.compareAndSet(false, true)) return
        // **下载中 / 已下载待安装时不接管**（本轮修复）：本函数会把 state 整体覆盖成
        // Checking → Available/UpToDate，正在进行的那条下载链的进度与安装入口就此丢失
        // （下载协程随后还会 `state.copy(ReadyToInstall)` 把它盖回来 → UI 两态来回抖）。
        // checkSilently() 早就排除了这两种状态，两处防护口径现在一致。
        if (state.status == UpdateUiState.Status.Downloading ||
            state.status == UpdateUiState.Status.ReadyToInstall
        ) {
            checkInFlight.set(false)
            return
        }
        // 状态先行、同步置位：UI 立即进入「检查中」并把按钮禁用，
        // 不能等协程调度到才有反馈（否则连点两下的第二下会看到旧状态）
        state = UpdateUiState(status = UpdateUiState.Status.Checking)
        scope.launch {
            try {
                val info = fetchManifest()
                state = when {
                    info == null -> UpdateUiState(
                        status = UpdateUiState.Status.Failed,
                        message = "无法连接更新源",
                        failStage = UpdateUiState.FailStage.Check, // P2-25：检查失败 ≠ 下载失败，文案按阶段给
                    )
                    info.versionCode <= currentVersionCode() ->
                        UpdateUiState(status = UpdateUiState.Status.UpToDate)
                    else -> UpdateUiState(
                        status = UpdateUiState.Status.Available,
                        versionName = info.versionName,
                        changelog = info.changelog,
                    ).also { pendingInfo = info }
                }
                // **UpToDate / Failed 时清掉 pendingInfo**（本轮修复）：旧实现只在 Available
                // 分支赋值、任何分支都不清理，于是「检查到 v9 → 用户没更新 → 服务端回滚到 v8
                // → 再检查得 UpToDate」之后 pendingInfo 仍指向 v9 的下载地址。
                // 设置页的失败分流又用 versionName != null 判断「进过下载链路」
                // （SettingsScreen.kt 的 Failed 分支），会把 UpToDate 之后的
                // Failed 误判成「下载失败 → 可重试」而实际是检查失败。
                // 这里显式清空，让 pendingInfo 的生命周期 = 「当前 state 是 Available」这一条。
                if (info == null || info.versionCode <= currentVersionCode()) pendingInfo = null
            } finally {
                // 必须无条件释放：fetchManifest 内部是 runCatching 兜底的，但「协程被取消」
                // 同样会走到这里——漏放会让本次进程内再也无法检查更新
                checkInFlight.set(false)
            }
        }
    }

    /** 启动静默检查：失败无声、有新版仅点亮设置行 */
    fun checkSilently() {
        // 与手动检查共用「在飞」标记：静默检查在飞时用户点检查，直接复用它的结果（它完成后会更新 state），
        // 不重复拉清单；反之手动检查在飞时静默检查让路
        if (!checkInFlight.compareAndSet(false, true)) return
        scope.launch {
            try {
                val info = fetchManifest() ?: return@launch
                if (info.versionCode > currentVersionCode() &&
                    // **Failed 也必须允许静默检查接管**（本轮修复）：旧条件只放行 Idle，
                    // 于是「用户曾经手动检查过一次、那次网络不通 → Failed」之后，
                    // 本次进程内每次冷启动的静默检查都静默跳过，
                    // 设置页「发现新版本」那条点亮永远不会再出现 ——
                    // 用户感知是「这个 App 不再提示更新了」，而且没有任何办法自愈
                    // （除非用户自己想起来再点一次「检查更新」）。
                    // Downloading/ReadyToInstall 仍必须排除：那两种状态下 pendingInfo 指向
                    // 一个正在进行/已完成的下载，覆盖 state 会丢掉进度与安装入口。
                    (
                        state.status == UpdateUiState.Status.Idle ||
                            state.status == UpdateUiState.Status.Failed ||
                            state.status == UpdateUiState.Status.UpToDate ||
                            state.status == UpdateUiState.Status.Checking
                        )
                ) {
                    pendingInfo = info
                    state = UpdateUiState(
                        status = UpdateUiState.Status.Available,
                        versionName = info.versionName,
                        changelog = info.changelog,
                    )
                }
            } finally {
                checkInFlight.set(false)
            }
        }
    }

    /** 一键更新：下载 → 校验 → 自动拉起安装器 */
    fun startUpdate() {
        val info = pendingInfo ?: return
        // 并发防护（本轮修复）：不能只看 state.status。
        //
        // 旧写法 `if (state.status == Downloading) return` 有窗口：状态置位发生在
        // `scope.launch` 的协程体首句，而 launch 只是排队 —— 从「判断状态」到
        // 「协程真正跑起来置位」之间，两次点击都能通过检查，各起一路下载协程，
        // 共用同一个 TMP_NAME 互相截断 → sha256 必然失败 → 误报「网络异常」。
        // 这与 check() 用 AtomicBoolean 解决的「launch 排队窗口」是同一类竞态。
        if (!downloadInFlight.compareAndSet(false, true)) return
        // P3-51：清掉上次进程被杀残留的半截 tmp（downloadApk 的成败路径都会删 tmp，
        // 唯独进程中途死亡没人兜底；不清理的话旧半截会一直占着私有目录空间）
        runCatching { File(apkFile.parentFile, TMP_NAME).delete() }
        // 进度节流基准随本次下载重置，避免上一次下载的百分比/时间戳让首块数据被误判为「无需写」
        lastProgressPercent = -1
        lastProgressAtMs = 0L
        lastIndeterminateReadMb = -1 // P3-50：不定长基准一并重置
        // **状态同步置位**（本轮修复）：与 check() 的做法对齐 —— UI 立即进入下载态并
        // 禁用入口，不要等协程调度到才有反馈。
        state = UpdateUiState(
            status = UpdateUiState.Status.Downloading,
            versionName = info.versionName,
            changelog = info.changelog,
            progress = 0f,
            // P3-50：先按「不定长」显示；首次回调拿到有效 Content-Length 再转百分比
            // （经代理转发常丢 Content-Length，旧实现这种场景全程恒 0%）
            indeterminate = true,
        )
        scope.launch {
            try {
                val downloaded = withContext(Dispatchers.IO) { downloadApk(info) }
                when {
                    downloaded != null -> {
                        state = state.copy(status = UpdateUiState.Status.ReadyToInstall, indeterminate = false)
                        install()
                    }
                    else -> state = state.copy(
                        status = UpdateUiState.Status.Failed,
                        message = "网络异常或下载中断",
                        failStage = UpdateUiState.FailStage.Download, // P2-25：真·下载失败
                        indeterminate = false,
                    )
                }
            } finally {
                // 必须无条件复位：异常/取消路径漏放会让本次进程内再也无法重新下载
                downloadInFlight.set(false)
            }
        }
    }

    /** 拉起系统安装器（已下载即可反复调用）；未授权时跳授权页 */
    fun install() {
        if (!apkFile.isFile) {
            state = state.copy(
                status = UpdateUiState.Status.Failed,
                message = "安装包已丢失，请重新下载",
                failStage = UpdateUiState.FailStage.Install, // P2-25：包丢了要重下，与验签失败（不许重试）区分
            )
            return
        }
        // 签名校验（最后一道防线，fail-closed）：sha256 只能证明「下载到的字节与清单写的一致」，
        // 而清单与 APK 走同一条不受信通道（gh-proxy / raw / jsDelivr），可被同时篡改——
        // 真正拦得住伪造包的是「待装包签名必须与当前运行包一致」。校验不过就地删除包，
        // 避免下次误装，并保留明确原因。
        if (!verifyApkSignature()) {
            runCatching { apkFile.delete() }
            // P2-25：验签失败必须同时清 pendingInfo——同一份坏字节重下（同源同签名）必然再失败，
            // 否则设置页「Failed 且 versionName != null → onStartUpdate()」会把用户锁进死循环。
            // 清掉后 onStartUpdate 对 null pendingInfo 直接 return，重试入口即便误触也无害
            pendingInfo = null
            state = state.copy(
                status = UpdateUiState.Status.Failed,
                message = "安装包签名校验失败，已拒绝安装",
                failStage = UpdateUiState.FailStage.Verify, // P2-25：此阶段**不给**「重新下载」重试入口
            )
            return
        }
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

    /**
     * 校验待装 APK 的签名是否与当前运行包一致（**fail-closed**：读不到也算失败）。
     *
     * 为什么必须有这一步：release 变体沿用 debug keystore 签名（见 app/build.gradle.kts），
     * 而 debug keystore 是公开的通用密钥——任何人都能签出一个「同包名同签名」的包来覆盖安装。
     * 清单里的 sha256 挡不住这种攻击（攻击者能同时改清单与 APK）。
     *
     * 签名按 hex 字符串集合比较，避免 ByteArray.equals 的引用比较陷阱。
     */
    private fun verifyApkSignature(): Boolean = runCatching {
        val pm = context.packageManager
        val archive = pm.getPackageArchiveInfo(
            apkFile.absolutePath,
            PackageManager.GET_SIGNING_CERTIFICATES,
        ) ?: return@runCatching false
        val current = pm.getPackageInfo(context.packageName, PackageManager.GET_SIGNING_CERTIFICATES)
        val archiveSigs = signaturesOf(archive)
        val currentSigs = signaturesOf(current)
        // 两侧都必须非空（空集相等会误判为通过）
        archiveSigs.isNotEmpty() && currentSigs.isNotEmpty() && archiveSigs == currentSigs
    }.getOrDefault(false)

    /** 取包的签名集合（hex 字符串）：优先新 API 的 apkContentsSigners，退回签名历史 */
    @Suppress("DEPRECATION")
    private fun signaturesOf(info: PackageInfo): Set<String> {
        val raw: Array<Signature>? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val signing = info.signingInfo
            when {
                signing == null -> null
                signing.hasMultipleSigners() -> signing.apkContentsSigners
                else -> signing.apkContentsSigners.ifEmpty { signing.signingCertificateHistory }
            }
        } else {
            info.signatures
        }
        return raw.orEmpty().map { it.toByteArray().toHex() }.toSet()
    }

    /**
     * 字节数组 → 小写 hex。
     *
     * 显式固定 [Locale.US]：`"%02x".format(byte)` 走的是默认 locale 的 formatter，
     * 在阿拉伯语/波斯语等区域（ar-EG、fa-IR）会把 ASCII 数字换成该区域的数字字形
     * （如 ٠١٢٣…），产出的就**不是合法 hex 字符串**了——sha256 比对必然恒不相等
     * （更新永远校验失败），签名比对同样恒不相等（fail-closed 直接拒绝安装）。
     * 这类 locale 恰好是本应用重点覆盖的市场之一，不能靠运气。
     */
    private fun ByteArray.toHex(): String =
        joinToString("") { String.format(Locale.US, "%02x", it) }

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
        // 退 0 的理论后果是「清单里任何版本号都被判定为有新版」——实际几乎不可达（读自己包
        // 的信息只会在 PM 完全异常时失败），但真出现「永远提示有更新」时要有痕迹可查
        android.util.Log.e("UpdateManager", "currentVersionCode failed; treating as 0 (every manifest looks newer)", e)
        0
    }

    /**
     * 清单三链回退，单链 5s 超时；任一成功即返回。
     *
     * P3-52：整体再套一层 15s 预算——单链实际是 connect 5s + read 5s 两个独立超时（最长 10s/链），
     * 三链全卡满理论上要 30s，用户早失去耐心；15s 内走不完就当检查失败处理。
     * 注意 withTimeoutOrNull 取消的是阻塞 IO 线程上挂起的调用点，已发出的底层连接要等
     * 单链自身超时才真正断开，但本函数调用方拿到的返回时机不再超过 15s。
     */
    private suspend fun fetchManifest(): UpdateInfo? = withContext(Dispatchers.IO) {
        withTimeoutOrNull(15_000) {
            MANIFEST_URLS.firstNotNullOfOrNull { url ->
                runCatching {
                    val body = httpGet(url, timeoutMs = 5_000) ?: return@runCatching null
                    parseManifest(JSONObject(body))
                }.getOrNull()
            }
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
        // 清单内 APK 链接强制 https：出现非 https 地址即整份清单判无效，跳源
        if (urls.isEmpty() || urls.any { !it.startsWith("https://") }) return null
        return UpdateInfo(
            versionCode = code,
            versionName = o.optString("versionName", code.toString()),
            changelog = o.optString("changelog").ifEmpty { null },
            apkUrls = urls,
            sha256 = o.optString("sha256").ifEmpty { null }?.lowercase(),
        )
    }

    /**
     * 按清单地址逐个尝试下载；sha256 缺失或不符均视为校验失败（fail-closed），换下一个源。
     *
     * P3-51：runCatching 换显式 try/catch——runCatching 连 CancellationException 一起吞掉，
     * 协程取消信号会被它伪装成「下载失败」，且阻塞 IO 里的 downloadTo 根本感知不到取消。
     * 现在循环内 catch 到取消就删 tmp 并原样重抛，让取消语义回到协程层。
     */
    private fun downloadApk(info: UpdateInfo): File? {
        apkFile.parentFile?.mkdirs()
        val tmp = File(apkFile.parentFile, TMP_NAME)
        for (url in info.apkUrls) {
            val ok = try {
                downloadTo(url, tmp) { read, total -> updateProgress(read, total) }
                val actual = sha256Of(tmp)
                if (info.sha256 == null || actual != info.sha256) {
                    tmp.delete()
                    false // sha256 缺失（疑似被代理裁剪）或不符：跳过该源
                } else {
                    tmp.renameTo(apkFile) || (apkFile.delete() && tmp.renameTo(apkFile))
                }
            } catch (e: CancellationException) {
                // 取消（协程被杀）：清掉半截包后原样上抛，不能吞
                tmp.delete()
                throw e
            } catch (e: Exception) {
                // 普通网络异常：换下一个源（等价于旧 runCatching 的兜底语义）
                false
            }
            if (ok) return apkFile
        }
        tmp.delete()
        return null
    }

    /**
     * 下载进度回报（64KB 一块回调 → 快网下每秒可达数百次）。
     *
     * 为什么要节流：每次赋值都是一次 snapshot state 写入，会唤醒所有读 progress 的组合
     * （设置页整行 + 进度条 + 按钮文案），数百 Hz 的无效重组纯属浪费——用户看到的是一根
     * 百分比进度条，1% 的变化量已经是它物理上能表达的最小粒度（还要再加上一帧 16ms 的显示延迟）。
     *
     * 规则：整数百分比变了就写；没变但距上次写入 ≥ [PROGRESS_MIN_INTERVAL_MS] 也写。
     * 后者是「慢速网络」的补偿——龟速下载时可能几十秒都跨不过 1%，全靠时间兜底，
     * 否则进度条看起来彻底卡死（块级涓流也要让用户看到它还在动）。
     *
     * P3-50：total <= 0（经代理转发丢 Content-Length）时不再直接 return（旧实现进度恒 0%），
     * 改为按「不定长」回报：首次命中把 indeterminate 置 true（startUpdate 起步已预置 true，
     * 这里幂等重写）；此后 read 每跨 1MB 或距上次写入 ≥ 100ms 写一次 progress（增量节流），
     * 让 UI 能表达「仍在动」。某链半途给出了有效 total（>0），立即转回百分比模式。
     */
    private fun updateProgress(read: Long, total: Long) {
        if (total <= 0) {
            // 不定长模式：无法计算百分比，用「已读字节数」做节流基准。
            //
            // **progress 字段不能被写成字节数**（本轮修复）：它的语义是 0..1 的归一化进度，
            // 而 UI 侧 ProgressTrack 会对该值 coerceIn(0f, 1f) —— 写 read.toFloat()
            // （已读字节数，动辄上百万）会被钳成 1f，进度条**恒满格**。这比「恒 0%」
            // 更糟：用户以为下完了。正确做法是保持 progress 不动（让它留在上次的 0 或
            // 上次有效百分比），并把 indeterminate=true 交给 UI 去表达「总长未知、仍在动」。
            if (state.status == UpdateUiState.Status.Downloading) {
                if (!state.indeterminate) state = state.copy(indeterminate = true)
                val now = android.os.SystemClock.elapsedRealtime()
                // 注意两侧必须同型比较（Long vs Int 的 == 走装箱 equals 恒为 false）
                val mb = read shr 20
                val mbChanged = mb != lastIndeterminateReadMb.toLong()
                val intervalElapsed = now - lastProgressAtMs >= PROGRESS_MIN_INTERVAL_MS
                if (mbChanged || intervalElapsed) {
                    lastIndeterminateReadMb = mb.toInt()
                    lastProgressAtMs = now
                    // 只写 indeterminate 的「心跳」，不改 progress（见上）
                    state = state.copy(indeterminate = true)
                }
            }
            return
        }
        if (state.indeterminate) state = state.copy(indeterminate = false) // 拿到总长：转回百分比模式
        val p = (read.toFloat() / total).coerceIn(0f, 1f)
        val percent = (p * 100).toInt()
        val now = android.os.SystemClock.elapsedRealtime()
        val percentChanged = percent != lastProgressPercent
        val intervalElapsed = now - lastProgressAtMs >= PROGRESS_MIN_INTERVAL_MS
        if (!percentChanged && !intervalElapsed) return
        lastProgressPercent = percent
        lastProgressAtMs = now
        if (state.status == UpdateUiState.Status.Downloading) {
            state = state.copy(progress = p)
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
                if (target.length() < MIN_APK_BYTES) return@runCatching false
                // 文件头必须是 APK/ZIP 魔数 "PK"，挡掉代理返回的错误页/半包
                val head = ByteArray(2)
                val headOk = target.inputStream().use { it.read(head) } == 2 &&
                    head[0] == 'P'.code.toByte() && head[1] == 'K'.code.toByte()
                if (!headOk) return@runCatching false
                true
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
        md.digest().joinToString("") { String.format(Locale.US, "%02x", it) }
    }.getOrDefault("")

    companion object {
        /** 下载通过下限：APK 至少 1MB（再叠文件头 "PK" 魔数校验） */
        private const val MIN_APK_BYTES = 1L * 1024 * 1024

        /** 下载临时文件名（downloadApk 与 startUpdate 的陈旧清理共用，避免两处字符串漂移） */
        private const val TMP_NAME = "app-update.tmp"

        /** 进度写入最小间隔（见 [updateProgress]）：低于一帧的刷新率对人眼无意义 */
        private const val PROGRESS_MIN_INTERVAL_MS = 100L

        /** 清单地址链：gh-proxy（国内可达优先）→ raw 直连 → jsDelivr（CDN，可能有最长数小时缓存） */
        private val MANIFEST_URLS = listOf(
            "https://gh-proxy.com/https://raw.githubusercontent.com/R4gM9Xk/music/main/update.json",
            "https://raw.githubusercontent.com/R4gM9Xk/music/main/update.json",
            "https://cdn.jsdelivr.net/gh/R4gM9Xk/music@main/update.json",
        )
    }
}

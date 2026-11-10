package com.local.music.share

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.media.RingtoneManager
import android.net.Uri
import android.provider.Settings
import androidx.core.content.FileProvider
import com.local.music.core.ui.components.DemoPlaylist
import com.local.music.core.ui.components.DemoSong
import com.local.music.player.M3uPlaylistIO
import java.io.File

/**
 * 系统集成动作：分享歌曲 / 分享歌单 / 设为铃声（P0-5 A）。
 *
 * ## 为什么单独一个文件
 *
 * 这三个动作都是「把 App 内部数据交给系统」——涉及 Intent、Uri 授权、FileProvider、
 * WRITE_SETTINGS 这四件与 Compose 完全无关的东西。写进 ActionsSheet（UI 层）会让
 * 面板组件同时承担排版与系统交互两种职责，也无法在没有 UI 的场景（小部件、通知）
 * 复用。放在 app 模块是因为只有它有 AndroidManifest 与 FileProvider 配置。
 *
 * ## 统一的失败处理策略
 *
 * **所有系统交互一律 runCatching 包住并返回 [Outcome]，绝不向上抛**：
 * 这些动作全部依赖「对面有什么 App / 厂商 ROM 怎么实现」，任何一个环节都可能
 * `ActivityNotFoundException` / `SecurityException` / `IllegalArgumentException`。
 * 在国内 ROM（ColorOS/MIUI/EMUI）上这些概率远高于 AOSP——例如部分 ROM 精简掉了
 * AOSP 的铃声选择器、部分 ROM 的 FileProvider 授权要额外 flag 才生效。
 * 用户视角只有两种结局：**「做了什么」** 或 **「为什么没做成」**，不能是静默无反应。
 * 所以每个函数都返回一句可以直接丢给顶部横幅的中文文案。
 */
object ShareActions {

    /**
     * 动作结果：[ok] 用于决定横幅语气（此处不区分图标，仅作语义标记）；
     * [message] 是给用户看的一句话，**必然非空**——「点了没反应」是最糟的交互。
     */
    data class Outcome(val ok: Boolean, val message: String)

    // ── 分享歌曲 ────────────────────────────────────────────

    /**
     * 分享单曲：`ACTION_SEND` + `createChooser`。
     *
     * ### 为什么直接用 MediaStore content:// uri，而不是 FileProvider
     *
     * 曲目的 [DemoSong.mediaUri] 本来就是 `content://media/external/audio/media/<id>`，
     * **接收方 App 只要拿到 FLAG_GRANT_READ_URI_PERMISSION 就能直接读**，零拷贝。
     *
     * 而走 FileProvider 反而是错的路：本工程的 `res/xml/file_paths.xml` 只暴露了
     * `files/update/`（给应用内更新包用）。要把 `/sdcard/Music/` 这类外部目录加进去，
     * 必须声明 `<external-path>` 或 `<root-path>` —— 那等于把用户**整块存储**
     * 通过一个 exported=false 但仍可被任意已安装 App 通过 grant 访问的 provider 打开。
     * 为一个「分享一首歌」的需求把存储面扩到全盘，是明确的安全倒退。
     * （该文件顶部还有一段注释记录了同一类踩坑：封面曾试图走 FileProvider，被系统侧
     *  Permission Denial 拒绝。结论一致：媒体文件走 MediaStore，别动 FileProvider 路径表。）
     *
     * ### 无 mediaUri 的曲目（演示/占位）
     *
     * 退化成纯文本分享（「标题 · 艺术家」），至少能让用户把歌名发给朋友。
     * 不报错——用户点「分享」得不到任何反应是最差的体验。
     */
    fun shareSong(context: Context, song: DemoSong): Outcome {
        val title = song.title.ifBlank { "未命名曲目" }
        val subject = buildString {
            append(title)
            if (song.artist.isNotBlank()) append(" - ").append(song.artist)
        }
        val uri = song.mediaUri?.takeIf { it.isNotBlank() }?.let { raw ->
            runCatching { Uri.parse(raw) }.getOrNull()
        }
        // 没有可用 uri：退化为文本分享（见 KDoc）。EXTRA_TITLE 让接收方（如聊天软件）
        // 用它当消息标题/文件展示名，比裸 uri 对用户可读得多
        val send = when {
            uri == null -> Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_TEXT, subject)
                putExtra(Intent.EXTRA_SUBJECT, subject)
                putExtra(Intent.EXTRA_TITLE, title)
            }
            else -> Intent(Intent.ACTION_SEND).apply {
                type = "audio/*"
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(Intent.EXTRA_TITLE, title)
                putExtra(Intent.EXTRA_SUBJECT, subject)
                // 关键：没有这个 flag，接收方拿到 uri 也读不出字节（SecurityException）
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
        }
        return launchChooser(context, send, "分享「$title」", if (uri == null) {
            // 文本兜底：说明「这首歌没有可分享的音频文件」，避免用户以为分享出去的是音频
            "这首歌没有可分享的音频文件，已改用文字分享歌名"
        } else {
            "已打开分享面板"
        }, "分享「$title」失败")
    }

    /**
     * 分享歌单：先把歌单导出成临时 M3U8 文件，再把**文件本身**分享出去。
     *
     * 为什么是 M3U8 而不是文本：歌单内容的唯一可移植格式就是 M3U/M3U8，
     * 接收方（另一个播放器 / 电脑上的 foobar2000）能直接打开；用纯文本发一串歌名
     * 对接收方毫无用处。这条路径完全复用已有的 [M3uPlaylistIO.export]，不新写序列化。
     *
     * ### 为什么临时文件必须走 FileProvider（与此处 shareSong 相反）
     *
     * 这个文件在**应用私有目录** `files/share/` 下，别人的 App 没有路径也拿不到权限，
     * 必须由 FileProvider 换成一个可授权的 content:// uri。对应的 `<files-path name="share"
     * path="share/">` 已加进 `res/xml/file_paths.xml`（只暴露这一个子目录，
     * 不碰用户存储，与 shareSong 注释里拒绝 external-path 的理由一致）。
     *
     * ### 清理
     *
     * 每次分享前把 `files/share/` 目录清空再写（见 [pruneShareDir]）。不清理的话，
     * 用户每分享一次歌单就在私有目录里多留一个文件——纯本地播放器的用户很可能
     * 反复分享同一批歌单，几个月后就是一个几百 MB 的垃圾目录。
     * 用「分享前清空」而不是「分享后删除」：分享出去后接收方可能延迟读取（系统分享
     * 面板/聊天软件会异步拉取），立刻删除会让对方拿到空文件或读失败。
     */
    fun sharePlaylist(context: Context, title: String, tracks: List<DemoSong>): Outcome {
        if (tracks.isEmpty()) {
            return Outcome(false, "「${title}」里还没有歌曲，无法分享")
        }
        return runCatching {
            val dir = File(context.filesDir, SHARE_DIR).apply { mkdirs() }
            pruneShareDir(dir)
            // 文件名复用 M3uPlaylistIO 的清洗逻辑（非法字符 / 长度 / Windows 保留名），
            // 那条逻辑已经为「跨设备打开」打磨过（P3-73），这里不再重复实现一遍
            val file = File(dir, M3uPlaylistIO.suggestedFileName(title))
            val playlist = DemoPlaylist(
                id = "share-${System.currentTimeMillis()}",
                title = title,
                meta = "${tracks.size} 首",
                // 封面只是 M3U8 的展示元数据，不进文件内容；这里不持有真实封面资源，
                // 用曲库里已有的一种艺术风格占位（ArtStyle 是纯绘制参数，无资源依赖）
                art = com.local.music.core.ui.components.ArtStyle.Night,
                tracks = tracks,
            )
            // Uri.fromFile 只是给 ContentResolver 指路：openOutputStream 对 file:// 走
            // 文件直写，不经过任何 provider（这里写的是自己的私有目录，本来就有权限）
            M3uPlaylistIO.export(context, Uri.fromFile(file), playlist)
            if (!file.isFile || file.length() == 0L) {
                return@runCatching Outcome(false, "分享失败：歌单文件没有写出来")
            }
            val shared = FileProvider.getUriForFile(context, fileProviderAuthority(context), file)
            val send = Intent(Intent.ACTION_SEND).apply {
                // M3U8 的标准 MIME；部分接收方只认 audio/x-mpegurl，两者等价，用前者兼容更广
                type = "audio/x-mpegurl"
                putExtra(Intent.EXTRA_STREAM, shared)
                putExtra(Intent.EXTRA_TITLE, title)
                putExtra(Intent.EXTRA_SUBJECT, title)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            launchChooser(context, send, "分享歌单「$title」", "已准备「$title」（${tracks.size} 首）", "分享歌单失败")
        }.getOrElse { e ->
            // FileProvider 路径没配对（file_paths.xml 少声明 share/）时会抛
            // IllegalArgumentException("Failed to find configured root...")：
            // 这属于打包配置问题，给用户的文案只能是「功能不可用」，细节进日志
            Outcome(false, "分享歌单失败：${friendly(e)}")
        }
    }

    /** 分享目录名：`filesDir/share/`，必须与 file_paths.xml 里的 `<files-path path="share/">` 一致 */
    private const val SHARE_DIR = "share"

    /** 分享前清空目录（理由见 [sharePlaylist] 的 KDoc「清理」段） */
    private fun pruneShareDir(dir: File) {
        runCatching { dir.listFiles()?.forEach { f -> if (f.isFile) f.delete() } }
    }

    /**
     * FileProvider authority：必须与 AndroidManifest 里的 `${applicationId}.fileprovider` 一致。
     *
     * build.gradle 给 debug/release 都加了 `applicationIdSuffix = ".debug"`，
     * 所以**不能硬编码** `com.local.music.fileprovider`——运行期 [Context.getPackageName]
     * 才是真实 applicationId（`com.local.music.debug`）。硬编码会在真机上直接
     * IllegalArgumentException（找不到 provider）。
     */
    private fun fileProviderAuthority(context: Context): String = "${context.packageName}.fileprovider"

    // ── 设为铃声 ────────────────────────────────────────────

    /**
     * 设为电话铃声。
     *
     * ### 选的路：三级降级（写在这里是为了让后来者知道为什么这么绕）
     *
     * **一级 —— 系统铃声选择器 [`RingtoneManager.ACTION_RINGTONE_PICKER`]（首选）**
     *
     * 这是唯一一条「不需要本 App 持有 WRITE_SETTINGS」的路：选择器本身跑在 Settings
     * （系统应用，天生持有该权限），用户在我们传进去的候选项上点确定即可。
     * 我们通过 `EXTRA_RINGTONE_EXISTING_URI` 把**当前这首**塞进去，用户进来就看到它，
     * 少一步查找；`EXTRA_RINGTONE_SHOW_SILENT = false` 让「无声」不占位
     * （用户点的动作是「设为铃声」，他这会儿不想要无声）。
     *
     * 为什么它是首选而不是直接 setActualDefaultRingtoneUri：后者在 Android 12+ 必须
     * 持有 WRITE_SETTINGS（特殊权限，要走设置页授权并手动开关），对「听到好歌想设铃声」
     * 这种一念之间的动作，先弹一个系统权限页是极高的流失点。选择器是一步到位的。
     *
     * **二级 —— 本 App 已持有 WRITE_SETTINGS 时直接写系统设置**
     *
     * 用户此前为别的功能授权过（或我们在授权页引导过）的情况下，
     * [`RingtoneManager.setActualDefaultRingtoneUri`] 是最短的路径，且不弹任何界面。
     *
     * **三级 —— 引导用户去授权页**
     *
     * 一级拉不起来（国内 ROM 精简了 AOSP 的铃声选择器是常见现象——注意本工程 targetSdk 36，
     * Android 11+ 的包可见性也会让「能不能拉到」变得不可预测，所以这里**不做
     * resolveActivity 预检，直接 startActivity 然后接异常**：预检需要 manifest 里声明
     * `<queries>`，而且预检通过也可能在 startActivity 时抛 ActivityNotFoundException，
     * 白写两遍判断）且二级没有权限时，把用户送到
     * `Settings.ACTION_MANAGE_WRITE_SETTINGS`，回来后**再点一次**「设为铃声」就会走二级。
     *
     * 为什么不在一级返回结果里做「没生效就补写」：`ACTION_RINGTONE_PICKER` 是
     * **选择器不是设置器**——AOSP 的实现只把用户选的 uri 通过 `EXTRA_RINGTONE_PICKED_URI`
     * 返回给调用方，而不落盘；部分国内 ROM 的 Settings 版本则是自己落盘。两种行为都能遇到，
     * 我们又没有 ActivityResult 通道（本动作是从列表菜单直接发起的，没有 launcher 生命周期），
     * 所以不依赖它的返回值，只把「打开系统界面」当作一级成功的判据。
     * 用户在选择器里点了取消 = 他自己放弃了，不该再自作主张去写系统设置。
     */
    fun setAsRingtone(context: Context, song: DemoSong): Outcome {
        val title = song.title.ifBlank { "未命名曲目" }
        val uri = song.mediaUri?.takeIf { it.isNotBlank() }?.let { raw ->
            runCatching { Uri.parse(raw) }.getOrNull()
        } ?: return Outcome(false, "「$title」没有音频文件，无法设为铃声")

        // 一级：系统选择器。所有异常都收进 failure，交给下面判断要不要降级
        val pickerFailure = runCatching { startRingtonePicker(context, uri) }.exceptionOrNull()
        if (pickerFailure == null) {
            return Outcome(true, "已打开系统铃声选择器，选择「$title」并确认即可")
        }

        // 二级：已有 WRITE_SETTINGS → 直接写
        if (canWriteSystemSettings(context)) {
            val setFailure = runCatching {
                RingtoneManager.setActualDefaultRingtoneUri(context, RingtoneManager.TYPE_RINGTONE, uri)
            }.exceptionOrNull()
            return if (setFailure == null) {
                Outcome(true, "已把「$title」设为来电铃声")
            } else {
                // 走到这里说明权限在但写入仍被拒（部分 ROM 的 AppOps 与 Settings.System.canWrite
                // 口径不一致）。不抛给用户看堆栈，给一句可执行的话
                Outcome(false, "设为铃声失败：系统拒绝了写入（${friendly(setFailure)}），可在「设置 → 声音与振动 → 电话铃声」里手动选择")
            }
        }

        // 三级：引导授权。返回 true = 已把用户送到授权页
        val granted = runCatching { openWriteSettingsGrantPage(context) }.getOrDefault(false)
        return Outcome(
            false,
            if (granted) {
                "需要「修改系统设置」权限：已打开授权页，允许后请再点一次「设为铃声」"
            } else {
                "当前系统不支持直接设为铃声，请到「设置 → 声音与振动 → 电话铃声」里选择本地音乐"
            },
        )
    }

    /** 一级实现：拉起系统铃声选择器（Extras 语义见 [setAsRingtone] 的 KDoc）。
     *  形参只有 uri：标题不再需要（见下方已删除行留下的说明）。 */
    private fun startRingtonePicker(context: Context, uri: Uri) {
        val picker = Intent(RingtoneManager.ACTION_RINGTONE_PICKER).apply {
            putExtra(RingtoneManager.EXTRA_RINGTONE_TYPE, RingtoneManager.TYPE_RINGTONE)
            putExtra(RingtoneManager.EXTRA_RINGTONE_TITLE, "设为来电铃声")
            // 把我们这首作为「当前选择」塞进去：选择器会把它显示为已选项/自定义项，
            // 用户进来就能看到它，少一步查找（这是本条路径省掉权限页的关键收益）
            putExtra(RingtoneManager.EXTRA_RINGTONE_EXISTING_URI, uri)
            // 不显示「无声」：用户此刻的动作是「设为铃声」，把无声摆在首屏是误导
            putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_SILENT, false)
            // 未在库里收录的 uri 也要显示（我们的歌在 Music 目录，未必被登记为铃声）
            putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_DEFAULT, true)
            // 结果 uri 由谁读取：我们不需要读返回值（见 setAsRingtone 的取舍说明），
            // 但部分 ROM 的选择器非 null 才肯正常渲染候选，故按 AOSP 惯例留一个占位
            putExtra(RingtoneManager.EXTRA_RINGTONE_PICKED_URI, uri)
            // 已删除：旧实现在这里还有一行
            //   putExtra("android.intent.extra.ringtone.SHOW_SILENT_ITEM", title)
            // 该 key 是 EXTRA_RINGTONE_SHOW_SILENT 的字符串别名，语义是 **boolean**
            // （决定「无声」这一项是否出现在候选列表里），而上一行已经用官方常量
            // 正确地 putExtra(EXTRA_RINGTONE_SHOW_SILENT, false)。把歌名 String 塞进 boolean 键：
            // AOSP 的 Settings 会忽略它，但按 getBooleanExtra 读该键的实现会撞类型不匹配
            // （静默回默认值，个别 ROM 直接抛 ClassCastException）。title 在这里也没有任何用途
            // —— 当前曲已经通过 EXTRA_RINGTONE_EXISTING_URI 显示为已选项。
        }
        startActivitySafely(context, picker)
    }

    /** [Settings.System.canWrite]：WRITE_SETTINGS 是特殊权限，只能这样查（不是 checkSelfPermission） */
    private fun canWriteSystemSettings(context: Context): Boolean =
        runCatching { Settings.System.canWrite(context) }.getOrDefault(false)

    /**
     * 三级实现：跳 `ACTION_MANAGE_WRITE_SETTINGS` 授权页。
     *
     * 带 `package:` uri 直达本 App 那一行（不带的话用户要在几百个 App 里翻）。
     * 返回 false = 连授权页都拉不起来（极老的 ROM / 被精简），调用方会退回纯文案提示。
     *
     * 注意：这条路径**没有结果回调**（用 startActivity 而不是 ActivityResultLauncher，
     * 因为发起点是列表菜单，没有可挂的生命周期）。用户授权返回后需要**再点一次**
     * 「设为铃声」——文案里已经明确说了这一点，不指望用户猜。
     */
    private fun openWriteSettingsGrantPage(context: Context): Boolean = runCatching {
        val intent = Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS).apply {
            data = Uri.parse("package:${context.packageName}")
            // 授权页不在本任务栈里时，返回栈要保持本 App 在下层（否则返回直接退出应用）
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        startActivitySafely(context, intent)
        true
    }.getOrDefault(false)

    // ── 公共工具 ────────────────────────────────────────────

    /**
     * 起 `createChooser` 并统一处理「没有 App 能接 / 被系统拒绝」。
     *
     * [chooserTitle] 是分享面板的标题；[successMessage] / [failPrefix] 是给横幅的文案。
     *
     * **chooser 本身也补 FLAG_GRANT_READ_URI_PERMISSION**：`createChooser` 会把 target
     * intent 的 flag 转发给被选中的组件，AOSP 实现是可靠的；但个别 ROM 的 chooser
     * 中间层会丢掉 flag，导致接收方读不出文件（用户看到「分享失败/文件不存在」）。
     * 补一个 flag 无副作用（对不带 uri 的纯文本 intent 也安全），换 ROM 兼容性。
     */
    private fun launchChooser(
        context: Context,
        send: Intent,
        chooserTitle: String,
        successMessage: String,
        failPrefix: String,
    ): Outcome = runCatching {
        val chooser = Intent.createChooser(send, chooserTitle).apply {
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivitySafely(context, chooser)
        Outcome(true, successMessage)
    }.getOrElse { e ->
        // 失败原因分两类，文案要分开：没有 App 能接（用户装的应用太少）vs 系统拒绝。
        // 两者用户能做的事完全不同，混成一句「分享失败」等于没提示
        val notFound = e is ActivityNotFoundException
        Outcome(
            false,
            if (notFound) "$failPrefix：系统里没有可以接收分享的应用" else "$failPrefix：${friendly(e)}",
        )
    }

    /**
     * 起 Activity，并在「非 Activity Context」时补 NEW_TASK。
     *
     * 为什么需要：本文件的调用方传入的是 Compose `LocalContext.current`，它在 Activity
     * 宿主下就是 Activity 本身；但同一批动作也可能从小部件/服务发起（那些场景拿到的是
     * Application context），此时不带 FLAG_ACTIVITY_NEW_TASK 会直接抛
     * `AndroidRuntimeException: Calling startActivity() from outside of an Activity context
     * requires the FLAG_ACTIVITY_NEW_TASK flag`。这个分支现在用不到，但成本是两行，
     * 而漏掉它的后果是在新入口上线时炸得莫名其妙。
     */
    private fun startActivitySafely(context: Context, intent: Intent) {
        if (context !is Activity) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
    }

    /**
     * 异常 → 一句能读的原因。
     *
     * 不用 `e.message` 直接拼：那条消息里常带类名/包名（
     * "Failed to find configured root that contains /data/..."），对用户是无意义噪音。
     * 这里按异常类型给稳定文案，其余情况回退到简短 message（截断防横幅超长）。
     */
    private fun friendly(e: Throwable): String = when (e) {
        is ActivityNotFoundException -> "系统里没有可以处理该操作的界面"
        is SecurityException -> "权限不足"
        is IllegalArgumentException -> "分享配置异常，请更新到最新版本"
        else -> e.message?.take(60)?.takeIf { it.isNotBlank() } ?: "未知错误"
    }
}

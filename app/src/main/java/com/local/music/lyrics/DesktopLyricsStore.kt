package com.local.music.lyrics

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.local.music.core.ui.components.DesktopLyricsStyle
import kotlinx.coroutines.flow.first

/**
 * 桌面歌词开关的持久化（P0-8）。
 *
 * ## 为什么不复用 SettingsStore 的 AppStateSnapshot
 * AppStateSnapshot / AppState / SettingsStore.kt 属于历史封板文件（快照 v1→v10 迁移链
 * 已被 137 条单测钉死），为加一个布尔键去动它，要升 VERSION、补 encode/decode/legacy
 * 三条路径、还要过快照测试——改动面与风险远超这个功能本身。所以这里用**独立 DataStore**
 * 单键存储：旧版本无此文件 → 读到 null → 默认关，天然兼容，零迁移。
 *
 * ## 为什么开关状态放 UI 侧而不是服务端
 * 悬浮窗是纯显示层瞬态：服务重建（进程被杀后系统续播拉起）时**不该自动弹窗**——
 * 用户没有当下操作却看到桌面多出一行字，比「开关要重开一次」糟糕得多。
 * 状态由 UI 持有，服务重连时按存储值重放一次（见 GlassApp 的重连 effect 与
 * MusicPlaybackService.CMD_SET_DESKTOP_LYRICS 注释）；服务只驱动进度同步与切歌重读。
 */
val Context.desktopLyricsStore by preferencesDataStore(name = "desktop_lyrics")

object DesktopLyricsStore {

    private val KEY_ON = booleanPreferencesKey("on")

    /**
     * 外观档位（本轮新增）。
     *
     * 存**档位索引**而不是 sp/百分比数值：档位是用户可理解的离散选择
     * （「标准」「清晰」），存索引让「把某档的数值调一调」不会影响已保存的偏好，
     * 也让旧版本读新版本写入的未知索引时能安全回落到默认档（见 [readStyle]）。
     */
    private val KEY_FONT_STEP = intPreferencesKey("font_step")
    private val KEY_OPACITY_STEP = intPreferencesKey("opacity_step")

    /**
     * 锁定位置（C-2 本轮新增）。
     *
     * 锁定的语义是「触摸穿透」：悬浮窗变成纯展示，任何触摸都落到下层应用。
     * 为什么需要它——可拖动的悬浮条在**滑动操作**里是个持续风险：
     * 用户在刷信息流/玩游戏时手指划过歌词条就会把它拖走，而他本想滚的是内容。
     * 主流桌面歌词（QQ 音乐/网易云）都有这枚锁，属于「用了就回不去」的细节。
     */
    private val KEY_LOCKED = booleanPreferencesKey("locked")

    /** 读开关；IO 异常/文件不存在一律默认**关**（不给悬浮窗擅自弹出的机会） */
    suspend fun read(context: Context): Boolean = runCatching {
        context.applicationContext.desktopLyricsStore.data.first()[KEY_ON] ?: false
    }.getOrDefault(false)

    /** 写开关；失败只记结果（开关是低价值数据，坏了下次重开即可，不值得用户感知） */
    suspend fun write(context: Context, on: Boolean): Boolean = runCatching {
        context.applicationContext.desktopLyricsStore.edit { it[KEY_ON] = on }
        true
    }.getOrDefault(false)

    /** 外观档位快照（字号档, 不透明度档） */
    data class Style(val fontStep: Int, val opacityStep: Int)

    /**
     * 读外观；任何异常/缺字段回落默认档。
     *
     * **必须做范围钳制**：DataStore 里的值是历史数据，旧版本（或未来版本）写入的
     * 索引可能超出当前档位数。越界时若不钳制，`FontSteps[step]` 会直接抛
     * IndexOutOfBounds **在服务进程里**（桌面歌词是服务侧渲染的），表现为播放服务崩溃。
     */
    suspend fun readStyle(context: Context): Style = runCatching {
        val prefs = context.applicationContext.desktopLyricsStore.data.first()
        val font = (prefs[KEY_FONT_STEP] ?: DesktopLyricsStyle.DEFAULT_FONT_STEP)
            .coerceIn(0, DesktopLyricsStyle.FontSteps.lastIndex)
        val opacity = (prefs[KEY_OPACITY_STEP] ?: DesktopLyricsStyle.DEFAULT_OPACITY_STEP)
            .coerceIn(0, DesktopLyricsStyle.OpacitySteps.lastIndex)
        Style(font, opacity)
    }.getOrDefault(Style(DesktopLyricsStyle.DEFAULT_FONT_STEP, DesktopLyricsStyle.DEFAULT_OPACITY_STEP))

    /** 写字号档（已钳制，越界值不会落盘） */
    suspend fun writeFontStep(context: Context, step: Int) = runCatching {
        val v = step.coerceIn(0, DesktopLyricsStyle.FontSteps.lastIndex)
        context.applicationContext.desktopLyricsStore.edit { it[KEY_FONT_STEP] = v }
    }.onFailure { android.util.Log.w("DesktopLyric", "write font step failed", it) }

    /** 写不透明度档（已钳制） */
    suspend fun writeOpacityStep(context: Context, step: Int) = runCatching {
        val v = step.coerceIn(0, DesktopLyricsStyle.OpacitySteps.lastIndex)
        context.applicationContext.desktopLyricsStore.edit { it[KEY_OPACITY_STEP] = v }
    }.onFailure { android.util.Log.w("DesktopLyric", "write opacity step failed", it) }

    /** 读锁定态；默认**不锁**（首次使用应该能拖，锁是用户主动加的保护） */
    suspend fun readLocked(context: Context): Boolean = runCatching {
        context.applicationContext.desktopLyricsStore.data.first()[KEY_LOCKED] ?: false
    }.getOrDefault(false)

    /** 写锁定态 */
    suspend fun writeLocked(context: Context, locked: Boolean) = runCatching {
        context.applicationContext.desktopLyricsStore.edit { it[KEY_LOCKED] = locked }
    }.onFailure { android.util.Log.w("DesktopLyric", "write locked failed", it) }

    /**
     * 跳系统「显示在其他应用上层」授权页（带 package uri 直达本应用的开关）。
     *
     * 三级回退：直达页 → 无 uri 的悬浮窗总列表 → 应用详情页。ACTION_MANAGE_OVERLAY_PERMISSION
     * 带 package uri 在个别 ROM（部分 ColorOS 版本）上不被处理，必须给兜底——
     * 否则就是「点了去授权没反应」（任务约束第 4 条）。
     *
     * @return 是否至少有一条跳转成功（失败时调用方给 toast/横幅提示手动路径）
     */
    fun openPermissionPage(context: Context): Boolean {
        val uri = Uri.parse("package:${context.packageName}")
        val direct = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, uri)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (runCatching { context.startActivity(direct) }.isSuccess) return true
        // 无 uri 形态：系统悬浮窗授权列表（用户需要手动找到本应用，但一定能到）
        val list = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (runCatching { context.startActivity(list) }.isSuccess) return true
        // 最终兜底：应用详情页
        val details = Intent(
            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            uri,
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return runCatching { context.startActivity(details) }.isSuccess
    }
}

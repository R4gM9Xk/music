package com.local.music.core.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.local.music.core.ui.state.AppState
import com.local.music.core.ui.state.songFormatText
import com.local.music.core.ui.theme.GlassIcons
import com.local.music.core.ui.theme.GlassText
import com.local.music.core.ui.theme.LocalGlassColors
import com.local.music.core.ui.theme.R
import com.local.music.core.ui.theme.S
import com.local.music.core.ui.theme.auroraAccentColor

/**
 * 动作面板统一组件：全 App 的单曲菜单 / 集合菜单唯一实现——
 * 任何页面要菜单 = 挂组件，禁止复制面板代码（防屎山红线）。
 */

/** 单曲操作面板的二级面板 */
private enum class SongActionSub { None, AddTo, Info }

/**
 * 单曲操作面板：下一首播放 / 最后播放 / 收藏 / 加入歌单 / 查看专辑 / 查看艺术家 /
 * 从歌单移除（仅歌单详情内的自建歌单提供）/ 歌曲信息。
 * 曲库、搜索、歌单详情、播放页 More 共用；二级面板（加入歌单 / 歌曲信息）内聚于此。
 */
@Composable
fun SongActionsSheet(
    visible: Boolean,
    onDismiss: () -> Unit,
    state: AppState,
    song: DemoSong,
    playlistContextId: String? = null,
) {
    var sub by remember { mutableStateOf(SongActionSub.None) }
    LaunchedEffect(visible) {
        if (!visible) sub = SongActionSub.None
    }
    // 可移除性判定统一走 isEditablePlaylist（内建常驻 + 自建可编辑；动态歌单由状态实时
    // 解析、不可写）。旧实现要求 "user-" 前缀 → 内建歌单「能加歌、删不掉」，
    // 用户加错歌没有任何自救途径（P2-3）。
    val canRemove = playlistContextId != null &&
        state.isEditablePlaylist(playlistContextId) &&
        state.isSongInPlaylist(playlistContextId, song.id)
    val favorite = state.isFavorite(song.id)
    // 系统集成动作（分享 / 设为铃声）：宿主经 LocalSystemActions 提供，未接线时两项都不渲染。
    // 走 CompositionLocal 而不是加形参：本面板有 5 个调用点，为新动作改 8 处签名
    // 既扩大并行开发冲突面，也让「再加一个系统动作」的边际成本居高不下（见 SystemActions.kt）。
    val sys = LocalSystemActions.current
    val onShareSong = sys.songShareAction(song)
    val onSetRingtone = sys.songRingtoneAction(song)
    GlassSheet(visible = visible && sub == SongActionSub.None, onDismiss = onDismiss, title = song.title) {
        SheetAction(
            icon = GlassIcons.PlayNext,
            label = "下一首播放",
            onClick = {
                state.playNext(song)
                onDismiss()
            },
        )
        SheetAction(
            icon = GlassIcons.PlayLast,
            label = "最后播放",
            onClick = {
                state.playLast(song)
                onDismiss()
            },
        )
        SheetAction(
            icon = if (favorite) GlassIcons.HeartFilled else GlassIcons.Heart,
            label = if (favorite) "取消收藏" else "收藏",
            emphasized = favorite,
            onClick = {
                state.toggleFavorite(song.id)
                onDismiss()
            },
        )
        SheetAction(
            icon = GlassIcons.Plus,
            label = "加入歌单",
            subtitle = "添加到已有歌单",
            onClick = { sub = SongActionSub.AddTo },
        )
        SheetAction(
            icon = GlassIcons.Disc,
            label = "查看专辑",
            // 副标题是**展示文案**：用纯专辑名（song.album），不是定位 id
            subtitle = song.album,
            onClick = {
                // 定位必须用 albumIdOf(song)：专辑分组键改造后是
                // (专辑名, 专辑艺术家) 二元组，`DetailTarget.Kind.Album` 按这个 id 匹配。
                // 旧实现直接传 song.album（纯专辑名），两只同名专辑会进到同一张详情页
                // ——更隐蔽的是「同专辑名的另一张」可能根本不包含当前这首歌，
                // 用户点「查看专辑」看到一张陌生专辑，还以为是 App 抽风。
                // albumIdOf 与分组用的是同一个编码函数（AppState.albumId），不会分叉。
                state.openDetail(DetailTarget(DetailTarget.Kind.Album, state.albumIdOf(song)))
                onDismiss()
            },
        )
        SheetAction(
            icon = GlassIcons.User,
            label = "查看艺术家",
            subtitle = song.artist,
            onClick = {
                state.openDetail(DetailTarget(DetailTarget.Kind.Artist, song.artist))
                onDismiss()
            },
        )
        if (canRemove) {
            SheetAction(
                icon = GlassIcons.MinusCircle,
                label = "从歌单移除",
                // A4：破坏性动作的视觉信号。严格说「移除单曲」是可逆的（可以再加回来），
                // 理想形态是「执行 + 5s 撤销窗口」而不是红色警告——但本 App 目前没有撤销
                // 机制（全仓零 Snackbar/Undo），在不可撤销的前提下它就该按破坏性对待。
                // 等撤销通道落地后，这一处应改回中性色 + 撤销提示，届时不要把它当回归。
                destructive = true,
                onClick = {
                    state.removeFromPlaylist(playlistContextId!!, song.id)
                    onDismiss()
                },
            )
        }
        SheetAction(
            icon = GlassIcons.Book,
            label = "歌曲信息",
            subtitle = songFormatText(
                song,
                if (song.id == state.currentSong.id) state.liveFormat else null,
            ),
            onClick = { sub = SongActionSub.Info },
        )
        // ── 系统集成：分享 / 设为铃声（P0-5 A）──
        //
        // 放在「歌曲信息」之后的末段而不是插在播放动作中间：分享/设铃声是低频动作，
        // 而「下一首播放/收藏/加入歌单」是高频动作——把低频项压在顶部会把高频项挤出
        // 面板首屏（GlassSheet 内容区限高 420dp，8 项已接近上限）。
        //
        // 两项都可能为 null（宿主未接线 / Compose Preview）：为 null 就不渲染，
        // 绝不渲染一个点了没反应的菜单项——这比没有这项更伤。
        if (onShareSong != null) {
            SheetAction(
                icon = GlassIcons.Share,
                label = "分享歌曲",
                subtitle = if (song.mediaUri == null) "这首歌没有音频文件，将分享歌名" else "发送给朋友",
                onClick = {
                    onShareSong()
                    onDismiss()
                },
            )
        }
        if (onSetRingtone != null) {
            SheetAction(
                icon = GlassIcons.Bell,
                label = "设为铃声",
                subtitle = if (song.mediaUri == null) "这首歌没有可用的音频文件" else "来电铃声",
                onClick = {
                    onSetRingtone()
                    onDismiss()
                },
            )
        }
    }
    AddToPlaylistSheet(visible = visible && sub == SongActionSub.AddTo, onDismiss = onDismiss, state = state, song = song)
    SongInfoSheet(visible = visible && sub == SongActionSub.Info, onDismiss = onDismiss, state = state, song = song)
}

/**
 * 「加入歌单」选择面板：列出内建 + 自建歌单，已含该曲的行显示"已添加"且点击不生效。
 *
 * **底部内联「新建歌单」（增-U1）**：旧实现只有已有歌单可选，于是「加歌时发现想建个新歌单」
 * 必须退面板 → 切歌单页 → ＋ → 输名 → 创建 → 找到新卡 → 点开 → ＋ → 选歌 → 提交，
 * 实测 11+ 步（成熟播放器 QQ音乐在加入面板底部就有「新建」行，4-5 步）。
 * 这里补一行内联新建：输入名 → 创建 → **立刻把当前曲加进去** → 关闭面板，
 * 用户全程不离开当前上下文。创建走 state.createPlaylist（已含重名去重/唯一化），
 * 加入走 state.addToPlaylist（已含去重），两步都是既有单点，无新状态。
 */
@Composable
fun AddToPlaylistSheet(
    visible: Boolean,
    onDismiss: () -> Unit,
    state: AppState,
    song: DemoSong,
) {
    // 内联新建的输入草稿：面板关闭即清（面板本就是新组合，这里显式自守，
    // 与同文件 AddSongsSheet 的 reset 同一纪律：组件契约不寄托在调用方式上）
    var newName by remember { mutableStateOf("") }
    // 是否展开内联新建输入框（默认收起：不干扰「从已有歌单选一个」这条主路径）
    var creating by remember { mutableStateOf(false) }
    LaunchedEffect(visible) {
        if (!visible) {
            newName = ""
            creating = false
        }
    }
    GlassSheet(visible = visible, onDismiss = onDismiss, title = "加入歌单") {
        (state.visibleBuiltInPlaylists() + state.userPlaylists).forEach { playlist ->
            val added = state.isSongInPlaylist(playlist.id, song.id)
            PlaylistPickRow(playlist = playlist, added = added, onClick = {
                // 已在歌单中的曲目点击不生效（保持面板打开，展示"已添加"反馈）
                if (state.addToPlaylist(playlist.id, song.id)) onDismiss()
            })
        }
        Spacer(Modifier.height(4.dp))
        // 重名预检与「新建歌单」面板同口径（playlistTitleExists 含内建/动态歌单）：
        // 重名时副标题提示且不可提交，绝不静默造第二张同名卡
        val duplicate = newName.trim().isNotEmpty() && state.playlistTitleExists(newName)
        if (creating) {
            GlassTextField(
                value = newName,
                onValueChange = { newName = it },
                hint = "新歌单名称",
                leading = null,
                imeAction = ImeAction.Done,
                onSearch = {
                    if (newName.isNotBlank() && !duplicate) {
                        val created = state.createPlaylist(newName)
                        state.addToPlaylist(created.id, song.id)
                        onDismiss()
                    }
                },
            )
            Spacer(Modifier.height(S.Gap8.dp))
        }
        SheetAction(
            icon = GlassIcons.Plus,
            label = if (creating) "创建并加入" else "新建歌单",
            subtitle = when {
                !creating -> "不用离开这里就能新建"
                newName.isBlank() -> "先输入名称"
                duplicate -> "已有同名歌单"
                else -> "「${newName.trim()}」"
            },
            emphasized = creating && newName.isNotBlank() && !duplicate,
            onClick = {
                if (!creating) {
                    creating = true
                } else if (newName.isNotBlank() && !duplicate) {
                    // 旧行为：创建后停留在歌单页找不到它；这里创建即加入当前曲并关闭，
                    // 用户要的「这首歌进新歌单」一步到位
                    val created = state.createPlaylist(newName)
                    state.addToPlaylist(created.id, song.id)
                    onDismiss()
                }
            },
        )
    }
}

/** 「加入歌单」选择行：封面 + 歌单名 + 计数；added = 已含该曲目 */
@Composable
private fun PlaylistPickRow(playlist: DemoPlaylist, added: Boolean, onClick: () -> Unit) {
    val interactionSource = remember { MutableInteractionSource() }
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(R.Card14.dp))
            .pressScale(interactionSource, pressed = 0.98f)
            .clickable(interactionSource = interactionSource, indication = null, onClick = onClick)
            .padding(horizontal = 6.dp, vertical = S.Gap8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(S.Gap12.dp),
    ) {
        ArtThumb(playlist.art, Modifier.size(40.dp), RoundedCornerShape(R.Thumb10.dp), bordered = true)
        Column(Modifier.weight(1f)) {
            Text(
                playlist.title, color = LocalGlassColors.current.textPrimary, style = GlassText.Row, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            Text(playlist.meta, color = LocalGlassColors.current.textTertiary, style = GlassText.Micro)
        }
        if (added) {
            Text("已添加", color = LocalGlassColors.current.textTertiary, style = GlassText.Micro)
        }
    }
}

/** 歌曲信息面板：标题 / 艺术家 / 专辑 / 碟号与音轨 / 时长 / 格式 / 文件夹 */
@Composable
fun SongInfoSheet(
    visible: Boolean,
    onDismiss: () -> Unit,
    state: AppState,
    song: DemoSong,
) {
    val live = if (song.id == state.currentSong.id) state.liveFormat else null
    GlassSheet(visible = visible, onDismiss = onDismiss, title = "歌曲信息") {
        InfoRow("标题", song.title)
        InfoRow("艺术家", song.artist)
        InfoRow("专辑", song.album)
        // 专辑艺术家：只在**与曲目艺术家不同**时才单列一行。
        // 绝大多数普通专辑的专辑艺术家就等于曲目艺术家，此时多一行只是重复信息；
        // 而合辑（专辑艺术家 = "Various Artists"）恰恰是用户最需要看到它的场景。
        // 判据与分组口径一致（albumArtist 空串 = 标签缺失），空则整行不出现。
        if (song.albumArtist.isNotBlank() && song.albumArtist != song.artist) {
            InfoRow("专辑艺术家", song.albumArtist)
        }
        // 未知字段（0/空）不占行：扫描未取到的标签不展示空值噪声
        if (song.genre.isNotBlank()) InfoRow("流派", song.genre)
        if (song.year > 0) InfoRow("年份", song.year.toString())
        // 碟号只在多碟专辑出现：单碟专辑 discNo 恒为 0（未知）或 1，单独列一行没有信息量
        if (song.discNo > 1) InfoRow("碟号", song.discNo.toString())
        if (song.trackNo > 0) InfoRow("音轨", song.trackNo.toString())
        InfoRow("时长", song.duration)
        // ── 格式：诚实性标注（审计 01 报告 14a）──
        // 关键取舍：mimeToCodec 是**真实容器格式**的映射（APE 就是 APE），它回答「这是什么」；
        // 而 playable 回答「本机能不能播」。两者必须分开——若为了让「APE 不许说自己 APE」
        // 而把 APE 从映射表里删掉，用户就再也看不到这是 APE 文件了，那是把「不诚实」
        // 换成了「信息缺失」，问题没解决。所以在格式串后面追加一句明确的「不支持播放」，
        // 让用户在点播放之前就知道结果，而不是点了才收到一个错误提示。
        val formatText = songFormatText(song, live)
        if (song.playable) {
            InfoRow("格式", formatText)
        } else {
            // 颜色用 tertiary 而非 error 红：这是**客观能力说明**不是故障，
            // 曲库里有它、能看能搜能看信息，只是本地播不了，不该用报错色制造焦虑
            InfoRow(
                "格式",
                "$formatText（当前设备不支持播放）",
                valueColor = LocalGlassColors.current.textTertiary,
            )
        }
        InfoRow("文件夹", song.folder)
    }
}

@Composable
private fun InfoRow(
    label: String,
    value: String,
    /** 值文本颜色：默认主要文字色；「能力说明」类灰色信息（如格式不支持）用 tertiary 降级 */
    valueColor: androidx.compose.ui.graphics.Color = LocalGlassColors.current.textPrimary,
) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = S.Gap8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, color = LocalGlassColors.current.textTertiary, style = GlassText.Body, modifier = Modifier.width(72.dp))
        Text(value, color = valueColor, style = GlassText.Body, modifier = Modifier.weight(1f))
    }
}

/**
 * 集合操作面板（歌单 / 专辑 / 艺术家 / 文件夹 / 流派详情与卡片长按共用）：
 * 全部播放 / 随机播放 / 下一首播放全部 / 最后播放全部 / 重命名歌单（仅自建）/
 * 删除歌单（歌单类，收藏/最近播放动态歌单除外）。
 * 播放行为经 [onPlayAll] 回调由调用方决定（含页面跳转）；删除后的兜底导航由详情页
 * data==null 分支自然完成，面板不感知路由。
 */
@Composable
fun CollectionActionsSheet(
    visible: Boolean,
    onDismiss: () -> Unit,
    state: AppState,
    target: DetailTarget,
    title: String,
    tracks: List<DemoSong>,
    onPlayAll: (shuffle: Boolean) -> Unit,
    /** D2：导出 M3U（null = 不提供该项，例如未接线 SAF 的场景） */
    onExportM3u: (() -> Unit)? = null,
) {
    var showRename by remember { mutableStateOf(false) }
    // P2-③：删除二次确认。旧实现单击 `删除歌单` 立即销毁歌单及全部曲目关系（实测一次误触
    // 即数据丢失，且无撤销入口）。改为两步：动作行只切到确认子面板，真正删除由确认行触发。
    var showDeleteConfirm by remember { mutableStateOf(false) }
    var newName by remember { mutableStateOf("") }
    LaunchedEffect(visible) {
        if (!visible) {
            showRename = false
            showDeleteConfirm = false
        }
    }
    LaunchedEffect(showRename) {
        if (showRename) {
            newName = state.userPlaylists.firstOrNull { it.id == target.id }?.title.orEmpty()
        }
    }
    // 重命名查重：与新建路径（PlaylistScreen.duplicate）同口径——user- 歌单与内建歌单标题
    // 都算冲突。必须排除「名字没改」的情况，否则用户点保存会被误报重名。
    // 旧实现重命名完全不查重，而 createPlaylist 有同名合并语义（同名返回现有歌单），
    // 于是能造出两个同名歌单，后续合聚会命中较早那个，内容落到意料之外的歌单。
    val trimmedName = newName.trim()
    val currentTitle = state.userPlaylists.firstOrNull { it.id == target.id }?.title.orEmpty()
    val duplicateName = trimmedName.isNotEmpty() && trimmedName != currentTitle &&
        state.playlistTitleExists(trimmedName)
    fun commitRename() {
        if (newName.isBlank() || duplicateName) return
        state.renamePlaylist(target.id, newName)
        onDismiss()
    }
    // 系统集成动作（分享歌单）：宿主经 LocalSystemActions 提供，未接线时该项不渲染。
    // 与 SongActionsSheet 同一套机制，理由见 SystemActions.kt 的 KDoc
    val sys = LocalSystemActions.current
    GlassSheet(visible = visible && !showRename && !showDeleteConfirm, onDismiss = onDismiss, title = title) {
        SheetAction(
            icon = GlassIcons.Play,
            label = "全部播放",
            subtitle = "${tracks.size} 首",
            onClick = {
                onDismiss()
                onPlayAll(false)
            },
        )
        SheetAction(
            icon = GlassIcons.Shuffle,
            label = "随机播放",
            subtitle = "从随机一首开始",
            onClick = {
                onDismiss()
                onPlayAll(true)
            },
        )
        SheetAction(
            icon = GlassIcons.PlayNext,
            label = "下一首播放全部",
            subtitle = "${tracks.size} 首插到当前曲之后",
            onClick = {
                state.playNextAll(tracks)
                onDismiss()
            },
        )
        SheetAction(
            icon = GlassIcons.PlayLast,
            label = "最后播放全部",
            subtitle = "${tracks.size} 首追加到队尾",
            onClick = {
                state.playLastAll(tracks)
                onDismiss()
            },
        )
        if (target.kind == DetailTarget.Kind.Playlist && target.id.startsWith("user-")) {
            SheetAction(
                icon = GlassIcons.Edit,
                label = "重命名歌单",
                onClick = { showRename = true },
            )
        }
        // D2：M3U 导出（任何歌单都可导出；动态歌单导出的就是当前解析结果）
        // **onExportM3u == null 时整项不渲染**（本轮修复）：旧实现只在 onClick 里写
        // `onExportM3u?.invoke()`，菜单项照常显示 —— 未接线场景下用户点下去只会
        // 关掉面板、什么都没发生，正是本文件与 SystemActions.kt 都反复点名的红线
        //（「点了没反应比没有这项更伤」）。当前唯一调用点恒传非空，所以这是防回归的守卫。
        val exportAction = onExportM3u
        if (target.kind == DetailTarget.Kind.Playlist && tracks.isNotEmpty() && exportAction != null) {
            SheetAction(
                icon = GlassIcons.Down,
                label = "导出 M3U 歌单",
                subtitle = "${tracks.size} 首 · 可在其他播放器打开",
                onClick = {
                    onDismiss()
                    exportAction()
                },
            )
        }
        // ── 分享歌单（P0-5 A）──
        // 与「导出 M3U」并列而不合并：两者语义不同——导出是「存到自己选的位置」，
        // 分享是「直接发给别人」。实现上分享正是「导出到私有临时目录 + ACTION_SEND」，
        // 但用户心智里是两个动作（就像相册里「导出」和「分享」并存）。
        // 已接线且歌单非空才出现（空歌单分享毫无意义，null 时不渲染）。
        val onSharePlaylist = sys.playlistShareAction(title, tracks)
        if (onSharePlaylist != null) {
            SheetAction(
                icon = GlassIcons.Share,
                label = "分享歌单",
                subtitle = "${tracks.size} 首 · 以 M3U8 歌单文件发送",
                onClick = {
                    onDismiss()
                    onSharePlaylist()
                },
            )
        }
        // 动态歌单（收藏 / 最近播放 / 最常播放）由状态实时解析，既不接受加歌也不允许删除：
        // 走 DemoCatalog.dynamicIds 集合判定，新增动态歌单无需再补这里的白名单
        val isDynamic = target.kind == DetailTarget.Kind.Playlist &&
            target.id in DemoCatalog.dynamicIds
        if (target.kind == DetailTarget.Kind.Playlist && !isDynamic) {
            SheetAction(
                icon = GlassIcons.Trash,
                label = "删除歌单",
                subtitle = "需要二次确认",
                // A4：这一项本身只是「进入确认子面板」，不立即销毁数据。但它是通往
                // 不可撤销操作**唯一**的入口，标上破坏性色能让用户在拿到这一步之前
                // 就意识到「这个分支通向不可逆」。真正的执行在下面的确认面板里。
                destructive = true,
                onClick = { showDeleteConfirm = true },
            )
        }
    }
    // 重命名子面板（输入当前名，空白名不可提交）
    GlassSheet(visible = visible && showRename, onDismiss = onDismiss, title = "重命名歌单") {
        GlassTextField(
            value = newName,
            onValueChange = { newName = it },
            hint = "歌单名称",
            leading = null,
            onSearch = { commitRename() },
        )
        Spacer(Modifier.height(S.Gap8.dp))
        SheetAction(
            icon = GlassIcons.Edit,
            label = "保存",
            subtitle = when {
                trimmedName.isEmpty() -> "先输入名称"
                duplicateName -> "已有同名歌单"
                else -> "「$trimmedName」"
            },
            emphasized = trimmedName.isNotEmpty() && !duplicateName,
            onClick = { commitRename() },
        )
    }
    // P2-③ 删除确认子面板：标题区写明「不可撤销」与受影响曲目数，确认行才真正删除。
    // 与重命名面板同构（同一 GlassSheet 容器、同一 showXxx 布尔互斥），不引新模态机制。
    GlassSheet(visible = visible && showDeleteConfirm, onDismiss = onDismiss, title = "删除歌单") {
        Text(
            "「$title」将从歌单列表移除，歌单内的 ${tracks.size} 首曲目关系一并清除，此操作不可撤销。",
            color = LocalGlassColors.current.textSecondary,
            style = GlassText.Body,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = S.Gap8.dp),
        )
        Spacer(Modifier.height(4.dp))
        SheetAction(
            icon = GlassIcons.Trash,
            label = "确认删除",
            subtitle = "「$title」· ${tracks.size} 首",
            // A4：这是全 App 唯一的「真正执行销毁」动作——不可撤销、清空整张歌单的
            // 曲目关系。它是破坏性语义的**终点**，必须与上面的「删除歌单」入口同一
            // 视觉语言，否则用户看到的是一路中性灰直到最后一击。
            destructive = true,
            onClick = {
                state.deletePlaylist(target.id)
                onDismiss()
            },
        )
        SheetAction(
            icon = GlassIcons.Back,
            label = "取消",
            subtitle = "保留歌单",
            onClick = { showDeleteConfirm = false },
        )
    }
}

/**
 * 「添加歌曲」面板（歌单选歌，与 AddToPlaylistSheet 的「歌选歌单」方向相反，不在菜单唯一实现约束内）：
 * 顶部搜索过滤（纯标题/艺术家包含匹配，同 searchScope 模式）+ 全曲库多选
 * （LazyColumn 懒加载，key = 稳定歌曲 id）；已在歌单的行置灰不可选；
 * 底部「添加 N 首」经 [onAdd] 一次性提交——mutator 由调用方单点走 addToPlaylist，本组件不写状态。
 * 状态自守：visible 转 false 时清空搜索词与选中集（不依赖「必须经 modal.show 组合」这一外部前提）。
 */
@Composable
fun AddSongsSheet(
    visible: Boolean,
    onDismiss: () -> Unit,
    state: AppState,
    playlistId: String,
    existingTracks: List<DemoSong>,
    onAdd: (List<DemoSong>) -> Unit,
) {
    var query by remember { mutableStateOf("") }
    val selected = remember { mutableStateListOf<String>() }
    // 自守的重置：面板每次关闭即清空搜索词与选中集。
    // 原先只在 KDoc 里声明「每次打开都是新组合，天然重置」——那依赖调用方必须经 modal.show
    // （宿主销毁 content 才会重建状态）。组件契约不该寄托在调用方式上：将来任何「常驻组合」
    // 的用法（新入口/预览/常驻侧栏）都会把上次的搜索词与选中集带出来。
    // 对照同文件 SongActionsSheet 的 sub 状态就是显式重置的，这里补齐同款。
    LaunchedEffect(visible) {
        if (!visible) {
            query = ""
            selected.clear()
        }
    }
    // 已在歌单的曲目 id 集：置灰不可选（id 为 f<路径> 稳定体系，重扫后仍对位）
    val existingIds = remember(existingTracks) { existingTracks.map { it.id }.toHashSet() }
    // 排序缓存（键 = library 引用 + 排序档 + 过滤开关，与曲库页同款）→ 过滤结果二次缓存
    // （P2-④：漏 scanFilterShort 会让「添加歌曲」列表仍列出已被过滤掉的短音频）
    val all = remember(state.library, state.songSort, state.scanFilterShort) { state.sortedSongs() }
    val songs = remember(all, query) {
        val q = query.trim()
        if (q.isEmpty()) all else all.filter { it.title.contains(q, true) || it.artist.contains(q, true) }
    }
    GlassSheet(visible = visible, onDismiss = onDismiss, title = "添加歌曲") {
        GlassTextField(
            value = query,
            onValueChange = { query = it },
            hint = "搜索歌曲或艺术家",
        )
        Spacer(Modifier.height(S.Gap12.dp))
        if (songs.isEmpty()) {
            // 空态：曲库未装载 / 搜索无结果
            Box(Modifier.fillMaxWidth().height(120.dp), contentAlignment = Alignment.Center) {
                Text(
                    if (query.isBlank()) "曲库还没有歌曲" else "没有匹配「${query.trim()}」的歌曲",
                    color = LocalGlassColors.current.textTertiary, style = GlassText.Body,
                )
            }
        } else {
            // 限高列表：GlassSheet 内容列自带 verticalScroll，LazyColumn 须持有界高度才可嵌套
            LazyColumn(Modifier.fillMaxWidth().heightIn(max = 280.dp)) {
                items(songs, key = { it.id }) { song ->
                    val added = song.id in existingIds
                    SongRow(
                        song = song,
                        // 已在歌单：整行降透明度置灰，点击不生效
                        modifier = if (added) Modifier.graphicsLayer { alpha = 0.38f } else Modifier,
                        selected = !added && song.id in selected,
                        trailing = if (added) "已添加" else song.duration,
                        onClick = {
                            if (!added) {
                                if (song.id in selected) selected.remove(song.id) else selected.add(song.id)
                            }
                        },
                    )
                }
            }
        }
        Spacer(Modifier.height(S.Gap12.dp))
        SheetAction(
            icon = GlassIcons.Plus,
            label = "添加 ${selected.size} 首",
            subtitle = if (selected.isEmpty()) "先在上方点选歌曲" else "加入「${state.resolvePlaylist(playlistId)?.title ?: "歌单"}」",
            emphasized = selected.isNotEmpty(),
            onClick = {
                if (selected.isNotEmpty()) {
                    // 按库序一次性提交（含被当前搜索过滤掉但仍处于选中集的曲目）
                    onAdd(all.filter { it.id in selected })
                    onDismiss()
                }
            },
        )
    }
}

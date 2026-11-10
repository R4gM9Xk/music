package com.local.music.core.ui.components

/**
 * 内建目录：动态歌单 id / 常驻歌单 / 曲目查找。
 *
 * ## 本轮清理记录（2026-11-10，只读审查 D1）
 *
 * 本文件原是一个「演示曲库目录」：静态 `albums`（5 个 DemoAlbum）、`lyrics`（设计稿
 * 演示歌词）、`searchSongs`、`album(id)`、以及 `DemoData.songs`（已恒为 `emptyList()`）。
 * 它们全部**不可达**：
 *
 * | 符号 | 状态 |
 * |---|---|
 * | `DemoAlbum` 类型 + `albums` | 零引用（唯一消费者是自己） |
 * | `album(id)` | 零调用 |
 * | `searchSongs(q)` | 零调用（`AppState.searchSongs` 是另一份实现） |
 * | `lyrics` | 零引用（只在一条 KDoc 里被提到） |
 * | `songById(id)` | 仅被 `AppState` 调用，而底层 `DemoData.songs` 恒为空 ⇒ 恒 null |
 *
 * 这层的代价不是体积而是**误导**：`AppState` 里因此存在两条完整的演示/真库分支、
 * `LibraryOrigin.Demo/Live` 的两套占位配色、以及 `searchScope()` 的非真库分支——
 * 全部建立在一个恒空的数据源上。删掉之后「曲库唯一来源 = MediaStore 扫描/缓存」
 * 这件事在代码里才没有反例。
 *
 * 保留的部分是**仍然活着**的：三个动态歌单 id（`AppState.resolvePlaylist` /
 * `isEditablePlaylist` / 各 UI 入口都在用）、`dynamicIds` 集合（集中判定的单一事实源）、
 * 四个常驻歌单壳（用户可加歌/删除，`deletePlaylist` 的 `hiddenBuiltInPlaylists` 分支依赖它）、
 * `playlist(id)`、以及 `songById`（作为 `AppState.songById` 的非真库兜底分支，
 * 对恒空列表是无害空转，但删掉它要动 AppState 的封板快照逻辑，故本轮保留并标注真实语义）。
 */

/** 带时间戳的歌词行（秒，Float 保留毫秒精度）：当前行由播放进度推导（点行 = seek 到该行） */
data class LyricLine(val text: String, val timeSec: Float)

data class DemoPlaylist(
    val id: String,
    val title: String,
    val meta: String,
    val art: ArtStyle,
    val tracks: List<DemoSong>,
)

/** 详情页导航目标：歌单 / 专辑 / 艺术家 / 文件夹 / 流派 共用同一详情模板 */
data class DetailTarget(val kind: Kind, val id: String) {
    enum class Kind { Playlist, Album, Artist, Folder, Genre }
}

/** 详情页解析结果（由 AppState 按目标 + 动态数据生成） */
data class DetailData(
    val header: String,   // 顶栏标题：歌单 / 专辑 / 艺术家 / 文件夹 / 流派
    val title: String,
    val meta: String,
    val art: ArtStyle,
    val tracks: List<DemoSong>,
    /** 真曲库首曲内嵌封面。**恒为 null**：扫描不再预解码全库封面，
     *  真正的封面来源是 [coverUri]（经 ArtworkBitmapCache 按需解码）。
     *  保留形参是因为 `ArtThumb(bitmap=)` 的优先级语义需要它占位（见 Artwork.kt）。 */
    val cover: androidx.compose.ui.graphics.ImageBitmap? = null,
    /** 首曲封面地址：扫描不再预解码全库封面，详情页大图改为按需解码（ArtworkBitmapCache） */
    val coverUri: String? = null,
)

object DemoCatalog {
    val FavoritesId = "__favorites"
    val RecentId = "__recent"
    val MostPlayedId = "__most_played"

    /**
     * 全部动态歌单 id（由 favorites / playedIds / playCounts **实时解析**生成，
     * 不接受加歌、删除、重排）。
     *
     * 必须集中登记的原因：动态歌单的「不可写」判定散落在多个 UI 入口
     * （加歌按钮、删除菜单项…），过去各处硬编码 `id == FavoritesId || id == RecentId`，
     * 新增 MostPlayedId 时漏改其中两处，导致「最常播放」出现加歌无效（写入的叠加层
     * 无人读取）与删除空跑（两个 deletePlaylist 分支都不进）两个缺陷。
     * **新增动态歌单时把 id 加进这里即可，不要再逐处写 if。**
     */
    val dynamicIds: Set<String> = setOf(FavoritesId, RecentId, MostPlayedId)

    /**
     * 常驻歌单（可删除 / 可加歌的空壳歌单；收藏 / 最近播放 / 常听由 AppState 动态解析）。
     *
     * 这里的 `tracks` 恒为空列表 —— 它**不是**「曲目数据」，而是「内建歌单的基座曲目」，
     * 演示曲库移除后基座就是空的，用户通过「加入歌单」往 `addedTracks` 叠加层里加歌。
     * 保留这个结构的意义：`deletePlaylist` 需要 `DemoCatalog.playlist(id) != null`
     * 来判定「这是内建歌单（删除 = 隐藏）而不是用户自建歌单（删除 = 真删）」。
     */
    val playlists = listOf(
        DemoPlaylist("pl-midnight", "深夜巡航", "0 首", ArtStyle.Night, emptyList()),
        DemoPlaylist("pl-commute", "通勤电台", "0 首", ArtStyle.Tide, emptyList()),
        DemoPlaylist("pl-rainy", "雨天单曲循环", "0 首", ArtStyle.Galaxy, emptyList()),
        DemoPlaylist("pl-lossless", "无损试音", "0 首", ArtStyle.Tape, emptyList()),
    )

    /**
     * 按 id 找曲目。**对当前工程恒返回 null**：底层 `DemoData.songs` 是空列表。
     *
     * 它仍被 `AppState.songById` 的非真库分支调用（`realLibraryLoaded == false` 时），
     * 那是个无作用的兜底查询 —— 保留是为了不动 AppState 的封板快照/恢复链；
     * 语义上应当读成「演示曲库已不存在，这里永远查不到」。
     */
    fun songById(id: String): DemoSong? = DemoData.songs.firstOrNull { it.id == id }

    fun playlist(id: String): DemoPlaylist? = playlists.firstOrNull { it.id == id }
}

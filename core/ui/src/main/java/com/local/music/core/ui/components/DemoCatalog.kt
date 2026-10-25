package com.local.music.core.ui.components

/**
 * 演示曲库目录：歌单 / 专辑 / 分组与搜索的静态数据层。
 * AppState 之上的内容源——所有屏的内容查询都从这里走，替换真实曲库时只动这一层。
 */

/** 带时间戳的歌词行（秒，Float 保留毫秒精度）：当前行由播放进度推导（点行 = seek 到该行） */
data class LyricLine(val text: String, val timeSec: Float)

data class DemoAlbum(
    val id: String,
    val title: String,
    val artist: String,
    val year: String,
    val art: ArtStyle,
    val tracks: List<DemoSong>,
) {
    val meta: String get() = "$artist · $year"
}

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
    val cover: androidx.compose.ui.graphics.ImageBitmap? = null, // 真曲库首曲内嵌封面
)

object DemoCatalog {
    val FavoritesId = "__favorites"
    val RecentId = "__recent"
    val MostPlayedId = "__most_played"

    /** 初始进度 98s（01:38）时高亮第 4 行，对齐设计稿 */
    val lyrics = listOf(
        LyricLine("窗外的城市退成一条线", 0f),
        LyricLine("霓虹在雨里慢慢晕开", 20f),
        LyricLine("我们坐在云层的上面", 44f),
        LyricLine("把整片夜色都装进口袋", 70f),
        LyricLine("耳机里循环着那首旧歌", 100f),
        LyricLine("你说远方其实并不远", 130f),
        LyricLine("只是我们一直在路上", 158f),
    )

    val albums = listOf(
        DemoAlbum("city-edge", "城市边缘", "林之遥", "2024", ArtStyle.Night,
            DemoData.songs.filter { it.album == "城市边缘" }),
        DemoAlbum("tide-album", "潮汐信号", "海边的卡夫卡", "2023", ArtStyle.Tide,
            DemoData.songs.filter { it.album == "潮汐信号" }),
        DemoAlbum("slow-down", "慢下来", "沈青", "2023", ArtStyle.Afternoon,
            DemoData.songs.filter { it.album == "慢下来" }),
        DemoAlbum("long-haul", "长途", "远山乐队", "2023", ArtStyle.Galaxy,
            DemoData.songs.filter { it.album == "长途" }),
        DemoAlbum("reissue", "复刻", "周一", "2024", ArtStyle.Tape,
            DemoData.songs.filter { it.album == "复刻" }),
    )

    /** 常驻歌单（曲目随演示曲库移除已清空，作为可删除/可加歌的空壳歌单；收藏 / 最近播放 / 用户自建由 AppState 动态解析） */
    val playlists = listOf(
        DemoPlaylist("pl-midnight", "深夜巡航", "0 首", ArtStyle.Night, listOf("night-flight", "white-room", "tide-signal", "galaxy", "afternoon").mapById()),
        DemoPlaylist("pl-commute", "通勤电台", "0 首", ArtStyle.Tide, listOf("tide-signal", "rain-tower", "galaxy", "night-shuttle", "afternoon", "south-story").mapById()),
        DemoPlaylist("pl-rainy", "雨天单曲循环", "0 首", ArtStyle.Galaxy, listOf("rain-tower", "night-flight", "white-room", "tide-signal").mapById()),
        DemoPlaylist("pl-lossless", "无损试音", "0 首", ArtStyle.Tape, listOf("old-tape", "night-flight", "galaxy").mapById()),
    )

    fun songById(id: String): DemoSong? = DemoData.songs.firstOrNull { it.id == id }
    fun album(id: String): DemoAlbum? = albums.firstOrNull { it.id == id }
    fun playlist(id: String): DemoPlaylist? = playlists.firstOrNull { it.id == id }

    /** 搜索：标题/艺术家/专辑 contains，忽略大小写，命中全部演示曲库（含伴奏/单曲） */
    fun searchSongs(query: String): List<DemoSong> {
        val q = query.trim()
        if (q.isEmpty()) return emptyList()
        return DemoData.songs.filter {
            it.title.contains(q, true) || it.artist.contains(q, true) || it.album.contains(q, true)
        }
    }

    private fun List<String>.mapById(): List<DemoSong> = mapNotNull { songById(it) }
}

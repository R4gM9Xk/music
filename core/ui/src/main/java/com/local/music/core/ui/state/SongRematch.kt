package com.local.music.core.ui.state

import com.local.music.core.ui.components.DemoSong

/**
 * 备份换机 re-match（P0-5 B）：把「备份里记录的旧曲目 id」重新绑定到「本机当前曲库的新曲目 id」。
 *
 * ## 为什么需要这一层（02 报告最致命缺口③）
 *
 * 曲目 id 的约定是 `"f" + 文件绝对路径`（见 MediaLibrary 扫描处与 M3uPlaylistIO 的 pathOf）。
 * 用路径当 id 的理由很硬：MediaStore `_id` 会在重扫/数据库重建后漂移，只有路径跨扫描稳定。
 * 但它有唯一的致命副作用——**换机后路径必然全变**（`/storage/emulated/0/Music/a.mp3` 在某台
 * 机器上可能变成 `/storage/emulated/0/Download/音乐/a.mp3`，主用户/存储挂载点也可能不同）。
 *
 * 于是：备份 JSON 里的 `favorites / playedIds / playCounts / userPlaylists / addedTracks`
 * 引用全部指向旧路径 → `AppState.songById()` 返回 null → `resolvePlaylist` 的
 * `mapNotNull {}` **静默丢弃** → 用户看到「我恢复备份了，但歌单全空了」，而且**没有任何提示**。
 * 对纯本地播放器，这比任何 UI 缺陷都致命：攒了两年的歌单换台手机就没了。
 *
 * ## 本对象的定位
 *
 * 纯粹的重绑定算法——**不碰 Context / MediaStore / Compose**，入参出参全是普通数据，
 * 因此可以写成不依赖 Robolectric 的 JVM 单测（见 app 模块 SongRematchTest）。
 * 调用方（AppState）负责：收集待匹配 id、回写各引用集合、生成用户可见的恢复报告。
 *
 * ## 四级匹配（优先级从高到低，一旦命中即停止下沉）
 *
 * | 级别 | 判据 | 典型场景 |
 * |------|------|----------|
 * | 1 | 文件名 + 时长（±2s） | 同目录整体搬走、改名目录：最可靠 |
 * | 2 | 文件名 | 文件重编码导致时长微变、或元数据缺失拿不到时长 |
 * | 3 | 标题 + 艺术家 + 专辑 | 用户手动改了文件名，但标签完好 |
 * | 4 | 标题 + 艺术家 | 换机时被并入扁平目录 / 专辑名被工具改写 |
 *
 * 之所以坚持「文件名优先于标签」：国内用户的本地曲库大量来自「下载时按文件名存、
 * 标签残缺」的渠道，文件名往往比 ID3 更贴近真实身份；而标题+艺术家对「同名翻唱/翻录」
 * 反而容易误配——所以标签匹配放在最后两级，且要求命中唯一。
 *
 * ## 两个刻意的取舍
 *
 * 1. **同一个新曲目只允许被一条旧引用认领**（[claimed] / [claimedOld]）。否则两条旧引用
 *    （例如同一首歌在收藏与歌单里各存了一份、路径写法不同）会同时绑到一首歌上，
 *    回写后 Set 去重会静默吞掉一条——用户看到「歌单少了一首」却查不出原因。
 * 2. **候选不唯一时按稳定规则收敛**（时长最接近 → 曲库顺序靠前），而不是放弃匹配。
 *    放弃匹配的代价是「歌单里凭空消失一首」，而绑定到同名的另一首至少还留在歌单里、
 *    用户能自己发现并修正；且规则确定（同样的输入永远得到同样的结果），不会两次恢复
 *    绑定到不同的歌。
 */
object SongRematch {

    /** 时长比较容差（秒）：同一文件在不同 MediaStore 实现里取的时长有 1-2s 级抖动 */
    const val DURATION_TOLERANCE_SEC = 2

    /** 级别编号（对外可读的常量，报告/单测都引用它，避免到处写魔法数字 1..4） */
    const val LEVEL_NAME_DURATION = 1
    const val LEVEL_NAME = 2
    const val LEVEL_TITLE_ARTIST_ALBUM = 3
    const val LEVEL_TITLE_ARTIST = 4

    /**
     * 单曲「身份指纹」：备份里随 id 一起存下来的最小元数据。
     *
     * 为什么备份必须额外存它：id 里只有路径，**推导不出标题/艺术家/专辑/时长**，
     * 而 3、4 级匹配与 1 级的时长校验全靠这些字段。旧备份（本字段不存在的版本）
     * 解码为 null，此时只能退化为 1、2 级（文件名）匹配——仍然比现在的「全丢」好得多。
     *
     * 字段全带默认值：JSON 里缺键时走默认，不抛异常（备份格式向前兼容的唯一手法）。
     */
    data class TrackRef(
        val title: String = "",
        val artist: String = "",
        val album: String = "",
        val durationSec: Int = 0,
    )

    /** 一条待重绑定的旧引用：[oldId] 是备份里的 id，[ref] 可能为 null（旧备份没存指纹） */
    data class MissingRef(val oldId: String, val ref: TrackRef?)

    /**
     * 匹配结果。
     *
     * @param mapping 旧 id → 新 id（只含成功重绑定项）
     * @param unresolved 没能匹配上的旧 id（**调用方必须保留这些引用而不是删掉**）
     * @param levelHits 各级命中数量（key = LEVEL_*），便于日志排查「为什么匹配错了」
     */
    data class Result(
        val mapping: Map<String, String>,
        val unresolved: List<String>,
        val levelHits: Map<Int, Int>,
    ) {
        fun hitsOf(level: Int): Int = levelHits[level] ?: 0
    }

    /**
     * 四级重绑定。
     *
     * @param missing 待匹配项（调用方应只传「当前曲库里确实找不到」的 id；已在库中的不需要传）
     * @param library 当前曲库全量（不可见/被过滤目录也应包含，否则用户刚拷进来的歌匹配不到）
     */
    fun rematch(missing: List<MissingRef>, library: List<DemoSong>): Result {
        // 空输入直接短路：不要为了让「没有待匹配项」也走一遍索引构建而白白分配几张大表
        if (missing.isEmpty() || library.isEmpty()) {
            return Result(emptyMap(), missing.map { it.oldId }, emptyMap())
        }

        // 已认领的新曲目：保证一首新歌只服务一条旧引用（见 KDoc 取舍①）
        val claimed = HashSet<String>(missing.size * 2)
        // 已解决的旧 id：同一级别内不要再处理（避免同一旧引用被后续级别重复命中）
        val claimedOld = HashSet<String>(missing.size * 2)
        val mapping = LinkedHashMap<String, String>(missing.size * 2)
        val levelHits = HashMap<Int, Int>(8)

        // 待处理队列：remaining 随级别推进逐级收缩
        var remaining: List<MissingRef> = missing

        /** 三级索引只在第一轮建一次：每级重扫整个曲库会是 O(级别数 × 曲库) */
        val byName by lazy {
            val m = HashMap<String, MutableList<DemoSong>>(library.size * 2)
            library.forEach { song -> m.getOrPut(norm(fileNameOf(song))) { mutableListOf() }.add(song) }
            m
        }
        val byTitleArtistAlbum by lazy {
            val m = HashMap<String, MutableList<DemoSong>>(library.size * 2)
            library.forEach { song ->
                val key = keyOf(song.title, song.artist, song.album)
                if (key != null) m.getOrPut(key) { mutableListOf() }.add(song)
            }
            m
        }
        val byTitleArtist by lazy {
            val m = HashMap<String, MutableList<DemoSong>>(library.size * 2)
            library.forEach { song ->
                val key = keyOf(song.title, song.artist)
                if (key != null) m.getOrPut(key) { mutableListOf() }.add(song)
            }
            m
        }

        /**
         * 提交一条命中。返回 true = 已绑定。
         *
         * @param accept 额外的候选过滤（1 级用它做 ±2s 时长容差）；null = 不过滤
         */
        fun commit(
            level: Int,
            candidates: List<DemoSong>?,
            item: MissingRef,
            ref: TrackRef?,
            accept: ((DemoSong) -> Boolean)? = null,
        ): Boolean {
            // 候选里剔掉已被认领的：跨级别共享同一份 claimed
            val free = candidates?.filter { it.id !in claimed && accept?.invoke(it) != false }.orEmpty()
            if (free.isEmpty()) return false
            val chosen = pickBest(free, ref)
            // 自成环防御：调用方本应只传「库里找不到」的 id，但万一误传已在库的 id，
            // 映射成它自己等于「回写后什么都没变」，还会污染 resolved 统计误导用户
            if (chosen.id == item.oldId) return false
            claimed += chosen.id
            claimedOld += item.oldId
            mapping[item.oldId] = chosen.id
            levelHits[level] = (levelHits[level] ?: 0) + 1
            return true
        }

        // 逐级下沉：每级只处理上一级剩下的条目
        val levels = listOf(
            LEVEL_NAME_DURATION,
            LEVEL_NAME,
            LEVEL_TITLE_ARTIST_ALBUM,
            LEVEL_TITLE_ARTIST,
        )
        for (level in levels) {
            if (remaining.isEmpty()) break
            remaining = remaining.filter { it.oldId !in claimedOld }.filter { item ->
                val ref = item.ref
                when (level) {
                    LEVEL_NAME_DURATION -> {
                        // 有指纹且时长已知才有意义：时长 0 = 未知，拿 0 去比会把「时长缺失」
                        // 与「时长真是 0」混为一谈，宁可下沉到 2 级（纯文件名）也不要误配
                        if (ref == null || ref.durationSec <= 0) return@filter true
                        val name = norm(basenameOf(item.oldId))
                        if (name.isEmpty()) return@filter true
                        // 1 级是唯一带**硬容差**的一级：文件名相同但时长差一大截 = 同名的另一首
                        // （现场版/翻录/同名单曲），不能算命中，必须下沉到 2 级由「只认文件名」兜住
                        commit(level, byName[name], item, ref) { candidate ->
                            kotlin.math.abs(candidate.durationSec - ref.durationSec) <= DURATION_TOLERANCE_SEC
                        }
                        item.oldId !in claimedOld
                    }
                    LEVEL_NAME -> {
                        val name = norm(basenameOf(item.oldId))
                        if (name.isEmpty()) return@filter true
                        commit(level, byName[name], item, ref)
                        item.oldId !in claimedOld
                    }
                    LEVEL_TITLE_ARTIST_ALBUM -> {
                        // 标题为空是「标签残缺」而不是身份：空标题之间互相匹配等于随机绑歌
                        if (ref == null || ref.title.isBlank()) return@filter true
                        commit(level, byTitleArtistAlbum[keyOf(ref.title, ref.artist, ref.album)], item, ref)
                        item.oldId !in claimedOld
                    }
                    else -> {
                        if (ref == null || ref.title.isBlank()) return@filter true
                        commit(level, byTitleArtist[keyOf(ref.title, ref.artist)], item, ref)
                        item.oldId !in claimedOld
                    }
                }
            }
        }

        val unresolved = missing.map { it.oldId }.filter { it !in mapping }
        return Result(mapping, unresolved, levelHits)
    }

    /**
     * 候选裁决：多首候选时按「时长最接近 → 曲库顺序」收敛，保证确定性与稳定性。
     *
     * 为什么不用「有歧义就放弃」：放弃 = 用户歌单里凭空少一首且无法解释；绑到同名同长的
     * 另一份副本（例如同一张专辑的两次下载）仍是同一首歌的概率极高，用户能自己发现并修正。
     * 排序用曲库原序（稳定序）：不引入随机/时间相关因素，单测可断言。
     */
    private fun pickBest(candidates: List<DemoSong>, ref: TrackRef?): DemoSong {
        if (candidates.size == 1) return candidates[0]
        val dur = ref?.durationSec ?: 0
        return if (dur > 0) {
            // sortedBy 是稳定排序：时长完全相同时保持曲库原序
            candidates.sortedBy { kotlin.math.abs(it.durationSec - dur) }.first()
        } else {
            candidates.first()
        }
    }

    /** 组合键：任一必填项为空则返回 null（= 该级对这条引用不可用，直接下沉） */
    private fun keyOf(title: String, artist: String, album: String? = null): String? {
        val t = norm(title)
        if (t.isEmpty()) return null
        val a = norm(artist)
        // 专辑可能合法为空（无标签专辑）：空串参与比较即可，不做额外特判，
        // 否则「两条都没有专辑」会被误判成不可比较而白白下沉一级
        val al = if (album == null) "" else norm(album)
        return if (album == null) "$t\u0000$a" else "$t\u0000$a\u0000$al"
    }

    /** 归一化：trim + 小写。locale 固定用 ROOT —— 默认 locale 在 tr-TR 下会把 I 折成点less i */
    private fun norm(s: String): String = s.trim().lowercase(java.util.Locale.ROOT)

    /**
     * 从曲目 id 还原文件名（含扩展名）。
     *
     * id 约定 = `"f" + 绝对路径`。同时容忍反斜杠写法（备份经 Windows 编辑器/中转工具
     * 处理过的极端情形），否则整条链路在这里静默失配。
     */
    fun fileNameOf(song: DemoSong): String = basenameOf(song.id)

    /**
     * 从「f + 路径」形态的 id 取文件名。
     *
     * 兜底：既不是 `f` 前缀、又没有路径分隔符的 id（演示曲目 / placeholder 这类）原样返回，
     * 让它走「文件名恰好等于 id」的退化路径而不是抛异常或返回空串。
     */
    fun basenameOf(id: String): String {
        val path = id.removePrefix("f").replace('\\', '/')
        return path.substringAfterLast('/').trim()
    }
}

/**
 * 备份恢复报告：给用户看的那一份。
 *
 * 为什么必须存在：旧实现恢复完只弹一句「备份已恢复」，而实际上可能有几十条引用被
 * `mapNotNull` 静默丢掉。用户看到的是「歌单空了」却没有任何解释——本报告就是
 * 「不能再静默」这条验收要求的载体，由 GlassApp 顶部横幅展示。
 *
 * @param reused 路径没变、直接命中的引用数
 * @param resolved 经 re-match 重新绑定的引用数（> 0 说明路径确实变了）
 * @param missing 完全没找到的引用数（引用本身被保留在数据里，不会被删）
 * @param missingSamples 未找到项的可读样本（曲名或文件名，最多几个）—— 只报数字用户无从下手
 */
data class RestoreReport(
    val reused: Int,
    val resolved: Int,
    val missing: Int,
    val missingSamples: List<String> = emptyList(),
    /** 是否来自「备份文件恢复」（true）而非冷启动读本地快照（false）——决定要不要无条件提示 */
    val fromBackup: Boolean = false,
) {
    val total: Int get() = reused + resolved + missing

    /** 有值得说的内容：发生过重绑定，或存在找不到的条目 */
    val needsAttention: Boolean get() = resolved > 0 || missing > 0
}

/** 未找到样本最多展示几条：横幅最多 3 行，再多就是噪音 */
const val RESTORE_REPORT_SAMPLE_LIMIT = 3

/**
 * 恢复报告的横幅文案（纯函数，可单测）。
 *
 * 文案三种形态分别对应三种真实处境：
 * - 全部命中（resolved == 0）：一句话确认，不制造焦虑；
 * - 部分/全部重新匹配（resolved > 0）：明确说「按文件名重新匹配」，让用户知道
 *   **不是**丢了而是换了路径，否则他会怀疑恢复失败；
 * - 有未找到项：给数量 + 可读样本 + 「文件路径已变化或未拷贝」的原因提示。
 */
fun restoreReportMessage(report: RestoreReport): String {
    val restored = report.reused + report.resolved
    return when {
        report.total == 0 -> "备份已恢复（其中没有曲目引用）"
        report.missing == 0 && report.resolved == 0 -> "已恢复全部 ${report.total} 首曲目"
        report.missing == 0 ->
            "已恢复 $restored 首，其中 ${report.resolved} 首按文件名重新匹配（换机后文件路径已变化）"
        restored == 0 ->
            "备份里的 ${report.missing} 首一首都没找到：文件路径已变化，或音乐还没拷贝到本机"
        else -> buildString {
            append("已恢复 $restored 首，${report.missing} 首未找到（文件路径已变化或未拷贝）")
            if (report.missingSamples.isNotEmpty()) {
                append("：").append(report.missingSamples.joinToString("、"))
                if (report.missing > report.missingSamples.size) append(" 等")
            }
        }
    }
}

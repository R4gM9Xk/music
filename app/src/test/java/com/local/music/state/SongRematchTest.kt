package com.local.music.state

import com.local.music.core.ui.components.ArtStyle
import com.local.music.core.ui.components.DemoSong
import com.local.music.core.ui.state.RESTORE_REPORT_SAMPLE_LIMIT
import com.local.music.core.ui.state.RestoreReport
import com.local.music.core.ui.state.SongRematch
import com.local.music.core.ui.state.restoreReportMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 备份换机 re-match 单测（P0-5 B 的验收口）。
 *
 * 为什么这些用例必须存在：re-match 是「换机后用户两年歌单还在不在」的唯一开关。
 * 它不像 UI 缺陷那样能被看见——匹配错了用户只会觉得「歌单里的歌好像不对」，
 * 匹配失败用户只会觉得「我恢复备份了但歌单空了」。所以四级优先级与「全失败」
 * 这两条路径必须被断言死，而不是靠手点。
 *
 * 测试全部是纯 JVM：不碰 Context / MediaStore / Compose（算法一层刻意抽成纯函数，
 * 见 [SongRematch] 的 KDoc）。曲库用小规模字面量构造，断言可读。
 */
class SongRematchTest {

    /** 造一首真实曲库里的曲目：id 约定 = "f" + 绝对路径（与 MediaLibrary 扫描一致） */
    private fun song(
        path: String,
        title: String = path.substringAfterLast('/').substringBeforeLast('.'),
        artist: String = "artist",
        album: String = "album",
        durationSec: Int = 200,
    ) = DemoSong(
        id = "f$path",
        title = title,
        artist = artist,
        album = album,
        durationSec = durationSec,
        art = ArtStyle.Night,
        folder = path.substringBeforeLast('/', "音乐").substringAfterLast('/'),
    )

    /** 造一条「备份里存在、但本机曲库里找不到」的旧引用 */
    private fun missing(
        oldPath: String,
        title: String = oldPath.substringAfterLast('/').substringBeforeLast('.'),
        artist: String = "artist",
        album: String = "album",
        durationSec: Int = 200,
    ) = SongRematch.MissingRef(
        oldId = "f$oldPath",
        ref = SongRematch.TrackRef(title, artist, album, durationSec),
    )

    // ── 级 1：文件名 + 时长（±2s）─────────────────────────────

    @Test
    fun `一级匹配 文件名相同且时长在容差内即命中`() {
        // 场景：用户把整个 Music 目录从 Download 挪到 Music/，路径全变、文件名不变
        val library = listOf(
            song("/storage/emulated/0/Music/夜航西飞.flac", title = "夜航西飞", durationSec = 245),
            song("/storage/emulated/0/Music/其他.mp3", title = "其他", durationSec = 180),
        )
        val old = listOf(
            missing("/storage/emulated/0/Download/夜航西飞.flac", title = "夜航西飞", durationSec = 246),
        )

        val r = SongRematch.rematch(old, library)

        assertEquals(1, r.mapping.size)
        assertEquals(
            "f/storage/emulated/0/Music/夜航西飞.flac",
            r.mapping["f/storage/emulated/0/Download/夜航西飞.flac"],
        )
        assertEquals(1, r.hitsOf(SongRematch.LEVEL_NAME_DURATION))
        assertTrue(r.unresolved.isEmpty())
    }

    @Test
    fun `一级匹配 时长差超过容差则下沉到二级按文件名命中`() {
        // 同名但时长差 60s = 同名的另一首（现场版/翻录）。一级不能认，
        // 但二级「只认文件名」仍应把它接上——这是刻意的：宁可留在歌单里让用户自己挪走，
        // 也好过凭空消失。
        val library = listOf(
            song("/storage/emulated/0/Music/Intro.mp3", title = "Intro", durationSec = 60),
        )
        val old = listOf(
            missing("/old/path/Intro.mp3", title = "Intro", durationSec = 200),
        )

        val r = SongRematch.rematch(old, library)

        assertEquals(0, r.hitsOf(SongRematch.LEVEL_NAME_DURATION))
        assertEquals(1, r.hitsOf(SongRematch.LEVEL_NAME))
        assertEquals("f/storage/emulated/0/Music/Intro.mp3", r.mapping["f/old/path/Intro.mp3"])
    }

    @Test
    fun `一级匹配 容差边界 2 秒内命中 3 秒外下沉`() {
        // 容差是 ±2s（含边界）。这里用同一份曲库分别做「刚好 2s」与「刚好 3s」两条，
        // 钉死 <= 而不是 < —— 差 1s 都不能改。
        val library = listOf(song("/new/a.mp3", durationSec = 200))

        val withinStr = SongRematch.rematch(
            listOf(missing("/old/a.mp3", durationSec = 202)),
            library,
        )
        assertEquals(1, withinStr.hitsOf(SongRematch.LEVEL_NAME_DURATION))

        val outsideStr = SongRematch.rematch(
            listOf(missing("/old/a.mp3", durationSec = 203)),
            library,
        )
        assertEquals(0, outsideStr.hitsOf(SongRematch.LEVEL_NAME_DURATION))
        assertEquals(1, outsideStr.hitsOf(SongRematch.LEVEL_NAME))
    }

    // ── 级 2：文件名（时长未知/不符）─────────────────────────

    @Test
    fun `二级匹配 备份缺时长时仍能按文件名命中`() {
        // 旧备份没有 durationSec（TrackRef 的默认 0）：一级因「时长未知」被跳过，
        // 必须靠二级兜住。这是旧备份兼容的唯一路径。
        val library = listOf(song("/new/歌.mp3", title = "完全不同的标题"))
        val old = listOf(
            SongRematch.MissingRef("f/old/歌.mp3", SongRematch.TrackRef(title = "歌", durationSec = 0)),
        )

        val r = SongRematch.rematch(old, library)

        assertEquals(1, r.hitsOf(SongRematch.LEVEL_NAME))
        assertEquals("f/new/歌.mp3", r.mapping["f/old/歌.mp3"])
    }

    // ── 级 3：标题 + 艺术家 + 专辑 ────────────────────────────

    @Test
    fun `三级匹配 文件名被改名后按标题艺术家专辑命中`() {
        // 场景：用户整理曲库时把 "01 未命名.mp3" 改成了有意义的文件名，标签没动
        val library = listOf(
            song(
                "/storage/emulated/0/Music/01 夜航西飞.mp3",
                title = "夜航西飞", artist = "陈鸿宇", album = "一如年少模样", durationSec = 245,
            ),
            song("/storage/emulated/0/Music/x.mp3", title = "别的歌", artist = "别的", album = "别的"),
        )
        val old = listOf(
            missing(
                "/旧的/track12.mp3",
                title = "夜航西飞", artist = "陈鸿宇", album = "一如年少模样", durationSec = 245,
            ),
        )

        val r = SongRematch.rematch(old, library)

        assertEquals("f/storage/emulated/0/Music/01 夜航西飞.mp3", r.mapping["f/旧的/track12.mp3"])
        assertEquals(1, r.hitsOf(SongRematch.LEVEL_TITLE_ARTIST_ALBUM))
        assertEquals(0, r.hitsOf(SongRematch.LEVEL_TITLE_ARTIST))
    }

    @Test
    fun `三级匹配 专辑不同则不认 必须下沉到四级`() {
        // 同名同艺术家、不同专辑 = 不同专辑版本。三级要求专辑完全一致，
        // 这里库里只有另一张专辑，所以三级落空、四级（标题+艺术家）接住。
        // 旧 id 的文件名刻意与库里的 diag.a.mp3 不同：否则一二级的文件名匹配会先命中，
        // 这条用例就测不到三级/四级的判定逻辑了。
        val library = listOf(
            song("/new/diag.a.mp3", title = "告白气球", artist = "周杰伦", album = "周杰伦的床边故事"),
        )
        val old = listOf(
            missing("/old/oldname.mp3", title = "告白气球", artist = "周杰伦", album = "另一张专辑"),
        )

        val r = SongRematch.rematch(old, library)

        assertEquals(0, r.hitsOf(SongRematch.LEVEL_TITLE_ARTIST_ALBUM))
        assertEquals(1, r.hitsOf(SongRematch.LEVEL_TITLE_ARTIST))
    }

    // ── 级 4：标题 + 艺术家 ──────────────────────────────────

    @Test
    fun `四级匹配 换机后被并入扁平目录且专辑名被改写`() {
        // 场景：从旧手机的整体 Music 目录拷到新手机时被某个工具重写了 album 标签。
        // 旧文件名刻意与库里的不同：否则一二级的文件名匹配会先命中，测不到四级。
        val library = listOf(
            song("/new/flat/088.mp3", title = "夜航西飞", artist = "陈鸿宇", album = "Unknown Album"),
        )
        val old = listOf(
            missing("/old/deep/夜航西飞.flac", title = "夜航西飞", artist = "陈鸿宇", album = "一如年少模样"),
        )

        val r = SongRematch.rematch(old, library)

        assertEquals("f/new/flat/088.mp3", r.mapping["f/old/deep/夜航西飞.flac"])
        assertEquals(1, r.hitsOf(SongRematch.LEVEL_TITLE_ARTIST))
    }

    @Test
    fun `四级 空标题不参与标签匹配 避免随机绑歌`() {
        // 标题为空 = 标签残缺，不是身份。两条空标题互相匹配等于把歌单随机重排。
        // 备份侧时长未知（0）→ 一级跳过，只剩文件名能救 —— 这是预期行为，
        // 关键是**没有**走 3/4 级标签匹配。
        val library = listOf(song("/new/x.mp3", title = "", artist = "", album = ""))
        val old = listOf(missing("/old/x.mp3", title = "", artist = "", album = "", durationSec = 0))

        val r = SongRematch.rematch(old, library)

        assertEquals(0, r.hitsOf(SongRematch.LEVEL_TITLE_ARTIST_ALBUM))
        assertEquals(0, r.hitsOf(SongRematch.LEVEL_TITLE_ARTIST))
        assertEquals(1, r.hitsOf(SongRematch.LEVEL_NAME))
    }

    // ── 全部匹配失败 ────────────────────────────────────────

    @Test
    fun `全部匹配失败时 所有旧 id 都进 unresolved 且不产生映射`() {
        // 本机曲库与备份完全不搭（换机后音乐一首都没拷过来）
        val library = listOf(
            song("/storage/emulated/0/Music/别的歌.mp3", title = "别的歌", artist = "别人", album = "别的"),
            song("/storage/emulated/0/Music/再来一首.mp3", title = "再来一首", artist = "别人", album = "别的"),
        )
        val old = listOf(
            missing("/old/夜航西飞.flac", title = "夜航西飞", artist = "陈鸿宇", album = "一如年少模样"),
            missing("/old/理想三旬.flac", title = "理想三旬", artist = "陈鸿宇", album = "一如年少模样"),
            missing("/old/成都.flac", title = "成都", artist = "赵雷", album = "无法长大"),
        )

        val r = SongRematch.rematch(old, library)

        assertTrue("不该凭空产生映射", r.mapping.isEmpty())
        assertEquals(3, r.unresolved.size)
        assertEquals(
            listOf("f/old/夜航西飞.flac", "f/old/理想三旬.flac", "f/old/成都.flac"),
            r.unresolved,
        )
        assertEquals(0, r.hitsOf(SongRematch.LEVEL_NAME_DURATION))
        assertEquals(0, r.hitsOf(SongRematch.LEVEL_NAME))
        assertEquals(0, r.hitsOf(SongRematch.LEVEL_TITLE_ARTIST_ALBUM))
        assertEquals(0, r.hitsOf(SongRematch.LEVEL_TITLE_ARTIST))
    }

    @Test
    fun `空曲库时 全部引用都是 unresolved 而不是崩溃`() {
        // 换机后还没扫到音乐（权限未给的新机）：必须安静地全判 unresolved，
        // 让上层去提示「音乐还没拷贝到本机」，而不是抛异常把恢复流程整个打断。
        val r = SongRematch.rematch(listOf(missing("/old/a.mp3")), emptyList())
        assertTrue(r.mapping.isEmpty())
        assertEquals(listOf("f/old/a.mp3"), r.unresolved)
    }

    @Test
    fun `空引用列表时 直接返回空结果`() {
        val r = SongRematch.rematch(emptyList(), listOf(song("/new/a.mp3")))
        assertTrue(r.mapping.isEmpty())
        assertTrue(r.unresolved.isEmpty())
    }

    // ── 唯一认领 / 稳定性 ───────────────────────────────────

    @Test
    fun `同一首新曲目不会被两条旧引用同时认领`() {
        // 备份里同一首歌出现两次（路径写法不同）：不能都把歌单某一首绑到同一新曲目上，
        // 否则回写后 Set 去重会静默吞掉一条，用户看到「歌单少了一首」查不出原因
        val library = listOf(song("/new/a.mp3", title = "A", durationSec = 200))
        val old = listOf(
            missing("/old1/a.mp3", title = "A", durationSec = 200),
            missing("/old2/sub/a.mp3", title = "A", durationSec = 200),
        )

        val r = SongRematch.rematch(old, library)

        assertEquals(1, r.mapping.size)
        assertEquals(1, r.unresolved.size)
    }

    @Test
    fun `同名同长多候选时按曲库顺序稳定收敛`() {
        // 两份副本（同一张专辑下载了两次）：规则必须确定，同样的输入永远得同样的结果，
        // 否则两次恢复会绑定到不同的歌，用户看到的歌单莫名其妙在变
        val library = listOf(
            song("/new/first/a.mp3", title = "A", durationSec = 200),
            song("/new/second/a.mp3", title = "A", durationSec = 200),
        )
        val old = listOf(missing("/old/a.mp3", title = "A", durationSec = 200))

        val r1 = SongRematch.rematch(old, library)
        val r2 = SongRematch.rematch(old, library)

        assertEquals("f/new/first/a.mp3", r1.mapping["f/old/a.mp3"])
        assertEquals(r1.mapping, r2.mapping)
    }

    @Test
    fun `文件名匹配大小写不敏感`() {
        // Windows 中转/部分 ROM 会把文件名大小写改写；大小写敏感会让一批歌莫名失配
        val library = listOf(song("/new/Song.MP3", title = "Song"))
        val old = listOf(missing("/old/song.mp3", title = "Song", durationSec = 200))

        val r = SongRematch.rematch(old, library)

        assertEquals("f/new/Song.MP3", r.mapping["f/old/song.mp3"])
    }

    // ── id / 路径解析 ───────────────────────────────────────

    @Test
    fun `basenameOf 能从 f 前缀 id 还原文件名`() {
        assertEquals("a.mp3", SongRematch.basenameOf("f/storage/emulated/0/Music/a.mp3"))
        // 反斜杠写法（备份经 Windows 工具中转过的极端情形）同样要能还原
        assertEquals("a.mp3", SongRematch.basenameOf("f/storage/emulated/0/Music\\a.mp3"))
        // 非路径形态的 id 原样返回，不抛异常
        assertEquals("placeholder", SongRematch.basenameOf("placeholder"))
    }

    // ── 恢复报告文案 ────────────────────────────────────────

    @Test
    fun `恢复报告 全部命中时不提重匹配`() {
        val msg = restoreReportMessage(RestoreReport(reused = 42, resolved = 0, missing = 0))
        assertEquals("已恢复全部 42 首曲目", msg)
    }

    @Test
    fun `恢复报告 发生重匹配时明确说明路径已变化`() {
        // 「不是丢了、是换了路径」——这句话是用户不恐慌的关键，不能省
        val msg = restoreReportMessage(RestoreReport(reused = 10, resolved = 5, missing = 0))
        assertTrue(msg.contains("已恢复 15 首"))
        assertTrue(msg.contains("5 首按文件名重新匹配"))
        assertTrue(msg.contains("路径已变化"))
    }

    @Test
    fun `恢复报告 有未找到项时给数量与可读样本`() {
        val msg = restoreReportMessage(
            RestoreReport(reused = 8, resolved = 0, missing = 3, missingSamples = listOf("夜航西飞", "理想三旬")),
        )
        assertTrue(msg.contains("已恢复 8 首"))
        assertTrue(msg.contains("3 首未找到"))
        assertTrue(msg.contains("夜航西飞"))
        assertTrue(msg.contains("等")) // 样本未列全 → 用「等」收尾
    }

    @Test
    fun `恢复报告 一首都没找到时直接说清原因`() {
        val msg = restoreReportMessage(RestoreReport(reused = 0, resolved = 0, missing = 60))
        assertTrue(msg.contains("60 首一首都没找到"))
        assertTrue(msg.contains("还没拷贝到本机"))
    }

    @Test
    fun `恢复报告 完全没有任何曲目引用时给中性确认`() {
        val msg = restoreReportMessage(RestoreReport(reused = 0, resolved = 0, missing = 0))
        assertTrue(msg.contains("备份已恢复"))
    }

    @Test
    fun `恢复报告 needsAttention 只在有重匹配或未找到时为真`() {
        assertTrue(RestoreReport(reused = 0, resolved = 1, missing = 0).needsAttention)
        assertTrue(RestoreReport(reused = 0, resolved = 0, missing = 1).needsAttention)
        assertTrue(!RestoreReport(reused = 5, resolved = 0, missing = 0).needsAttention)
    }

    @Test
    fun `恢复报告样本上限常量是正数`() {
        // 只是钉死常量有意义（横幅最多 3 行），防止日后被改成 0 导致「未找到」不带样本
        assertTrue(RESTORE_REPORT_SAMPLE_LIMIT > 0)
    }

    // ── 指纹可空（旧备份）──────────────────────────────────

    @Test
    fun `指纹为 null 的旧备份仍能按文件名匹配`() {
        // 本功能上线前的备份只有 id（路径），没有 title/artist/duration。
        // 此时 3、4 级不可用，但 1、2 级（文件名）仍应生效 —— 这是旧备份的救命绳。
        val library = listOf(song("/new/夜航西飞.flac", title = "完全不同的标题"))
        val old = listOf(SongRematch.MissingRef("f/old/夜航西飞.flac", ref = null))

        val r = SongRematch.rematch(old, library)

        assertEquals("f/new/夜航西飞.flac", r.mapping["f/old/夜航西飞.flac"])
        assertEquals(1, r.hitsOf(SongRematch.LEVEL_NAME))
    }

    @Test
    fun `待匹配项已在曲库中时不应产生自成环映射`() {
        // 防御性：调用方理论上只传「找不到」的 id，但若误传已在库的 id，
        // 不能把 id 映射到它自己（那样回写等于没变，且会污染统计）
        val library = listOf(song("/new/a.mp3", title = "A", durationSec = 200))
        val r = SongRematch.rematch(
            listOf(SongRematch.MissingRef("f/new/a.mp3", SongRematch.TrackRef("A", "artist", "album", 200))),
            library,
        )
        assertNull(r.mapping["f/new/a.mp3"])
    }
}

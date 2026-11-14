package com.local.music.core.ui.state

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 更新域的状态语义（本轮随 `UpdateUiState` 迁到 `:core:ui` 一并补齐）。
 *
 * ## 为什么这两个属性值得测
 * `keepsSheetOpen` 与 `offersRetry` 原先分别以「设置页里的局部 `setOf`」和
 * 「调用点上的 if 判断」形式存在，谁都没有测过。而它们的取值直接决定两件用户可见的事：
 * - 面板会不会在下载失败的一瞬间自行关闭（错判 = 用户看不到失败原因）；
 * - 验签失败时会不会给一个**必然失败**的重试入口（错判 = 用户陷入死循环）。
 *
 * 迁到 `:core:ui` 后这两个判断变成了 data class 的属性，终于可以逐态断言。
 */
class UpdateUiStateTest {

    @Test
    fun `进行中的三个状态保持面板打开`() {
        assertTrue(UpdateUiState(status = UpdateUiState.Status.Available).keepsSheetOpen)
        assertTrue(UpdateUiState(status = UpdateUiState.Status.Downloading).keepsSheetOpen)
        assertTrue(UpdateUiState(status = UpdateUiState.Status.ReadyToInstall).keepsSheetOpen)
    }

    @Test
    fun `空闲与终态关闭面板`() {
        // Idle / Checking / UpToDate 都不需要面板：它们没有「可操作项」
        assertFalse(UpdateUiState(status = UpdateUiState.Status.Idle).keepsSheetOpen)
        assertFalse(UpdateUiState(status = UpdateUiState.Status.Checking).keepsSheetOpen)
        assertFalse(UpdateUiState(status = UpdateUiState.Status.UpToDate).keepsSheetOpen)
        // Failed 也要收起：失败原因在设置页那一行的副标题里，不需要面板挡着
        assertFalse(UpdateUiState(status = UpdateUiState.Status.Failed).keepsSheetOpen)
    }

    @Test
    fun `验签失败不提供重试`() {
        // 同一份坏字节再下一次必然再失败（死循环），正确出路是去发布页手动下载
        val verifyFail = UpdateUiState(
            status = UpdateUiState.Status.Failed,
            failStage = UpdateUiState.FailStage.Verify,
        )
        assertFalse("验签失败**不该**给重试入口", verifyFail.offersRetry)
    }

    @Test
    fun `其他阶段的失败提供重试`() {
        listOf(
            UpdateUiState.FailStage.Check,
            UpdateUiState.FailStage.Download,
            UpdateUiState.FailStage.Install,
            UpdateUiState.FailStage.None,
        ).forEach { stage ->
            val s = UpdateUiState(status = UpdateUiState.Status.Failed, failStage = stage)
            assertTrue("阶段 $stage 的失败应可重试", s.offersRetry)
        }
    }

    @Test
    fun `非失败态不提供重试`() {
        assertFalse(UpdateUiState(status = UpdateUiState.Status.Idle).offersRetry)
        assertFalse(UpdateUiState(status = UpdateUiState.Status.Downloading).offersRetry)
        assertFalse(UpdateUiState(status = UpdateUiState.Status.ReadyToInstall).offersRetry)
    }

    @Test
    fun `默认状态是空闲且无失败阶段`() {
        val s = UpdateUiState()
        assertEquals(UpdateUiState.Status.Idle, s.status)
        assertEquals(UpdateUiState.FailStage.None, s.failStage)
        assertFalse(s.indeterminate)
        assertEquals(0f, s.progress, 0f)
    }

    @Test
    fun `UpdateHolder 提交后状态可见`() {
        val holder = UpdateHolder()
        assertEquals(UpdateUiState.Status.Idle, holder.state.status)
        holder.commit(UpdateUiState(status = UpdateUiState.Status.Downloading, progress = 0.42f))
        assertEquals(UpdateUiState.Status.Downloading, holder.state.status)
        assertEquals(0.42f, holder.state.progress, 0.0001f)
    }
}

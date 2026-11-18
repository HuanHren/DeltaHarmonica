package com.huanhren.deltaharmonica.overlay

import com.huanhren.deltaharmonica.input.TapResult
import com.huanhren.deltaharmonica.model.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull

/** Orchestrates exactly one gesture; all callbacks are invoked on the caller's thread. */
object OverlayTapCoordinator {
    suspend fun execute(layout: InstrumentLayout, targetId: String, screen: ScreenSize, enabled: Boolean,
        hide: () -> Unit, awaitWindows: suspend () -> Unit, environmentValid: () -> Boolean,
        dispatch: suspend (ScreenPoint) -> TapResult, restore: () -> Unit): TapResult {
        if (!enabled) return TapResult.Unavailable("无障碍服务不可用")
        if (layout.referenceDisplay == null || !OverlayGeometry.compatible(layout, screen))
            return TapResult.Unavailable("布局未校准或屏幕尺寸不匹配")
        val target = layout.targets.find { it.id == targetId } ?: return TapResult.Unavailable("未找到选中的目标")
        val point = Coordinates.toScreen(target.position, screen)
        return try {
            withTimeoutOrNull(6000) {
                hide()
                awaitWindows()
                if (!environmentValid()) TapResult.Unavailable("屏幕环境或无障碍服务发生变化") else dispatch(point)
            } ?: TapResult.TimedOut
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) { TapResult.Unavailable("测试失败：${e.localizedMessage ?: "未知错误"}") }
        finally { restore() }
    }
}

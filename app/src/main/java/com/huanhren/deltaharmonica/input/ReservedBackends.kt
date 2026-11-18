package com.huanhren.deltaharmonica.input

import com.huanhren.deltaharmonica.model.ScreenPoint

class ShizukuBackend : InputBackend {
    override val name = "Shizuku"
    override fun status() = BackendStatus(BackendAvailability.NOT_IMPLEMENTED, "未实现 · Stage 1 不可用")
    override suspend fun tap(point: ScreenPoint) = TapResult.Unavailable(status().message)
}


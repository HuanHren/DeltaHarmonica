package com.huanhren.deltaharmonica.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import com.huanhren.deltaharmonica.R
import com.huanhren.deltaharmonica.model.Region
import com.huanhren.deltaharmonica.model.TouchTarget
import java.text.NumberFormat
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

@Composable
internal fun musicDate(millis: Long): String =
    Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault())
        .format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(LocalConfiguration.current.locales[0]))

@Composable
internal fun musicNumber(value: Number): String =
    NumberFormat.getIntegerInstance(LocalConfiguration.current.locales[0]).format(value)

@Composable
internal fun musicDuration(millis: Long?): String {
    val seconds = millis?.coerceAtLeast(0)?.div(1_000)
    return if (seconds == null) stringResource(R.string.music_duration_unknown)
    else stringResource(R.string.music_duration, seconds / 60, seconds % 60)
}

@Composable
internal fun musicRegion(region: Region?): String = stringResource(
    when (region) {
        Region.HIGH -> R.string.player_region_high
        Region.NATURAL -> R.string.player_region_natural
        Region.LOW -> R.string.player_region_low
        null -> R.string.player_unsynced
    }
)

@Composable
internal fun musicSemitone(enabled: Boolean?): String = stringResource(
    when (enabled) {
        true -> R.string.player_semitone_on
        false -> R.string.player_semitone_off
        null -> R.string.player_semitone_unsynced
    }
)

@Composable
internal fun musicTarget(target: TouchTarget): String = when (target.id) {
    "REGION_HIGH" -> if (target.label == "升调") musicRegion(Region.HIGH) else target.label
    "REGION_NATURAL" -> if (target.label == "自然音") musicRegion(Region.NATURAL) else target.label
    "REGION_LOW" -> if (target.label == "降调") musicRegion(Region.LOW) else target.label
    "SEMITONE_TOGGLE" -> if (target.label == "半音") stringResource(R.string.music_semitone) else target.label
    else -> target.label
}

/** Localize standard backend status messages at the UI boundary. Device output remains verbatim. */
@Composable
internal fun musicBackendStatus(message: String?): String {
    val label = when (message) {
        null -> R.string.music_reading_status
        "未启用 · 请在系统无障碍设置中开启" -> R.string.music_backend_accessibility_off
        "已启用，等待系统连接服务" -> R.string.music_backend_accessibility_wait
        "可用 · 无障碍服务已连接" -> R.string.music_backend_accessibility_ready
        "尚未检查 Root 授权" -> R.string.music_backend_root_unchecked
        "正在检查 Root 授权…" -> R.string.music_backend_root_check
        "可用 · 已获得 Root 授权" -> R.string.music_backend_root_available
        "su 未返回 Root UID；请检查授权" -> R.string.music_backend_root_uid
        "Root 授权检查超时；请检查授权提示" -> R.string.music_backend_root_timeout
        "Root 授权检查已取消" -> R.string.music_backend_root_cancelled
        "上次 Root 触摸释放待确认，请检查游戏后重启应用" -> R.string.music_backend_root_release
        "Root 点击超时，请重新检查授权" -> R.string.music_backend_root_tap_timeout
        "未实现 · Stage 1 不可用" -> R.string.music_backend_unavailable
        else -> null
    }
    return if (label != null) stringResource(label) else message.orEmpty()
}

@Composable
internal fun musicDiagnostic(message: String): String {
    val label = when (message) {
        "曲目不存在" -> R.string.music_error_library_missing
        "播放会话尚未结束" -> R.string.music_error_playback_busy
        "选曲请求已过期" -> R.string.music_error_expired_activation
        "JSON 脚本无效或版本不受支持" -> R.string.import_error_json
        "请重新选择 MIDI 文件" -> R.string.import_error_choose_midi
        "导入候选文件不存在" -> R.string.import_error_candidate_missing
        "未知错误", "null" -> R.string.music_unknown
        "请先保存校准布局" -> R.string.player_layout_missing
        "请先授权悬浮窗" -> R.string.player_error_overlay_permission
        "悬浮窗权限不可用，请在系统设置中开启" -> R.string.player_error_overlay_permission_settings
        "请先同步游戏音区与半音" -> R.string.player_error_sync
        "选定范围没有完整的可播放事件" -> R.string.player_error_no_events
        "无法确认游戏窗口：请切回游戏口琴界面并重试" -> R.string.player_error_game_focus
        "屏幕方向或尺寸不匹配，请重新校准" -> R.string.player_error_layout_display
        "布局没有已校准的参考屏幕" -> R.string.player_error_layout_reference
        "乐器配置或键位映射不匹配" -> R.string.player_error_profile
        "半音状态未知，请先确认游戏中的半音开关" -> R.string.player_error_semitone
        "事件延迟超过 2000 ms，已停止后续事件" -> R.string.player_error_delay
        "显示环境或无障碍服务已变化" -> R.string.player_error_environment
        "上次 Root 触摸释放待确认，请检查游戏状态" -> R.string.player_error_release
        "音高映射未经实机验证" -> R.string.player_error_mapping_unverified
        "组合触摸的游戏生效顺序尚需实机验证；请先试听并核对音高" -> R.string.player_error_combined_unverified
        "旧脚本的转换历史未知，需重新转换" -> R.string.player_error_legacy_script
        "无法验证旧脚本是否存在未映射音符" -> R.string.player_error_legacy_mapping
        "不支持的脚本版本" -> R.string.player_error_script_version
        "脚本数据无效" -> R.string.player_error_script_data
        "文件为空，无法读取 MIDI。" -> R.string.import_error_empty
        "文件超过 2 MB，无法安全导入。" -> R.string.import_error_file_size
        "这是 ZIP 压缩包，请先解压并选择其中的 MIDI 文件。" -> R.string.import_error_zip
        "文件不是有效的 MIDI，可能下载到了网页。" -> R.string.import_error_website
        "不支持的文件格式：请选择 SMF 或 RMID MIDI 文件。" -> R.string.import_error_format
        "检测到 MIDI Type 2，目前不支持这种序列格式。" -> R.string.import_error_type2
        "文件使用 SMPTE 时间基准，当前版本无法可靠换算音符时间。" -> R.string.import_error_smpte
        "部分轨道缺少结束事件，可能缺少后续音符。" -> R.string.import_warning_no_end
        else -> null
    }
    return when {
        label != null -> stringResource(label)
        message.startsWith("预检失败：") -> stringResource(R.string.player_error_preflight, musicDiagnostic(message.substringAfter('：')))
        message.startsWith("无法启动悬浮窗：") -> stringResource(R.string.player_error_overlay_start, musicDiagnostic(message.substringAfter('：')))
        message.startsWith("失败：") -> stringResource(R.string.player_error_detail, musicDiagnostic(message.substringAfter('：')))
        message.startsWith("MIDI 文件损坏：") -> stringResource(R.string.import_error_corrupt, musicDiagnostic(message.substringAfter('：')))
        else -> {
            val unmapped = unmappedNotes.matchEntire(message)?.groupValues?.get(1)?.toIntOrNull()
            val cropped = croppedNotes.matchEntire(message)?.groupValues?.get(1)?.toLongOrNull()
            val misplaced = unmappedPitch.matchEntire(message)?.groupValues
            when {
                unmapped != null -> stringResource(R.string.player_error_unmapped, unmapped)
                cropped != null -> stringResource(R.string.import_warning_shortened, cropped)
                misplaced != null -> stringResource(R.string.import_warning_unmapped,
                    misplaced[1].toLongOrNull() ?: return message, misplaced[2].toIntOrNull() ?: return message)
                else -> musicBackendStatus(message)
            }
        }
    }
}

private val unmappedNotes = Regex("脚本有 ([0-9]+) 个未映射音符，不能真实播放")
private val croppedNotes = Regex("单音化丢弃了 ([0-9]+) 毫秒的重叠音符时长；保留片段不延长。")
private val unmappedPitch = Regex("([0-9]+) ms：音高 (-?[0-9]+) 无法精确映射到配置或布局，已省略")

package com.huanhren.deltaharmonica.ui

import android.app.Activity
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.huanhren.deltaharmonica.R
import com.huanhren.deltaharmonica.model.*
import com.huanhren.deltaharmonica.ui.theme.*
import top.yukonga.miuix.kmp.basic.Text

@Composable
fun CalibrationScreen(state: AppUiState, activity: Activity, onSelect: (String) -> Unit,
                      onMove: (String, NormalizedPoint) -> Unit, onReset: (LayoutOrientation?) -> Unit,
                      onSave: (ScreenSize) -> Unit, onBack: () -> Unit) {
    ImmersiveScreen(activity)
    var expanded by rememberSaveable { mutableStateOf(true) }
    var resetOpen by rememberSaveable { mutableStateOf(false) }
    val panelHeight = with(LocalDensity.current) { (LocalWindowInfo.current.containerSize.height * 0.89f).toDp() }
    ScreenCanvas(activity) { geometry ->
        val layout = state.draft
        val compatible = geometry != null && layout != null && geometry.display.supported &&
            geometry.display.size.orientation == layout.orientation
        if (geometry != null && layout != null && compatible) {
            layout.targets.forEach { target ->
                key(target.id) {
                    TargetMarker(target.copy(label = musicTarget(target)), geometry, target.id == state.selectedId,
                        onSelect = { onSelect(target.id) },
                        onMove = if (state.saving) null else { point -> onMove(target.id, point) })
                }
            }
        }
        Column(Modifier.safeDrawingPadding().padding(Spacing.small).widthIn(max = 328.dp)) {
            if (!expanded) {
                PrimaryButton(stringResource(R.string.calibration_show), { expanded = true })
            } else {
                ProductCard(Modifier.heightIn(max = panelHeight)) {
                    Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(Spacing.small)) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            TextAction(onClick = onBack, enabled = !state.saving) { Text(stringResource(R.string.action_back)) }
                            TextAction(onClick = { expanded = false }) { Text(stringResource(R.string.calibration_collapse)) }
                        }
                        SectionTitle(stringResource(R.string.calibration_title))
                        SupportingText(stringResource(R.string.calibration_align_detail, layout?.targets?.size ?: 12))
                        if (!compatible) {
                            val orientation = stringResource(if (layout?.orientation == LayoutOrientation.LANDSCAPE)
                                R.string.calibration_landscape else R.string.calibration_portrait)
                            SupportingText(stringResource(R.string.calibration_need_orientation, orientation), error = true)
                            if (geometry?.display?.supported == true) {
                                TextAction(onClick = { resetOpen = true }, enabled = !state.saving) {
                                    Text(stringResource(R.string.calibration_rearrange))
                                }
                            }
                        }
                        state.selected?.let { target ->
                            val point = geometry?.let { Coordinates.toScreen(target.position, it.display.size) }
                            SupportingText(stringResource(R.string.calibration_target_position, musicTarget(target),
                                target.position.x, target.position.y))
                            point?.let { SupportingText(stringResource(R.string.calibration_pixels, it.x, it.y)) }
                        }
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.small), verticalArrangement = Arrangement.spacedBy(Spacing.small)) {
                            PrimaryButton(stringResource(R.string.calibration_save), { geometry?.let { onSave(it.display.size) } },
                                Modifier.testTag("calibration/save"), enabled = compatible && !state.saving && state.loadError == null)
                            if (compatible) SecondaryAction(onClick = { resetOpen = true }, enabled = !state.saving) {
                                Text(stringResource(R.string.calibration_default))
                            }
                        }
                        SupportingText(calibrationStatus(state), error = state.loadError != null || state.message.startsWith("保存失败"))
                    }
                }
            }
        }
        if (resetOpen) ProductDialog(stringResource(R.string.calibration_restore_question), { resetOpen = false },
            stringResource(R.string.calibration_restore_draft), {
                resetOpen = false
                onReset(if (compatible) null else geometry?.display?.size?.orientation)
            }) { SupportingText(stringResource(R.string.calibration_restore_detail)) }
    }
}

@Composable
private fun calibrationStatus(state: AppUiState): String {
    state.loadError?.let { return stringResource(R.string.calibration_load_error, musicDiagnostic(it.substringAfter('：'))) }
    val label = when {
        state.saving -> R.string.calibration_saving
        state.loading -> R.string.calibration_loading
        state.message == "未保存" -> R.string.calibration_unsaved
        state.message == "已保存" -> R.string.calibration_saved
        state.message == "已恢复默认草稿 · 尚未保存" -> R.string.calibration_default_draft
        state.message.startsWith("保存失败：") ->
            return stringResource(R.string.calibration_save_error, musicDiagnostic(state.message.substringAfter('：')))
        state.message.isNotBlank() -> return musicDiagnostic(state.message)
        state.dirty -> R.string.calibration_dirty
        state.saved == null -> R.string.calibration_no_layout
        else -> R.string.calibration_layout_saved
    }
    return stringResource(label)
}

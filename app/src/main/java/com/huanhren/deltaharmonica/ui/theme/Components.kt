package com.huanhren.deltaharmonica.ui.theme

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import top.yukonga.miuix.kmp.basic.Button as MiuixButton
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Card as MiuixCard
import top.yukonga.miuix.kmp.basic.Text

@Composable
fun Page(title: String, modifier: Modifier = Modifier, onBack: (() -> Unit)? = null, content: @Composable ColumnScope.() -> Unit) {
    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background), contentAlignment = Alignment.TopCenter) {
        Column(modifier.widthIn(max = Spacing.contentWidth).fillMaxWidth().verticalScroll(rememberScrollState())
            .padding(horizontal = Spacing.page, vertical = Spacing.item), verticalArrangement = Arrangement.spacedBy(Spacing.card)) {
            onBack?.let { TextButton(onClick = it) { Text("返回") } }
            PageTitle(title)
            content()
        }
    }
}

@Composable
fun PageTitle(title: String) {
    Text(title, style = MaterialTheme.typography.headlineLarge)
}

@Composable
fun SectionTitle(title: String) {
    Text(title, style = MaterialTheme.typography.titleLarge)
}

@Composable
fun ProductCard(modifier: Modifier = Modifier, color: Color = MaterialTheme.colorScheme.surface,
                content: @Composable ColumnScope.() -> Unit) {
    MiuixCard(modifier.fillMaxWidth(), ShapeTokens.cardRadius, PaddingValues(Spacing.card), color) {
        Column(verticalArrangement = Arrangement.spacedBy(Spacing.small), content = content)
    }
}

@Composable
fun PrimaryButton(label: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    MiuixButton(onClick = onClick, modifier = modifier.heightIn(min = Spacing.touchTarget), enabled = enabled, colors = ButtonDefaults.buttonColorsPrimary()) {
        Text(label, color = if (enabled) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.labelLarge)
    }
}

@Composable
fun SupportingText(text: String, modifier: Modifier = Modifier, error: Boolean = false, maxLines: Int = Int.MAX_VALUE) {
    Text(text, modifier, style = MaterialTheme.typography.bodyMedium, maxLines = maxLines, overflow = TextOverflow.Ellipsis,
        color = if (error) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
fun StatusCard(title: String, detail: String, loading: Boolean = false, error: Boolean = false,
               action: @Composable ColumnScope.() -> Unit = {}) {
    ProductCard {
        SectionTitle(title)
        SupportingText(detail, error = error)
        if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
        action()
    }
}

@Composable
fun <T> Choices(values: List<T>, selected: T, label: (T) -> String, onSelect: (T) -> Unit,
                enabled: Boolean = true, modifier: Modifier = Modifier) {
    FlowRow(modifier.selectableGroup(), horizontalArrangement = Arrangement.spacedBy(Spacing.small),
        verticalArrangement = Arrangement.spacedBy(Spacing.small)) {
        values.forEach { value ->
            val active = selected == value
            val colors = MaterialTheme.colorScheme
            Box(Modifier.clip(ShapeTokens.pill)
                .background(if (active) colors.primaryContainer else colors.background)
                .selectable(active, enabled = enabled, role = Role.RadioButton, onClick = { onSelect(value) })
                .heightIn(min = Spacing.touchTarget).padding(horizontal = Spacing.item),
                contentAlignment = Alignment.Center) {
                Text(label(value), color = when {
                    !enabled -> colors.onSurfaceVariant.copy(alpha = .65f)
                    active -> colors.onPrimaryContainer
                    else -> colors.onSurfaceVariant
                }, style = MaterialTheme.typography.labelLarge)
            }
        }
    }
}

@Composable
fun ProductDialog(title: String, onClose: () -> Unit, confirm: String, onConfirm: () -> Unit,
                  enabled: Boolean = true, text: @Composable () -> Unit) {
    AlertDialog(onDismissRequest = onClose, title = { SectionTitle(title) }, text = text,
        confirmButton = { PrimaryButton(confirm, onConfirm, enabled = enabled) },
        dismissButton = { TextButton(onClick = onClose) { Text("取消") } })
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProductSheet(title: String, onClose: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    ModalBottomSheet(onDismissRequest = onClose, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        shape = ShapeTokens.card, containerColor = MaterialTheme.colorScheme.surface) {
        Column(Modifier.fillMaxWidth().padding(horizontal = Spacing.page).padding(bottom = Spacing.section),
            verticalArrangement = Arrangement.spacedBy(Spacing.small)) {
            SectionTitle(title)
            content()
        }
    }
}

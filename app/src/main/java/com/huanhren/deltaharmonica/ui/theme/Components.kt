package com.huanhren.deltaharmonica.ui.theme

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import top.yukonga.miuix.kmp.basic.CardDefaults
import top.yukonga.miuix.kmp.basic.LinearProgressIndicator
import top.yukonga.miuix.kmp.overlay.OverlayDialog
import top.yukonga.miuix.kmp.overlay.OverlayBottomSheet
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

internal val LocalStudioGutters = androidx.compose.runtime.staticCompositionLocalOf { PaddingValues() }

@Composable
fun Page(title: String, modifier: Modifier = Modifier, onBack: (() -> Unit)? = null,
         actions: (@Composable RowScope.() -> Unit)? = null, content: @Composable ColumnScope.() -> Unit) {
    val gutters = LocalStudioGutters.current
    Box(Modifier.fillMaxSize().background(StudioTheme.colorScheme.background), contentAlignment = Alignment.TopCenter) {
        Column(modifier.widthIn(max = Spacing.contentWidth).fillMaxWidth().verticalScroll(rememberScrollState())
            .padding(horizontal = Spacing.page).padding(top = Spacing.item + gutters.calculateTopPadding(), bottom = Spacing.item + gutters.calculateBottomPadding()), verticalArrangement = Arrangement.spacedBy(Spacing.card)) {
            onBack?.let { TextAction(onClick = it) { Text(androidx.compose.ui.res.stringResource(com.huanhren.deltaharmonica.R.string.action_back)) } }
            if (actions == null) PageTitle(title)
            else Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.weight(1f)) { PageTitle(title) }
                actions()
            }
            content()
        }
    }
}

@Composable
fun PageTitle(title: String) {
    Text(title, style = StudioTheme.typography.headlineLarge)
}

@Composable
fun SectionTitle(title: String) {
    Text(title, style = StudioTheme.typography.titleLarge)
}

@Composable
fun ProductCard(modifier: Modifier = Modifier, color: Color = StudioTheme.colorScheme.surface,
                content: @Composable ColumnScope.() -> Unit) {
    MiuixCard(modifier.fillMaxWidth(), ShapeTokens.cardRadius, PaddingValues(Spacing.card), CardDefaults.defaultColors(color = color)) {
        Column(verticalArrangement = Arrangement.spacedBy(Spacing.small), content = content)
    }
}

@Composable
fun PrimaryButton(label: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    MiuixButton(onClick = onClick, modifier = modifier.heightIn(min = Spacing.touchTarget), enabled = enabled, colors = ButtonDefaults.buttonColorsPrimary()) {
        Text(label, color = if (enabled) StudioTheme.colorScheme.onPrimary else StudioTheme.colorScheme.onSurfaceVariant,
            style = StudioTheme.typography.labelLarge)
    }
}

@Composable
fun SupportingText(text: String, modifier: Modifier = Modifier, error: Boolean = false, maxLines: Int = Int.MAX_VALUE) {
    Text(text, modifier, style = StudioTheme.typography.bodyMedium, maxLines = maxLines, overflow = TextOverflow.Ellipsis,
        color = if (error) StudioTheme.colorScheme.error else StudioTheme.colorScheme.onSurfaceVariant)
}

@Composable
fun StatusCard(title: String, detail: String, loading: Boolean = false, error: Boolean = false,
               action: @Composable ColumnScope.() -> Unit = {}) {
    ProductCard {
        SectionTitle(title)
        SupportingText(detail, error = error)
        if (loading) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
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
            val colors = StudioTheme.colorScheme
            Box(Modifier.clip(ShapeTokens.pill)
                .background(if (active) colors.primaryContainer else colors.background)
                .selectable(active, enabled = enabled, role = Role.RadioButton, onClick = { onSelect(value) })
                .heightIn(min = Spacing.touchTarget).padding(horizontal = Spacing.item),
                contentAlignment = Alignment.Center) {
                Text(label(value), color = when {
                    !enabled -> colors.onSurfaceVariant.copy(alpha = .65f)
                    active -> colors.onPrimaryContainer
                    else -> colors.onSurfaceVariant
                }, style = StudioTheme.typography.labelLarge)
            }
        }
    }
}

@Composable
fun ProductDialog(title: String, onClose: () -> Unit, confirm: String, onConfirm: () -> Unit,
                  enabled: Boolean = true, text: @Composable () -> Unit) {
    OverlayDialog(show = true, title = title, onDismissRequest = onClose) {
        Column(verticalArrangement = Arrangement.spacedBy(Spacing.item)) {
            text()
            FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.small)) {
                TextAction(onClick = onClose) { Text(androidx.compose.ui.res.stringResource(android.R.string.cancel)) }
                PrimaryButton(confirm, onConfirm, enabled = enabled)
            }
        }
    }
}

@Composable
fun ProductSheet(title: String, onClose: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    OverlayBottomSheet(show = true, title = title, onDismissRequest = onClose) {
        Column(Modifier.fillMaxWidth().padding(horizontal = Spacing.page).padding(bottom = Spacing.section),
            verticalArrangement = Arrangement.spacedBy(Spacing.small), content = content)
    }
}

package com.huanhren.deltaharmonica.ui

import android.app.Activity
import android.text.format.Formatter
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.huanhren.deltaharmonica.R
import com.huanhren.deltaharmonica.ui.theme.PrimaryButton
import com.huanhren.deltaharmonica.ui.theme.Spacing
import com.huanhren.deltaharmonica.ui.theme.StatusCard
import com.huanhren.deltaharmonica.ui.theme.SupportingText
import com.huanhren.deltaharmonica.ui.theme.TextAction
import com.huanhren.deltaharmonica.update.InstallerLaunch
import com.huanhren.deltaharmonica.update.ReleaseUpdateController
import com.huanhren.deltaharmonica.update.UpdateCheck
import com.huanhren.deltaharmonica.update.UpdateDownload
import com.huanhren.deltaharmonica.update.UpdateProblem
import com.huanhren.deltaharmonica.update.openVerifiedInstaller
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.basic.LinearProgressIndicator
import java.text.DateFormat
import java.util.Date

@Composable
internal fun UpdatePreferences(activity: Activity, updates: ReleaseUpdateController) {
    if (!updates.enabled) {
        PreferenceGroup {
            BasicComponent(title = versionSummary(), summary = stringResource(R.string.update_disabled_build))
        }
        return
    }
    val settings by updates.settings.collectAsStateWithLifecycle()
    val settingsError by updates.settingsError.collectAsStateWithLifecycle()
    val check by updates.check.collectAsStateWithLifecycle()
    val download by updates.download.collectAsStateWithLifecycle()
    val busy = check is UpdateCheck.Checking || download is UpdateDownload.InProgress

    PreferenceGroup {
        BasicComponent(title = versionSummary(), summary = stringResource(R.string.update_stable_channel))
        SwitchPreference(
            checked = settings?.automatic ?: true,
            onCheckedChange = updates::setAutomatic,
            title = stringResource(R.string.update_automatic),
            summary = stringResource(R.string.update_automatic_summary),
            enabled = settings != null,
            modifier = Modifier.testTag("updates/automatic"),
        )
        val lastChecked = settings?.lastCheckedAtMs?.let { updateDate(it) }
            ?: stringResource(R.string.update_never_checked)
        ArrowPreference(
            title = stringResource(R.string.update_check),
            summary = stringResource(R.string.update_last_checked, lastChecked),
            enabled = !busy,
            modifier = Modifier.testTag("updates/check"),
            onClick = updates::checkManually,
        )
        if (settingsError) {
            SupportingText(stringResource(R.string.update_storage), Modifier.padding(Spacing.card), error = true)
        }
    }

    when (val result = check) {
        UpdateCheck.Idle -> Unit
        UpdateCheck.Checking -> StatusCard(stringResource(R.string.update_title), stringResource(R.string.update_checking), loading = true)
        UpdateCheck.Current -> StatusCard(stringResource(R.string.update_title), stringResource(R.string.update_current))
        UpdateCheck.NoRelease -> StatusCard(stringResource(R.string.update_title), stringResource(R.string.update_no_release))
        is UpdateCheck.Failed -> StatusCard(stringResource(R.string.update_title), stringResource(result.reason.updateMessage()), error = true)
        is UpdateCheck.Available -> {
            val release = result.release
            val size = Formatter.formatShortFileSize(activity, release.asset.size)
            var launchResult by remember(release.tag) { mutableStateOf<InstallerLaunch?>(null) }
            PreferenceGroup(R.string.update_title) {
                BasicComponent(
                    title = stringResource(R.string.update_latest, release.tag),
                    summary = stringResource(R.string.update_release_details, updateDate(release.published.toEpochMilli()), size),
                )
                Column(Modifier.fillMaxWidth().padding(Spacing.card),
                    verticalArrangement = Arrangement.spacedBy(Spacing.item)) {
                    if (release.notes.isNotBlank()) SupportingText(release.notes)
                    when (val activityState = download) {
                        UpdateDownload.Idle, is UpdateDownload.Failed -> {
                            if (activityState is UpdateDownload.Failed) {
                                SupportingText(stringResource(activityState.reason.updateMessage()), error = true)
                            }
                            PrimaryButton(stringResource(R.string.update_download), updates::download,
                                Modifier.testTag("updates/download"))
                        }
                        is UpdateDownload.InProgress -> {
                            val percent = (100.0 * activityState.bytes / release.asset.size).toInt().coerceIn(0, 100)
                            SupportingText(stringResource(R.string.update_downloading, percent))
                            if (percent == 100) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                        }
                        is UpdateDownload.Ready -> {
                            SupportingText(stringResource(R.string.update_verified))
                            PrimaryButton(stringResource(R.string.update_install), {
                                launchResult = openVerifiedInstaller(activity, activityState.file)
                            }, Modifier.testTag("updates/install"))
                            when (launchResult) {
                                InstallerLaunch.PERMISSION_SETTINGS -> SupportingText(stringResource(R.string.update_allow_return))
                                InstallerLaunch.FAILED -> {
                                    SupportingText(stringResource(R.string.update_install_error), error = true)
                                    TextAction(updates::download) { Text(stringResource(R.string.update_download)) }
                                }
                                else -> Unit
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun updateDate(time: Long): String {
    val locale = LocalConfiguration.current.locales[0]
    return remember(time, locale) {
        DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT, locale).format(Date(time))
    }
}

private fun UpdateProblem.updateMessage() = when (this) {
    UpdateProblem.NETWORK -> R.string.update_network
    UpdateProblem.ACCESS_DENIED -> R.string.update_access
    UpdateProblem.RATE_LIMIT -> R.string.update_rate_limit
    UpdateProblem.UNAVAILABLE -> R.string.update_unavailable
    UpdateProblem.RELEASE_METADATA -> R.string.update_metadata
    UpdateProblem.APK_SIZE -> R.string.update_bad_size
    UpdateProblem.APK_DIGEST -> R.string.update_bad_digest
    UpdateProblem.APK_INVALID -> R.string.update_bad_apk
    UpdateProblem.APK_PACKAGE -> R.string.update_bad_package
    UpdateProblem.APK_SIGNATURE -> R.string.update_bad_signature
    UpdateProblem.APK_VERSION -> R.string.update_bad_version
    UpdateProblem.STORAGE -> R.string.update_storage
}

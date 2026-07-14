package com.framenest.ui.screens

import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import com.framenest.BuildConfig
import com.framenest.ContextAppContainer
import com.framenest.R
import com.framenest.core.diagnostics.DiagnosticLog
import com.framenest.data.settings.UserPreferences
import com.framenest.ui.theme.FrameNestDimens
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun SettingsScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val container = remember(context) { ContextAppContainer(context) }
    val prefs = container.userPreferences
    val scope = rememberCoroutineScope()

    var cacheBytes by remember {
        mutableStateOf(container.cacheMaintenance.approximateTotalBytes())
    }
    var statusMessage by remember { mutableStateOf<String?>(null) }
    var languagePreset by remember { mutableStateOf(prefs.subtitleLanguagePreset()) }

    // Resolve strings at composition time (lint: avoid Context.getString in callbacks).
    val cacheClearedTemplate = stringResource(R.string.settings_cache_cleared)
    val diagnosticsSavedTemplate = stringResource(R.string.settings_diagnostics_saved)
    val diagnosticsShareTitle = stringResource(R.string.settings_diagnostics_share)
    val diagnosticsFailed = stringResource(R.string.settings_diagnostics_failed)

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(FrameNestDimens.ScreenPadding)
            .testTag("settings_screen"),
    ) {
        Text(
            text = stringResource(R.string.settings_title),
            style = MaterialTheme.typography.headlineSmall,
            modifier = Modifier.testTag("settings_title"),
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = stringResource(
                R.string.settings_version_line,
                BuildConfig.VERSION_NAME,
                BuildConfig.VERSION_CODE,
            ),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.testTag("settings_version"),
        )
        Spacer(modifier.height(12.dp))
        Text(
            text = stringResource(R.string.settings_intro),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.widthIn(max = FrameNestDimens.ReadableContentMaxWidth),
        )

        Spacer(Modifier.height(20.dp))
        Text(
            text = stringResource(R.string.settings_cache_row),
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.testTag("settings_cache_row"),
        )
        Text(
            text = stringResource(
                R.string.settings_cache_size,
                formatBytes(cacheBytes),
            ),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.testTag("settings_cache_size"),
        )
        Spacer(modifier.height(8.dp))
        Button(
            onClick = {
                scope.launch {
                    val result = withContext(Dispatchers.IO) {
                        container.cacheMaintenance.clearAllCaches()
                    }
                    cacheBytes = result.remainingApproxBytes
                    statusMessage = cacheClearedTemplate.format(formatBytes(result.freedApproxBytes))
                    DiagnosticLog.info("Settings", "cache cleared freed=${result.freedApproxBytes}")
                }
            },
            modifier = Modifier
                .heightIn(min = FrameNestDimens.MinTouchTarget)
                .minimumInteractiveComponentSize()
                .testTag("settings_clear_cache"),
        ) {
            Text(stringResource(R.string.settings_clear_cache))
        }

        Spacer(modifier.height(20.dp))
        Text(
            text = stringResource(R.string.settings_subtitle_lang_row),
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.testTag("settings_subtitle_lang_row"),
        )
        Text(
            text = stringResource(R.string.settings_subtitle_lang_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.widthIn(max = FrameNestDimens.ReadableContentMaxWidth),
        )
        Spacer(Modifier.height(8.dp))
        LanguageOption(
            label = stringResource(R.string.settings_lang_system),
            selected = languagePreset == UserPreferences.PRESET_SYSTEM,
            testTag = "settings_lang_system",
            onSelect = {
                languagePreset = UserPreferences.PRESET_SYSTEM
                prefs.setSubtitleLanguagePreset(UserPreferences.PRESET_SYSTEM)
            },
        )
        LanguageOption(
            label = stringResource(R.string.settings_lang_zh),
            selected = languagePreset == UserPreferences.PRESET_ZH,
            testTag = "settings_lang_zh",
            onSelect = {
                languagePreset = UserPreferences.PRESET_ZH
                prefs.setSubtitleLanguagePreset(UserPreferences.PRESET_ZH)
            },
        )
        LanguageOption(
            label = stringResource(R.string.settings_lang_en),
            selected = languagePreset == UserPreferences.PRESET_EN,
            testTag = "settings_lang_en",
            onSelect = {
                languagePreset = UserPreferences.PRESET_EN
                prefs.setSubtitleLanguagePreset(UserPreferences.PRESET_EN)
            },
        )

        Spacer(modifier.height(20.dp))
        Text(
            text = stringResource(R.string.settings_diagnostics_row),
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.testTag("settings_diagnostics_row"),
        )
        Text(
            text = stringResource(R.string.settings_diagnostics_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.widthIn(max = FrameNestDimens.ReadableContentMaxWidth),
        )
        Spacer(modifier.height(8.dp))
        OutlinedButton(
            onClick = {
                scope.launch {
                    val result = withContext(Dispatchers.IO) {
                        container.diagnosticLogExporter.export()
                    }
                    result.fold(
                        onSuccess = { file ->
                            statusMessage = diagnosticsSavedTemplate.format(file.name)
                            runCatching {
                                val uri = FileProvider.getUriForFile(
                                    context,
                                    "${context.packageName}.fileprovider",
                                    file,
                                )
                                val share = Intent(Intent.ACTION_SEND).apply {
                                    type = "text/plain"
                                    putExtra(Intent.EXTRA_STREAM, uri)
                                    putExtra(Intent.EXTRA_SUBJECT, "FrameNest diagnostics")
                                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                }
                                context.startActivity(
                                    Intent.createChooser(share, diagnosticsShareTitle),
                                )
                            }
                        },
                        onFailure = { e ->
                            statusMessage = e.message ?: diagnosticsFailed
                        },
                    )
                }
            },
            modifier = Modifier
                .heightIn(min = FrameNestDimens.MinTouchTarget)
                .minimumInteractiveComponentSize()
                .testTag("settings_export_logs"),
        ) {
            Text(stringResource(R.string.settings_export_logs))
        }

        statusMessage?.let { msg ->
            Spacer(modifier.height(16.dp))
            Text(
                text = msg,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.testTag("settings_status"),
            )
        }
    }
}

@Composable
private fun LanguageOption(
    label: String,
    selected: Boolean,
    testTag: String,
    onSelect: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = FrameNestDimens.MinTouchTarget)
            .selectable(
                selected = selected,
                onClick = onSelect,
                role = Role.RadioButton,
            )
            .padding(vertical = 4.dp)
            .testTag(testTag),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        RadioButton(selected = selected, onClick = null)
        Text(text = label, style = MaterialTheme.typography.bodyLarge)
    }
}

private fun formatBytes(bytes: Long): String {
    if (bytes < 1024) return "$bytes B"
    val kb = bytes / 1024.0
    if (kb < 1024) return String.format(Locale.US, "%.1f KB", kb)
    val mb = kb / 1024.0
    return String.format(Locale.US, "%.1f MB", mb)
}

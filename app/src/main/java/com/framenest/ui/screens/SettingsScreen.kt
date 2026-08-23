package com.framenest.ui.screens

import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import com.framenest.BuildConfig
import com.framenest.ContextAppContainer
import com.framenest.R
import com.framenest.core.diagnostics.DiagnosticLog
import com.framenest.data.settings.UserPreferences
import com.framenest.feature.listen_translate.asr.VoskLanguageStatus
import com.framenest.feature.listen_translate.asr.VoskModelInstaller
import com.framenest.feature.listen_translate.mt.MlKitMtEngine
import com.framenest.ui.theme.FrameNestDimens
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun SettingsScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val container = remember(context) { ContextAppContainer(context) }
    val prefs = container.userPreferences
    val scope = rememberCoroutineScope()

    // Directory and Room sizes are loaded below; initial composition must not touch disk.
    var cacheBytes by remember { mutableStateOf(0L) }
    var listenTranslateBytes by remember { mutableStateOf(0L) }
    var listenModelBytes by remember { mutableStateOf(0L) }
    var voskStatuses by remember { mutableStateOf(emptyList<VoskLanguageStatus>()) }
    var modelsInstalling by remember { mutableStateOf(false) }
    var maintenanceRunning by remember { mutableStateOf(false) }
    var installProgress by remember { mutableFloatStateOf(0f) }
    var installStep by remember { mutableStateOf<String?>(null) }
    var statusMessage by remember { mutableStateOf<String?>(null) }
    var pendingClearTarget by remember { mutableStateOf<SettingsClearTarget?>(null) }
    var languagePreset by remember { mutableStateOf(prefs.subtitleLanguagePreset()) }
    var thumbConcurrency by remember { mutableStateOf(prefs.thumbnailConcurrency()) }
    var allowMeteredModelDownloads by remember {
        mutableStateOf(prefs.allowMeteredModelDownloads())
    }
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(statusMessage) {
        val message = statusMessage ?: return@LaunchedEffect
        snackbarHostState.showSnackbar(message)
        if (statusMessage == message) statusMessage = null
    }

    // Resolve strings at composition time (lint: avoid Context.getString in callbacks).
    val cacheClearedTemplate = stringResource(R.string.settings_cache_cleared)
    val listenTranslateClearedTemplate = stringResource(R.string.settings_listen_translate_cleared)
    val listenModelsClearedTemplate = stringResource(R.string.settings_listen_models_cleared)
    val listenModelsInstalled = stringResource(R.string.settings_listen_models_installed)
    val listenModelsLangInstalledTemplate = stringResource(R.string.settings_listen_models_lang_installed)
    val listenModelsAllInstalled = stringResource(R.string.settings_listen_models_all_installed)
    val listenModelsFailedTemplate = stringResource(R.string.settings_listen_models_failed)
    val installStepTemplate = stringResource(R.string.settings_install_listen_models_step)
    val diagnosticsSavedTemplate = stringResource(R.string.settings_diagnostics_saved)
    val diagnosticsShareTitle = stringResource(R.string.settings_diagnostics_share)
    val diagnosticsFailed = stringResource(R.string.settings_diagnostics_failed)
    val maintenanceFailed = stringResource(R.string.settings_maintenance_failed)
    val langOk = stringResource(R.string.settings_listen_models_lang_ok)
    val langMissing = stringResource(R.string.settings_listen_models_lang_missing)
    val langLabels = remember {
        mapOf(
            "zh" to R.string.settings_listen_models_lang_zh,
            "en" to R.string.settings_listen_models_lang_en,
            "ja" to R.string.settings_listen_models_lang_ja,
            "ko" to R.string.settings_listen_models_lang_ko,
            "fr" to R.string.settings_listen_models_lang_fr,
            "de" to R.string.settings_listen_models_lang_de,
            "es" to R.string.settings_listen_models_lang_es,
        )
    }

    suspend fun refreshModelSizesAndStatus() {
        val snapshot = withContext(Dispatchers.IO) {
            container.voskModelInstaller.languageStatuses() to
                container.cacheMaintenance.usageSnapshot()
        }
        voskStatuses = snapshot.first
        cacheBytes = snapshot.second.diskCacheBytes
        listenTranslateBytes = snapshot.second.listenTranslateBytes
        listenModelBytes = snapshot.second.listenModelBytes
    }

    fun reportMaintenanceFailure(operation: String, error: Exception) {
        statusMessage = maintenanceFailed
        DiagnosticLog.info(
            "Settings",
            "$operation failed type=${error::class.java.simpleName}",
        )
    }

    androidx.compose.runtime.LaunchedEffect(Unit) {
        refreshModelSizesAndStatus()
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        snackbarHost = {
            SnackbarHost(
                hostState = snackbarHostState,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
            )
        },
    ) { scaffoldPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(scaffoldPadding),
        ) {
            Column(
                modifier = Modifier
            .align(Alignment.TopCenter)
            .widthIn(max = FrameNestDimens.SettingsContentMaxWidth)
            .fillMaxWidth()
            .fillMaxSize()
            // Edge-to-edge (MainActivity): keep title below the status bar.
            .statusBarsPadding()
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
        Spacer(Modifier.height(12.dp))
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
        Spacer(Modifier.height(8.dp))
        Button(
            onClick = {
                if (maintenanceRunning) return@Button
                maintenanceRunning = true
                scope.launch {
                    try {
                        val result = container.cacheMaintenance.clearGeneralCaches()
                        cacheBytes = result.remainingApproxBytes
                        statusMessage = cacheClearedTemplate.format(
                            formatBytes(result.freedApproxBytes),
                        )
                        DiagnosticLog.info(
                            "Settings",
                            "general cache cleared freed=${result.freedApproxBytes}",
                        )
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (error: Exception) {
                        reportMaintenanceFailure("general cache clear", error)
                    } finally {
                        maintenanceRunning = false
                    }
                }
            },
            enabled = !maintenanceRunning,
            modifier = Modifier
                .heightIn(min = FrameNestDimens.MinTouchTarget)
                .minimumInteractiveComponentSize()
                .testTag("settings_clear_cache"),
        ) {
            Text(stringResource(R.string.settings_clear_cache))
        }

        Spacer(Modifier.height(16.dp))
        Text(
            text = stringResource(R.string.settings_listen_translate_row),
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.testTag("settings_listen_translate_row"),
        )
        Text(
            text = stringResource(R.string.settings_listen_translate_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.widthIn(max = FrameNestDimens.ReadableContentMaxWidth),
        )
        Text(
            text = stringResource(
                R.string.settings_listen_translate_size,
                formatBytes(listenTranslateBytes),
            ),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.testTag("settings_listen_translate_size"),
        )
        Spacer(Modifier.height(8.dp))
        OutlinedButton(
            onClick = { pendingClearTarget = SettingsClearTarget.ListenTranslate },
            enabled = !maintenanceRunning,
            modifier = Modifier
                .heightIn(min = FrameNestDimens.MinTouchTarget)
                .minimumInteractiveComponentSize()
                .testTag("settings_clear_listen_translate"),
        ) {
            Text(stringResource(R.string.settings_clear_listen_translate))
        }

        Spacer(Modifier.height(16.dp))
        Text(
            text = stringResource(R.string.settings_listen_models_row),
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.testTag("settings_listen_models_row"),
        )
        Text(
            text = stringResource(R.string.settings_listen_models_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.widthIn(max = FrameNestDimens.ReadableContentMaxWidth),
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .toggleable(
                    value = allowMeteredModelDownloads,
                    enabled = !modelsInstalling && !maintenanceRunning,
                    role = Role.Switch,
                    onValueChange = { allow ->
                        allowMeteredModelDownloads = allow
                        prefs.setAllowMeteredModelDownloads(allow)
                    },
                )
                .padding(top = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.settings_model_mobile_data),
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    text = stringResource(R.string.settings_model_mobile_data_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(
                checked = allowMeteredModelDownloads,
                onCheckedChange = null,
                enabled = !modelsInstalling && !maintenanceRunning,
                modifier = Modifier.testTag("settings_model_mobile_data"),
            )
        }
        Spacer(Modifier.height(8.dp))
        val recommendedReady = VoskModelInstaller.isRecommendedReady(voskStatuses)
        Text(
            text = stringResource(
                if (recommendedReady) {
                    R.string.settings_listen_models_ready_yes
                } else {
                    R.string.settings_listen_models_ready_no
                },
            ),
            style = MaterialTheme.typography.bodyLarge,
            color = if (recommendedReady) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.error
            },
            modifier = Modifier.testTag("settings_listen_models_ready"),
        )
        Text(
            text = stringResource(
                R.string.settings_listen_models_size,
                formatBytes(listenModelBytes),
            ),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.testTag("settings_listen_models_size"),
        )
        Spacer(Modifier.height(6.dp))
        if (modelsInstalling) {
            Spacer(Modifier.height(4.dp))
            LinearProgressIndicator(
                progress = { installProgress.coerceIn(0f, 1f) },
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("settings_listen_models_progress"),
            )
            installStep?.let { step ->
                Text(
                    text = installStepTemplate.format(step),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .padding(top = 4.dp)
                        .testTag("settings_listen_models_step"),
                )
            }
            Spacer(Modifier.height(6.dp))
        }
        val orderedVoskStatuses = remember(voskStatuses) {
            val recommendedOrder = VoskModelInstaller.RECOMMENDED_LANGS
                .withIndex()
                .associate { it.value to it.index }
            voskStatuses.sortedWith(
                compareBy<VoskLanguageStatus> {
                    recommendedOrder[it.langTag] ?: Int.MAX_VALUE
                }.thenBy { it.langTag },
            )
        }
        orderedVoskStatuses.forEach { status ->
            val label = stringResource(
                langLabels[status.langTag] ?: R.string.settings_listen_models_lang_en,
            )
            VoskLangStatusRow(
                status = status,
                label = label,
                okLabel = langOk,
                missingLabel = langMissing,
                installing = modelsInstalling || maintenanceRunning,
                onInstall = {
                    if (modelsInstalling || maintenanceRunning || status.installed) {
                        return@VoskLangStatusRow
                    }
                    scope.launch {
                        modelsInstalling = true
                        installProgress = 0f
                        statusMessage = null
                        installStep = "Vosk ${status.langTag}"
                        val result = runCatching {
                            withContext(Dispatchers.IO) {
                                container.voskModelInstaller.ensureInstalled(
                                    langTag = status.langTag,
                                    allowMeteredDownloads = allowMeteredModelDownloads,
                                ) { p ->
                                    installProgress = p.coerceIn(0f, 1f)
                                }
                            }
                            refreshModelSizesAndStatus()
                        }
                        modelsInstalling = false
                        installStep = null
                        if (result.isSuccess) {
                            statusMessage = listenModelsLangInstalledTemplate.format(label)
                            DiagnosticLog.info(
                                "Settings",
                                "vosk lang installed lang=${status.langTag}",
                            )
                        } else {
                            val msg = result.exceptionOrNull()?.message?.take(200) ?: "error"
                            statusMessage = listenModelsFailedTemplate.format(msg)
                            DiagnosticLog.info(
                                "Settings",
                                "vosk lang install failed lang=${status.langTag}: $msg",
                            )
                            refreshModelSizesAndStatus()
                        }
                    }
                },
            )
        }
        Text(
            text = stringResource(R.string.settings_listen_models_mt_note),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier
                .padding(top = 4.dp)
                .widthIn(max = FrameNestDimens.ReadableContentMaxWidth)
                .testTag("settings_listen_models_mt_note"),
        )
        Spacer(Modifier.height(8.dp))
        Button(
            onClick = {
                if (modelsInstalling || maintenanceRunning) return@Button
                scope.launch {
                    modelsInstalling = true
                    installProgress = 0f
                    statusMessage = null
                    val result = runCatching {
                        val langs = VoskModelInstaller.RECOMMENDED_LANGS
                        langs.forEachIndexed { index, lang ->
                            installStep = "Vosk $lang"
                            val base = index.toFloat() / (langs.size + 1)
                            val span = 1f / (langs.size + 1)
                            withContext(Dispatchers.IO) {
                                container.voskModelInstaller.ensureInstalled(
                                    langTag = lang,
                                    allowMeteredDownloads = allowMeteredModelDownloads,
                                ) { p ->
                                    installProgress = (base + p * span).coerceIn(0f, 0.92f)
                                }
                            }
                        }
                        installStep = "ML Kit zh↔en"
                        installProgress = 0.93f
                        withContext(Dispatchers.IO) {
                            val mt = MlKitMtEngine(
                                allowMeteredDownloads = allowMeteredModelDownloads,
                            )
                            try {
                                mt.ensureModel("zh", "en")
                                installProgress = 0.96f
                                mt.ensureModel("en", "zh")
                            } finally {
                                mt.close()
                            }
                            // Tiny legacy JSON packs (FN-13); ignore failure.
                            runCatching { container.listenModelManager.installCoreModels() }
                        }
                        installProgress = 1f
                        refreshModelSizesAndStatus()
                    }
                    modelsInstalling = false
                    installStep = null
                    if (result.isSuccess) {
                        statusMessage = listenModelsInstalled
                        DiagnosticLog.info("Settings", "recommended listen models installed")
                    } else {
                        val msg = result.exceptionOrNull()?.message?.take(200) ?: "error"
                        statusMessage = listenModelsFailedTemplate.format(msg)
                        DiagnosticLog.info("Settings", "listen models install failed: $msg")
                        refreshModelSizesAndStatus()
                    }
                }
            },
            enabled = !modelsInstalling && !maintenanceRunning,
            modifier = Modifier
                .heightIn(min = FrameNestDimens.MinTouchTarget)
                .minimumInteractiveComponentSize()
                .testTag("settings_install_listen_models"),
        ) {
            Text(
                if (modelsInstalling) {
                    stringResource(
                        R.string.settings_install_listen_models_busy,
                        (installProgress * 100).toInt().coerceIn(0, 100),
                    )
                } else {
                    stringResource(R.string.settings_install_listen_models)
                },
            )
        }
        val missingLangs = orderedVoskStatuses.filter { !it.installed }.map { it.langTag }
        if (missingLangs.isNotEmpty()) {
            Spacer(Modifier.height(8.dp))
            OutlinedButton(
                onClick = {
                    if (modelsInstalling || maintenanceRunning) return@OutlinedButton
                    scope.launch {
                        modelsInstalling = true
                        installProgress = 0f
                        statusMessage = null
                        val result = runCatching {
                            missingLangs.forEachIndexed { index, lang ->
                                installStep = "Vosk $lang"
                                val base = index.toFloat() / missingLangs.size
                                val span = 1f / missingLangs.size
                                withContext(Dispatchers.IO) {
                                    container.voskModelInstaller.ensureInstalled(
                                        langTag = lang,
                                        allowMeteredDownloads = allowMeteredModelDownloads,
                                    ) { p ->
                                        installProgress = (base + p * span).coerceIn(0f, 1f)
                                    }
                                }
                            }
                            refreshModelSizesAndStatus()
                        }
                        modelsInstalling = false
                        installStep = null
                        if (result.isSuccess) {
                            statusMessage = listenModelsAllInstalled
                            DiagnosticLog.info(
                                "Settings",
                                "all missing vosk langs installed count=${missingLangs.size}",
                            )
                        } else {
                            val msg = result.exceptionOrNull()?.message?.take(200) ?: "error"
                            statusMessage = listenModelsFailedTemplate.format(msg)
                            DiagnosticLog.info(
                                "Settings",
                                "install all missing vosk failed: $msg",
                            )
                            refreshModelSizesAndStatus()
                        }
                    }
                },
                enabled = !modelsInstalling && !maintenanceRunning,
                modifier = Modifier
                    .heightIn(min = FrameNestDimens.MinTouchTarget)
                    .minimumInteractiveComponentSize()
                    .testTag("settings_install_all_missing_vosk"),
            ) {
                Text(stringResource(R.string.settings_install_listen_models_all_missing))
            }
        }
        Spacer(Modifier.height(8.dp))
        OutlinedButton(
            onClick = { pendingClearTarget = SettingsClearTarget.Models },
            enabled = !modelsInstalling && !maintenanceRunning,
            modifier = Modifier
                .heightIn(min = FrameNestDimens.MinTouchTarget)
                .minimumInteractiveComponentSize()
                .testTag("settings_clear_listen_models"),
        ) {
            Text(stringResource(R.string.settings_clear_listen_models))
        }

        Spacer(Modifier.height(20.dp))
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

        Spacer(Modifier.height(20.dp))
        Text(
            text = stringResource(R.string.settings_thumb_concurrency_row),
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.testTag("settings_thumb_concurrency_row"),
        )
        Text(
            text = stringResource(R.string.settings_thumb_concurrency_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.widthIn(max = FrameNestDimens.ReadableContentMaxWidth),
        )
        Spacer(Modifier.height(8.dp))
        LanguageOption(
            label = stringResource(R.string.settings_thumb_concurrency_1),
            selected = thumbConcurrency == 1,
            testTag = "settings_thumb_concurrency_1",
            onSelect = {
                thumbConcurrency = 1
                prefs.setThumbnailConcurrency(1)
                container.thumbnailRepository.ensureWorkers()
            },
        )
        LanguageOption(
            label = stringResource(R.string.settings_thumb_concurrency_2),
            selected = thumbConcurrency == 2,
            testTag = "settings_thumb_concurrency_2",
            onSelect = {
                thumbConcurrency = 2
                prefs.setThumbnailConcurrency(2)
                container.thumbnailRepository.ensureWorkers()
            },
        )

        Spacer(Modifier.height(20.dp))
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
        Spacer(Modifier.height(8.dp))
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

                Spacer(Modifier.height(16.dp))
            }
        }
    }

    pendingClearTarget?.let { target ->
        val title = stringResource(
            when (target) {
                SettingsClearTarget.ListenTranslate ->
                    R.string.settings_clear_listen_translate_confirm_title
                SettingsClearTarget.Models -> R.string.settings_clear_listen_models_confirm_title
            },
        )
        val body = stringResource(
            when (target) {
                SettingsClearTarget.ListenTranslate ->
                    R.string.settings_clear_listen_translate_confirm_body
                SettingsClearTarget.Models -> R.string.settings_clear_listen_models_confirm_body
            },
        )
        AlertDialog(
            onDismissRequest = {
                if (!maintenanceRunning) pendingClearTarget = null
            },
            title = { Text(title) },
            text = { Text(body) },
            confirmButton = {
                TextButton(
                    onClick = {
                        pendingClearTarget = null
                        maintenanceRunning = true
                        scope.launch {
                            try {
                                when (target) {
                                    SettingsClearTarget.ListenTranslate -> {
                                        val result = container.cacheMaintenance
                                            .clearListenTranslateCache()
                                        listenTranslateBytes = result.remainingApproxBytes
                                        statusMessage = listenTranslateClearedTemplate.format(
                                            formatBytes(result.freedApproxBytes),
                                        )
                                        DiagnosticLog.info(
                                            "Settings",
                                            "listen-translate cache cleared " +
                                                "freed=${result.freedApproxBytes}",
                                        )
                                    }
                                    SettingsClearTarget.Models -> {
                                        val result = container.cacheMaintenance.clearListenModels()
                                        refreshModelSizesAndStatus()
                                        statusMessage = listenModelsClearedTemplate.format(
                                            formatBytes(result.freedApproxBytes),
                                        )
                                        DiagnosticLog.info(
                                            "Settings",
                                            "listen models cleared freed=${result.freedApproxBytes}",
                                        )
                                    }
                                }
                            } catch (cancelled: CancellationException) {
                                throw cancelled
                            } catch (error: Exception) {
                                reportMaintenanceFailure("destructive cache clear", error)
                            } finally {
                                maintenanceRunning = false
                            }
                        }
                    },
                    enabled = !maintenanceRunning,
                    modifier = Modifier.testTag("settings_clear_confirm"),
                ) {
                    Text(stringResource(R.string.action_confirm_clear))
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { pendingClearTarget = null },
                    enabled = !maintenanceRunning,
                ) {
                    Text(stringResource(R.string.action_cancel))
                }
            },
        )
    }
}

@Composable
private fun VoskLangStatusRow(
    status: VoskLanguageStatus,
    label: String,
    okLabel: String,
    missingLabel: String,
    installing: Boolean,
    onInstall: () -> Unit,
) {
    val stateLabel = if (status.installed) okLabel else missingLabel
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = FrameNestDimens.MinTouchTarget)
            .padding(vertical = 2.dp)
            .testTag("settings_vosk_lang_${status.langTag}"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text = stringResource(
                R.string.settings_listen_models_lang_line,
                label,
                status.langTag,
                formatBytes(status.approxBytes),
                stateLabel,
            ),
            style = MaterialTheme.typography.bodyMedium,
            color = if (status.installed) {
                MaterialTheme.colorScheme.onSurface
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
            modifier = Modifier
                .weight(1f)
                .padding(end = 8.dp),
        )
        if (status.installed) {
            Text(
                text = stringResource(R.string.settings_install_lang_done),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.testTag("settings_vosk_lang_${status.langTag}_done"),
            )
        } else {
            OutlinedButton(
                onClick = onInstall,
                enabled = !installing,
                modifier = Modifier
                    .minimumInteractiveComponentSize()
                    .testTag("settings_vosk_lang_${status.langTag}_install"),
            ) {
                Text(stringResource(R.string.settings_install_lang))
            }
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

private enum class SettingsClearTarget { ListenTranslate, Models }

package com.framenest.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.framenest.BuildConfig
import kotlinx.coroutines.CancellationException

private const val SOURCE_ASR_LICENSE_TITLE = "开源许可与源码（ASR 实验）"

/** No assets, context, or loading state are accessed in the ordinary stable profile. */
@Composable
internal fun SourceAsrLicensesEntry(
    sourceProfile: Boolean = BuildConfig.SHERPA_ASR_SOURCE,
    loadNotice: (suspend (String) -> List<String>)? = null,
) {
    if (!sourceProfile) return
    var open by remember { mutableStateOf(false) }
    TextButton(
        onClick = { open = true },
        modifier = Modifier.heightIn(min = 48.dp).testTag("source_asr_licenses_entry"),
    ) {
        Text(SOURCE_ASR_LICENSE_TITLE)
    }
    if (open) {
        val assets = LocalContext.current.applicationContext.assets
        val assetLoader: suspend (String) -> List<String> = remember(assets) {
            { path -> readSourceAsrNotice(path) { assets.open(it) } }
        }
        SourceAsrLicensesDialog(loadNotice ?: assetLoader, onDismiss = { open = false })
    }
}

@Composable
private fun SourceAsrLicensesDialog(
    loadNotice: suspend (String) -> List<String>,
    onDismiss: () -> Unit,
) {
    // Removing the dialog cancels its LaunchedEffect. Reopening always starts with the supplement.
    var selected by remember { mutableStateOf<SourceAsrNotice?>(SourceAsrLicenses.supplemental) }
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(
            modifier = Modifier
                .padding(horizontal = 16.dp)
                .widthIn(max = 720.dp)
                .fillMaxWidth()
                .fillMaxHeight(0.9f)
                .testTag("source_asr_licenses_dialog"),
            shape = MaterialTheme.shapes.extraLarge,
            tonalElevation = 6.dp,
        ) {
            Column(Modifier.padding(16.dp)) {
                Text(SOURCE_ASR_LICENSE_TITLE, style = MaterialTheme.typography.titleLarge)
                Spacer(Modifier.height(8.dp))
                val notice = selected
                if (notice == null) {
                    Text("补充说明及 AAR 原始许可文件（${SourceAsrLicenses.aarNotices.size} 份）")
                    LazyColumn(
                        modifier = Modifier.weight(1f).testTag("source_asr_licenses_index"),
                    ) {
                        items(
                            listOf(SourceAsrLicenses.supplemental) + SourceAsrLicenses.aarNotices,
                            key = { it.assetPath },
                        ) { item ->
                            TextButton(
                                onClick = { selected = item },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .heightIn(min = 48.dp)
                                    .testTag("source_asr_notice_${item.assetPath}"),
                            ) {
                                Text(item.title, modifier = Modifier.fillMaxWidth())
                            }
                        }
                    }
                } else {
                    Text(
                        notice.title,
                        style = MaterialTheme.typography.titleSmall,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(Modifier.height(8.dp))
                    key(notice.assetPath) {
                        SourceAsrNoticePages(notice, loadNotice, Modifier.weight(1f))
                    }
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    if (notice != null) {
                        TextButton(
                            onClick = { selected = null },
                            modifier = Modifier.heightIn(min = 48.dp).testTag("source_asr_licenses_back"),
                        ) {
                            Text("许可目录（${SourceAsrLicenses.aarNotices.size}）")
                        }
                    } else {
                        Spacer(Modifier.weight(1f))
                    }
                    TextButton(
                        onClick = onDismiss,
                        modifier = Modifier.heightIn(min = 48.dp).testTag("source_asr_licenses_close"),
                    ) {
                        Text("关闭")
                    }
                }
            }
        }
    }
}

@Composable
private fun SourceAsrNoticePages(
    notice: SourceAsrNotice,
    loadNotice: suspend (String) -> List<String>,
    modifier: Modifier,
) {
    var pages by remember { mutableStateOf<List<String>?>(null) }
    var failed by remember { mutableStateOf(false) }
    var page by remember { mutableIntStateOf(0) }
    LaunchedEffect(notice.assetPath, loadNotice) {
        pages = null
        failed = false
        page = 0
        try {
            pages = loadNotice(notice.assetPath)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            failed = true
        }
    }
    val loaded = pages
    when {
        failed -> Box(modifier, contentAlignment = Alignment.Center) {
            Text(
                "无法读取或校验此许可文件。文件可能缺失、损坏或超出大小限制。请返回目录查看其他声明，或关闭后重试。",
                modifier = Modifier.verticalScroll(rememberScrollState()).testTag("source_asr_notice_error"),
            )
        }
        loaded == null -> Box(modifier, contentAlignment = Alignment.Center) {
            CircularProgressIndicator(Modifier.testTag("source_asr_notice_loading"))
        }
        else -> Column(modifier) {
            // Only this bounded page is laid out/selectable, including any plain source URLs.
            key(page) {
                SelectionContainer(Modifier.weight(1f).fillMaxWidth()) {
                    Text(
                        loaded[page],
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier
                            .fillMaxSize()
                            .verticalScroll(rememberScrollState())
                            .testTag("source_asr_notice_text"),
                    )
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                TextButton(
                    onClick = { page = (page - 1).coerceAtLeast(0) },
                    enabled = page > 0,
                    modifier = Modifier.heightIn(min = 48.dp).testTag("source_asr_page_previous"),
                ) {
                    Text("上一页")
                }
                Text(
                    "${page + 1} / ${loaded.size} 页",
                    style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier.testTag("source_asr_page_count"),
                )
                TextButton(
                    onClick = { page = (page + 1).coerceAtMost(loaded.lastIndex) },
                    enabled = page < loaded.lastIndex,
                    modifier = Modifier.heightIn(min = 48.dp).testTag("source_asr_page_next"),
                ) {
                    Text("下一页")
                }
            }
        }
    }
}

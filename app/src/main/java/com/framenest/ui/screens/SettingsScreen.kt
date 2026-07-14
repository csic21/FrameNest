package com.framenest.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.framenest.R
import com.framenest.ui.theme.FrameNestDimens

@Composable
fun SettingsScreen(modifier: Modifier = Modifier) {
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
        Spacer(Modifier.height(8.dp))
        Text(
            text = stringResource(R.string.settings_placeholder_body),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.widthIn(max = FrameNestDimens.ReadableContentMaxWidth),
        )
        Spacer(Modifier.height(16.dp))
        Text(
            text = stringResource(R.string.settings_cache_row),
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.testTag("settings_cache_row"),
        )
        Text(
            text = stringResource(R.string.settings_cache_value_fake),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(12.dp))
        Text(
            text = stringResource(R.string.settings_subtitle_lang_row),
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.testTag("settings_subtitle_lang_row"),
        )
        Text(
            text = stringResource(R.string.settings_subtitle_lang_value_fake),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

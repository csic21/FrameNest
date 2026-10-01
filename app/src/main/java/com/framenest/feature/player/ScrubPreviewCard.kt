package com.framenest.feature.player

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.layout
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.testTag
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.framenest.ui.theme.FrameNestIvory
import com.framenest.ui.theme.FrameNestLavender
import com.framenest.ui.theme.FrameNestMidnight
import com.framenest.ui.theme.FrameNestPeriwinkle

internal val ScrubPreviewCardWidth = 168.dp
private val ScrubPreviewImageHeight = 94.dp
private val PreviewShape = RoundedCornerShape(12.dp)

internal fun scrubPreviewBitmap(frames: Map<Long, Bitmap>, positionMs: Long): Bitmap? {
    val key = ScrubPreviewPlan.nearestReadyMs(positionMs, frames.keys) ?: return null
    val bitmap = frames[key] ?: return null
    if (bitmap.isRecycled) return null
    return bitmap
}

/**
 * Draws [this] above its parent without growing the parent. [lift] is the gap
 * between the card's caret and the parent's top edge.
 */
internal fun Modifier.scrubPreviewOverlay(x: Dp, lift: Dp): Modifier = layout { measurable, constraints ->
    val placeable = measurable.measure(
        constraints.copy(
            minWidth = 0,
            minHeight = 0,
            maxWidth = Constraints.Infinity,
            maxHeight = Constraints.Infinity,
        ),
    )
    layout(0, 0) {
        placeable.place(x.roundToPx(), -lift.roundToPx() - placeable.height)
    }
}

@Composable
internal fun ScrubPreviewCard(
    timeLabel: String,
    bitmap: Bitmap?,
    unavailable: Boolean,
    description: String,
    showCaret: Boolean = true,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .width(ScrubPreviewCardWidth)
            .clearAndSetSemantics {
                if (description.isNotEmpty()) contentDescription = description
                testTag = "player_scrub_preview"
            },
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(ScrubPreviewImageHeight)
                .shadow(10.dp, PreviewShape)
                .clip(PreviewShape)
                .background(FrameNestMidnight)
                .border(1.dp, FrameNestPeriwinkle.copy(alpha = 0.92f), PreviewShape),
        ) {
            when {
                bitmap != null -> {
                    Image(
                        bitmap = bitmap.asImageBitmap(),
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
                unavailable -> {
                    Icon(
                        imageVector = Icons.Filled.Movie,
                        contentDescription = null,
                        tint = FrameNestLavender,
                        modifier = Modifier
                            .align(Alignment.Center)
                            .size(28.dp),
                    )
                }
                else -> {
                    CircularProgressIndicator(
                        modifier = Modifier
                            .align(Alignment.Center)
                            .size(22.dp),
                        color = FrameNestLavender,
                        strokeWidth = 2.dp,
                    )
                }
            }
            Text(
                text = timeLabel,
                color = FrameNestIvory,
                style = MaterialTheme.typography.labelMedium,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 6.dp)
                    .background(Color.Black.copy(alpha = 0.62f), RoundedCornerShape(6.dp))
                    .padding(horizontal = 8.dp, vertical = 2.dp),
            )
        }
        if (showCaret) {
            Canvas(
                modifier = Modifier.size(width = 14.dp, height = 7.dp),
            ) {
                val path = Path().apply {
                    moveTo(0f, 0f)
                    lineTo(size.width, 0f)
                    lineTo(size.width / 2f, size.height)
                    close()
                }
                drawPath(path, FrameNestPeriwinkle)
            }
        }
    }
}

package com.framenest.data.thumbnail

/**
 * Where a direct SMB cover looks for one picture, and how large that picture is.
 *
 * Eight seconds skips a black fade. Twenty seconds is the single retry when
 * that picture is still black or flat. Both stay near the opening. The frame
 * itself keeps the video's aspect ratio; see [ThumbnailFrameGeometry].
 */
object ThumbnailVlcPlan {
    const val OPENING_MS: Long = 8_000L
    const val LATER_MS: Long = 20_000L

    /** Ceiling for one file. A usable frame returns earlier. */
    const val BUDGET_MS: Long = 8_000L

    fun seekTargetsMs(): List<Long> = listOf(OPENING_MS, LATER_MS)
}

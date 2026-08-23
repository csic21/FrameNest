package com.framenest.feature.subtitle

import com.framenest.player.PlayerTrack
import org.junit.Assert.assertEquals
import org.junit.Test

class SubtitleTrackListsTest {

    @Test
    fun embedded_hidesDisabledAndSlaveTracks() {
        val tracks = listOf(
            PlayerTrack(-1, "Disable", PlayerTrack.Kind.Subtitle),
            PlayerTrack(0, "English", PlayerTrack.Kind.Subtitle),
            PlayerTrack(1, "movie.zh.srt", PlayerTrack.Kind.Subtitle, isExternalSlave = true),
            PlayerTrack(2, "Audio 2", PlayerTrack.Kind.Audio),
        )

        val embedded = SubtitleTrackLists.embedded(tracks)

        assertEquals(listOf(0), embedded.map { it.id })
        assertEquals(listOf("English"), embedded.map { it.name })
    }
}

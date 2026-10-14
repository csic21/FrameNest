package com.framenest.feature.listen_translate

/** Bounded experimental acoustic evidence, not a VAD or proof of sentence completion. */
internal fun japanesePcmQuietRuns(pcm: ShortArray, startMs: Long): List<JapaneseQuietRun> {
    require(startMs in 0L..(Long.MAX_VALUE - JapaneseOwnedPrefixPolicy.MAX_READ_MS))
    require(pcm.size <= 16 * JapaneseOwnedPrefixPolicy.MAX_READ_MS)
    val frameSamples = 320 // 20 ms at the production 16 kHz rate.
    val quietMeanSquare = 32768.0 * 32768.0 * 0.00031622776601683794 // -35 dBFS
    val runs = mutableListOf<JapaneseQuietRun>()
    var quietStart: Int? = null
    var offset = 0
    fun flush(endSample: Int) {
        val begin = quietStart ?: return
        if (endSample - begin >= 2_560) {
            runs += JapaneseQuietRun(startMs + begin / 16L, startMs + endSample / 16L)
        }
        quietStart = null
    }
    while (offset < pcm.size) {
        val end = minOf(offset + frameSamples, pcm.size)
        var sum = 0.0
        for (index in offset until end) sum += pcm[index].toDouble() * pcm[index].toDouble()
        if (sum / (end - offset) <= quietMeanSquare) {
            if (quietStart == null) quietStart = offset
        } else flush(offset)
        offset = end
    }
    flush(pcm.size)
    return runs
}

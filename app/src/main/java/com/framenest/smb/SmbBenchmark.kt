package com.framenest.smb

/**
 * Micro-benchmarks for FN-02 decision data: first byte, sequential throughput, random seek.
 */
object SmbBenchmark {

    private const val SEQ_CHUNK = 256 * 1024
    private const val SEQ_TARGET_BYTES = 8L * 1024L * 1024L
    private const val RANDOM_READ_SIZE = 64 * 1024
    private const val RANDOM_SEEKS = 8

    fun run(
        client: SmbClient,
        host: String,
        share: String,
        path: String,
    ): SmbBenchmarkResult {
        val meta = client.metadata(share, path)
        if (meta.isDirectory) {
            throw SmbException(SmbError.NotFound("Path is a directory"))
        }
        val size = meta.sizeBytes
        client.openRandomAccess(share, path).use { raf ->
            val firstByteMs = measureFirstByte(raf)
            val (seqBytes, seqMs) = measureSequential(raf, size)
            val seqMbps = if (seqMs > 0) {
                (seqBytes * 8.0) / (seqMs / 1000.0) / 1_000_000.0
            } else {
                0.0
            }
            val (seeks, seekTotalMs) = measureRandomSeeks(raf, size)
            val avg = if (seeks > 0) seekTotalMs.toDouble() / seeks else 0.0
            return SmbBenchmarkResult(
                host = host,
                share = share,
                path = path,
                fileSizeBytes = size,
                firstByteMs = firstByteMs,
                sequentialBytes = seqBytes,
                sequentialMs = seqMs,
                sequentialMbps = seqMbps,
                randomSeeks = seeks,
                randomSeekTotalMs = seekTotalMs,
                randomSeekAvgMs = avg,
                notes = "chunk=${SEQ_CHUNK} randomRead=$RANDOM_READ_SIZE",
            )
        }
    }

    private fun measureFirstByte(raf: SmbRandomAccess): Long {
        val buf = ByteArray(1)
        val start = System.nanoTime()
        raf.readAt(0L, buf, 0, 1)
        return (System.nanoTime() - start) / 1_000_000L
    }

    private fun measureSequential(raf: SmbRandomAccess, size: Long): Pair<Long, Long> {
        if (size <= 0L) return 0L to 0L
        val target = minOf(size, SEQ_TARGET_BYTES)
        val buf = ByteArray(SEQ_CHUNK)
        var position = 0L
        var total = 0L
        val start = System.nanoTime()
        while (position < target) {
            val want = minOf(buf.size.toLong(), target - position).toInt()
            val n = raf.readAt(position, buf, 0, want)
            if (n <= 0) break
            position += n
            total += n
        }
        val ms = (System.nanoTime() - start) / 1_000_000L
        return total to ms
    }

    private fun measureRandomSeeks(raf: SmbRandomAccess, size: Long): Pair<Int, Long> {
        if (size <= RANDOM_READ_SIZE) return 0 to 0L
        val buf = ByteArray(RANDOM_READ_SIZE)
        val positions = samplePositions(size, RANDOM_SEEKS, RANDOM_READ_SIZE.toLong())
        var totalMs = 0L
        var count = 0
        for (pos in positions) {
            val start = System.nanoTime()
            val n = raf.readAt(pos, buf, 0, buf.size)
            totalMs += (System.nanoTime() - start) / 1_000_000L
            if (n > 0) count++
        }
        return count to totalMs
    }

    /**
     * Deterministic positions (not crypto-random) so runs are comparable.
     */
    fun samplePositions(fileSize: Long, count: Int, readSize: Long): List<Long> {
        if (fileSize <= readSize || count <= 0) return emptyList()
        val maxStart = fileSize - readSize
        return (0 until count).map { i ->
            val t = (i + 1).toDouble() / (count + 1).toDouble()
            (maxStart * t).toLong().coerceIn(0L, maxStart)
        }
    }
}

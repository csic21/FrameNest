package com.framenest.feature.listen_translate.mt

/** Process-wide Hy-MT2 file lifetime, shared by every engine and installer instance. */
internal class HyMtModelUseGuard {
    private val lock = Any()
    private var users = 0
    private var deleting = false

    fun acquire(): Lease = synchronized(lock) {
        check(!deleting) { "Hy-MT2 模型正在清理，请稍后重试" }
        users++
        Lease()
    }

    /** Only synchronous file deletion belongs here; no thread-owned lock crosses suspension. */
    fun <T> whileUnused(delete: () -> T): T {
        synchronized(lock) {
            check(users == 0 && !deleting) { "Hy-MT2 模型仍在使用，请退出播放器后重试" }
            deleting = true
        }
        return try {
            delete()
        } finally {
            synchronized(lock) { deleting = false }
        }
    }

    inner class Lease internal constructor() : AutoCloseable {
        private var released = false

        override fun close() = synchronized(lock) {
            if (!released) {
                released = true
                check(users > 0)
                users--
            }
        }
    }

    companion object {
        val shared = HyMtModelUseGuard()
    }
}

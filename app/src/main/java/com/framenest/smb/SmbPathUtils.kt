package com.framenest.smb

/**
 * Path helpers and list sorting used by browser + spike UI.
 */
object SmbPathUtils {

    fun normalizeRelative(path: String): String {
        if (path.isBlank() || path == "/" || path == "\\") return ""
        return path
            .replace('\\', '/')
            .trim('/')
    }

    fun join(parent: String, child: String): String {
        val p = normalizeRelative(parent)
        val c = child.trim().trim('/')
        return when {
            p.isEmpty() -> c
            c.isEmpty() -> p
            else -> "$p/$c"
        }
    }

    fun parentOf(path: String): String {
        val normalized = normalizeRelative(path)
        if (normalized.isEmpty()) return ""
        val idx = normalized.lastIndexOf('/')
        return if (idx <= 0) "" else normalized.substring(0, idx)
    }

    /**
     * Directories first, then case-insensitive name order (MVP browser rule).
     */
    fun sortEntries(entries: List<SmbEntry>): List<SmbEntry> =
        entries.sortedWith(
            compareBy<SmbEntry> { !it.isDirectory }
                .thenBy(String.CASE_INSENSITIVE_ORDER) { it.name },
        )

    fun isDotEntry(name: String): Boolean = name == "." || name == ".."
}

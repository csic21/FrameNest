package com.framenest.player

/** Shared redaction for application diagnostics, SMB logs and user-visible failures. */
object CredentialRedactor {
    // Consume through the last @ in an authority, including malformed raw passwords with @.
    private val userinfo = Regex("""(\b[A-Za-z][A-Za-z0-9+.-]*://)([^/\s]+)@""")
    private val assignment = Regex(
        """(?i)(?<![\w-])(sout-smbj-password|sout-smbj-username|smb-pwd|smb-user|password|passwd|pwd|pass)\s*[=:]\s*""",
    )

    fun redact(message: String?): String {
        if (message.isNullOrEmpty()) return ""
        val urlsHidden = userinfo.replace(message) {
            it.groupValues[1] + if (':' in it.groupValues[2]) "***:***@" else "***@"
        }
        // Scan quoted values rather than using recursive/backtracking regex on remote text.
        return buildString {
            var cursor = 0
            while (cursor < urlsHidden.length) {
                val match = assignment.find(urlsHidden, cursor)
                if (match == null) {
                    append(urlsHidden, cursor, urlsHidden.length)
                    break
                }
                append(urlsHidden, cursor, match.range.first)
                append(match.groupValues[1]).append("=***")
                var end = match.range.last + 1
                val quote = urlsHidden.getOrNull(end)
                if (quote == '"' || quote == '\'') {
                    end++
                    while (end < urlsHidden.length) {
                        val c = urlsHidden[end++]
                        if (c == '\\' && end < urlsHidden.length) end++
                        else if (c == quote) break
                    }
                } else {
                    // VLC echoes raw option strings; spaces may be part of the password.
                    val option = match.groupValues[1].contains("smb", ignoreCase = true)
                    while (end < urlsHidden.length && if (option) {
                        urlsHidden[end] != '\r' && urlsHidden[end] != '\n'
                    } else {
                        !urlsHidden[end].isWhitespace() && urlsHidden[end] !in ",;"
                    }) end++
                }
                cursor = end
            }
        }
    }
}

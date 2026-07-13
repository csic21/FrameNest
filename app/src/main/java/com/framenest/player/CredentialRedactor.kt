package com.framenest.player

/**
 * Strips credential-like material from strings before they reach logs or UI.
 *
 * Used for libVLC / system error messages that might echo option strings or URLs.
 */
object CredentialRedactor {
    private val smbUserinfo = Regex(
        """smb://([^/@\s]+):([^/@\s]+)@""",
        RegexOption.IGNORE_CASE,
    )
    private val genericUserinfo = Regex(
        """(://)([^/@\s]+):([^/@\s]+)@""",
    )
    private val optionPassword = Regex(
        """(:(?:smb-pwd|sout-smbj-password)=)(\S+)""",
        RegexOption.IGNORE_CASE,
    )
    private val optionUser = Regex(
        """(:(?:smb-user|sout-smbj-username)=)(\S+)""",
        RegexOption.IGNORE_CASE,
    )
    private val passwordKeyValue = Regex(
        """(?i)(password|passwd|pwd)\s*[=:]\s*\S+""",
    )

    fun redact(message: String?): String {
        if (message.isNullOrEmpty()) return ""
        var out = message
        out = smbUserinfo.replace(out, "smb://***:***@")
        out = genericUserinfo.replace(out, "$1***:***@")
        out = optionPassword.replace(out, "$1***")
        out = optionUser.replace(out, "$1***")
        out = passwordKeyValue.replace(out, "$1=***")
        return out
    }
}

package com.framenest.data.server

import com.framenest.smb.SmbError
import com.framenest.smb.SmbErrorMapper
import com.framenest.smb.SmbException

/**
 * Maps SMB / repository failures to short, credential-free UI strings.
 */
object SmbUiMessages {

    fun fromThrowable(error: Throwable): String {
        return when (error) {
            is SmbException -> fromSmbError(error.error)
            is IllegalArgumentException -> error.message?.takeIf { it.isNotBlank() }
                ?: "无效的服务器或路径"
            is IllegalStateException -> error.message?.takeIf { it.isNotBlank() }
                ?: "服务器状态无效"
            else -> {
                val mapped = SmbErrorMapper.map(error)
                fromSmbError(mapped)
            }
        }
    }

    fun fromSmbError(error: SmbError): String = when (error) {
        is SmbError.Auth -> "认证失败，请检查用户名和密码后重试"
        is SmbError.Network -> "网络错误，请检查主机地址、端口与局域网连接"
        is SmbError.NotFound -> "共享或路径不存在"
        is SmbError.Permission -> "没有访问权限"
        is SmbError.Disconnected -> "连接已断开，请刷新重试"
        is SmbError.Unknown -> {
            val safe = SmbErrorMapper.redactSecrets(error.message).ifBlank { "未知错误" }
            "操作失败：$safe"
        }
    }
}

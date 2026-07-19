package com.framenest.feature.listen_translate

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities

/** Shared network guard for the ASR and MT model stores. */
object ModelDownloadNetworkPolicy {
    fun isAllowed(
        allowMetered: Boolean,
        connected: Boolean,
        wifiOrEthernet: Boolean,
    ): Boolean = connected && (allowMetered || wifiOrEthernet)

    fun requireAllowed(context: Context, allowMetered: Boolean) {
        if (allowMetered) return
        val connectivity = context.applicationContext
            .getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = connectivity.activeNetwork
        val capabilities = network?.let(connectivity::getNetworkCapabilities)
        val connected = capabilities?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
        val wifiOrEthernet = capabilities?.let {
            it.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
                it.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
        } == true
        check(isAllowed(allowMetered, connected, wifiOrEthernet)) {
            "模型默认仅在 Wi-Fi 或有线网络下载；可在设置中允许移动网络"
        }
    }
}

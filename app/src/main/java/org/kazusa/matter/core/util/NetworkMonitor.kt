package org.kazusa.matter.core.util

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged

/**
 * 网络状态探测：用于「移动网络下不自动加载原图」的自适应流量节流。
 *
 * 依赖清单中已声明的 `ACCESS_NETWORK_STATE` 权限。
 */
object NetworkMonitor {

    private fun connectivityManager(context: Context): ConnectivityManager? =
        context.applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager

    /** 当前是否有可用网络 */
    fun isConnected(context: Context): Boolean {
        val manager = connectivityManager(context) ?: return false
        val capabilities = runCatching {
            manager.activeNetwork?.let { manager.getNetworkCapabilities(it) }
        }.getOrNull() ?: return false
        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    /**
     * 当前网络是否为「非计费网络」（Wi-Fi / 以太网 / 未开启流量计费的移动网络）。
     *
     * 无网络时返回 false，让调用方走最省流量的分支。
     */
    fun isUnmetered(context: Context): Boolean {
        val manager = connectivityManager(context) ?: return false
        val capabilities = runCatching {
            manager.activeNetwork?.let { manager.getNetworkCapabilities(it) }
        }.getOrNull() ?: return false
        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
    }

    /** 持续观察「非计费网络」状态，用于后续在查看器内实时响应网络切换 */
    fun observeUnmetered(context: Context): Flow<Boolean> = callbackFlow {
        val manager = connectivityManager(context)
        if (manager == null) {
            trySend(false)
            awaitClose { }
            return@callbackFlow
        }

        fun push() = trySend(isUnmetered(context))

        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) { push() }
            override fun onLost(network: Network) { push() }
            override fun onCapabilitiesChanged(
                network: Network,
                networkCapabilities: NetworkCapabilities
            ) { push() }
        }

        push()
        runCatching { manager.registerDefaultNetworkCallback(callback) }
            .onFailure {
                trySend(false)
                close()
                return@callbackFlow
            }
        awaitClose { runCatching { manager.unregisterNetworkCallback(callback) } }
    }.distinctUntilChanged()
}

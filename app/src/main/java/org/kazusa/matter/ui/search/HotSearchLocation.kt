package org.kazusa.matter.ui.search

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.os.CancellationSignal
import androidx.core.content.ContextCompat
import androidx.core.location.LocationManagerCompat
import androidx.core.util.Consumer
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.Executors
import kotlin.coroutines.resume

private const val TAG = "WeiboApi"

/**
 * 热搜「同城」榜用的定位（只在用户主动切换到该榜时才取一次，不持续定位）。
 *
 * 服务端 `filter_type=region` 的榜单**必须带 `lat` / `lon` 参数**才会返回真实城市榜：
 * 不带经纬度时整张卡只有一条服务端文案「位置信息获取失败」；
 * 带上之后榜单名会自动变成城市名（如「北京」），并有 30+ 条同城条目。
 *
 * 取位置分两步：
 * 1. [lastKnownLatLon]：设备最近一次已知位置（瞬时、零开销）；
 * 2. 上面拿不到时（刚开机 / 系统没有缓存）用 `requestCurrentLocation` **主动取一次**当前位置 ——
 *    实测只靠 `getLastKnownLocation` 经常返回 null，导致同城榜退回那句「位置信息获取失败」。
 */
private val LOCATION_PROVIDERS = listOf(LocationManager.NETWORK_PROVIDER, LocationManager.GPS_PROVIDER)

private fun Context.locationManager(): LocationManager? =
    getSystemService(LocationManager::class.java)

fun hasCoarseLocationPermission(context: Context): Boolean =
    ContextCompat.checkSelfPermission(
        context,
        Manifest.permission.ACCESS_COARSE_LOCATION
    ) == PackageManager.PERMISSION_GRANTED

/** 设备最近一次已知位置；未授权 / 无缓存时返回 null。 */
fun lastKnownLatLon(context: Context): Pair<Double, Double>? {
    if (!hasCoarseLocationPermission(context)) return null
    val manager = context.locationManager() ?: return null
    for (provider in LOCATION_PROVIDERS) {
        val location = runCatching { manager.getLastKnownLocation(provider) }
            .onFailure { android.util.Log.w(TAG, "getLastKnownLocation($provider) failed", it) }
            .getOrNull()
        if (location != null) return location.latitude to location.longitude
    }
    return null
}

/**
 * 取一次当前位置；拿不到（未授权 / 定位关闭 / 超时）返回 null。
 *
 * 仅 Android 11（API 30）以上会主动请求一次定位，低版本只退回缓存位置。
 */
suspend fun currentLatLon(context: Context, timeoutMs: Long = 8_000L): Pair<Double, Double>? {
    if (!hasCoarseLocationPermission(context)) {
        android.util.Log.d(TAG, "hotSearch location: no COARSE_LOCATION permission")
        return null
    }

    lastKnownLatLon(context)?.let {
        android.util.Log.d(TAG, "hotSearch location: last known = $it")
        return it
    }

    val manager = context.locationManager() ?: return null
    val provider = LOCATION_PROVIDERS.firstOrNull { manager.isProviderEnabled(it) }
    if (provider == null) {
        android.util.Log.d(TAG, "hotSearch location: no enabled provider")
        return null
    }

    val executor = Executors.newSingleThreadExecutor()
    val location = withTimeoutOrNull(timeoutMs) {
        suspendCancellableCoroutine<Location?> { cont ->
            val signal = CancellationSignal()
            cont.invokeOnCancellation { signal.cancel() }
            val consumer = Consumer<Location> { result -> if (cont.isActive) cont.resume(result) }
            runCatching {
                // 现代定位 API：requestCurrentLocation 直接取当前位置
                LocationManagerCompat.getCurrentLocation(manager, provider, signal, executor, consumer)
            }.onFailure {
                android.util.Log.w(TAG, "requestCurrentLocation failed", it)
                if (cont.isActive) cont.resume(null)
            }
        }
    }
    executor.shutdown()

    val latLon = location?.let { it.latitude to it.longitude }
    android.util.Log.d(TAG, "hotSearch location: current = $latLon")
    return latLon
}

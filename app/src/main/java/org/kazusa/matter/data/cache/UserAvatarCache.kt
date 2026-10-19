package org.kazusa.matter.data.cache

import android.util.LruCache

/**
 * 博主头像地址与昵称内存缓存（uid -> 信息流里已经渲染过的头像 URL / 昵称）。
 *
 * 作用：进入博主主页时，首帧就能使用与信息流卡片完全一致的头像地址与昵称，
 * 共享元素动画期间不会先闪默认头像、也不会先显示「博主 123456」再换成真实昵称。
 */
object UserAvatarCache {
    private val cache = object : LruCache<String, String>(256) {}
    private val nameCache = object : LruCache<String, String>(256) {}

    /** 认证类型（WeiboVerifyType.ordinal），供主页首帧直接绘制角标并参与共享元素动画 */
    private val verifyCache = object : LruCache<String, Int>(256) {}

    fun put(uid: Long?, url: String?, screenName: String? = null) {
        put(uid?.toString(), url, screenName)
    }

    fun put(uid: String?, url: String?, screenName: String? = null) {
        val key = uid?.takeIf { it.isNotBlank() } ?: return
        url?.takeIf { it.isNotBlank() }?.let { cache.put(key, it) }
        screenName?.takeIf { it.isNotBlank() }?.let { nameCache.put(key, it) }
    }

    fun get(uid: Long?): String? = get(uid?.toString())

    fun get(uid: String?): String? {
        val key = uid?.takeIf { it.isNotBlank() } ?: return null
        return cache.get(key)
    }

    fun putVerify(uid: Long?, verifyTypeOrdinal: Int) = putVerify(uid?.toString(), verifyTypeOrdinal)

    fun putVerify(uid: String?, verifyTypeOrdinal: Int) {
        val key = uid?.takeIf { it.isNotBlank() } ?: return
        verifyCache.put(key, verifyTypeOrdinal)
    }

    fun getVerify(uid: Long?): Int? = getVerify(uid?.toString())

    fun getVerify(uid: String?): Int? {
        val key = uid?.takeIf { it.isNotBlank() } ?: return null
        return verifyCache.get(key)
    }

    /** 昵称兜底：主页首帧（接口未返回）时直接用卡片上已经显示过的名字 */
    fun getName(uid: Long?): String? = getName(uid?.toString())

    fun getName(uid: String?): String? {
        val key = uid?.takeIf { it.isNotBlank() } ?: return null
        return nameCache.get(key)
    }
}

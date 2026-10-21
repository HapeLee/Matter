package org.kazusa.matter.core.image

import coil3.ImageLoader
import coil3.PlatformContext
import coil3.gif.AnimatedImageDecoder
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import coil3.request.crossfade
import org.kazusa.matter.core.network.WeiboImageClient

/**
 * 全应用唯一的 Coil [ImageLoader] 配置入口。
 *
 * 集中在一处而不是散在调用点，是因为两件事实在「每个 loader 各自配置」时必然走偏：
 * 1. 微博图片 CDN 带防盗链，请求缺 `Referer` 或只带 OkHttp 默认 UA 都会 403
 *    （见 [WeiboImageClient]），任何一个 loader 漏配，用户看到的就是「有些图莫名加载失败」；
 * 2. 内存 / 磁盘缓存按 ImageLoader 实例隔离，配置不一致会出现同一张图在两个 loader 之间
 *    重复下载、共享元素飞行动画起始帧拿不到已解码位图。
 *
 * 使用方式：界面上不要自己 `ImageLoader.Builder(...)`。
 * 全局单例由 [org.kazusa.matter.MatterApplication] 通过 [create] 注册，
 * 之后 `Context.imageLoader` / `LocalPlatformContext.current.imageLoader` 取到的就是这一份；
 * 需要独立 loader 的场景（预览、测试、以后的 JVM Desktop 入口）也直接复用 [create]。
 */
object MatterImageLoader {

    /**
     * 按统一配置构建 ImageLoader。
     *
     * 动图解码直接用 [AnimatedImageDecoder]（要求 API 28+）：应用 minSdk 为 31，
     * 不再需要保留 [coil3.gif.GifDecoder] 退回分支。
     *
     * @param context Coil 的平台上下文；Android 上是 `Application` / `Activity`。
     */
    fun create(context: PlatformContext): ImageLoader {
        return ImageLoader.Builder(context)
            .components {
                // 图片请求统一走带 Referer / 浏览器 UA 且会对偶发 403 重试的 OkHttp 客户端
                add(OkHttpNetworkFetcherFactory(callFactory = { WeiboImageClient.client }))
                // 动图解码：系统 ImageDecoder 保留原有帧时序
                add(AnimatedImageDecoder.Factory())
            }
            .crossfade(true)
            .build()
    }
}

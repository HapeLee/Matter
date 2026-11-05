package org.kazusa.matter.core.util

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 实况照片落盘的命名与设备判定。
 *
 * 相册 / 厂商图库识别实况照片时会先看文件名（`MVIMG_` 前缀、`*MP.jpg` 结尾），
 * 命名不符时即便 XMP 元数据完整也可能只显示静态图。
 */
class ImageSaverNamingTest {

    @Test
    fun oplusDevicesAreDetected() {
        // OPPO / 一加 / realme 同属 Oplus，相册只认自家的 OpCamera + UserComment + MPF 组合
        assertTrue(ImageSaver.supportsOplusLivePhoto("OPPO", "OPPO"))
        assertTrue(ImageSaver.supportsOplusLivePhoto("OnePlus", "OnePlus"))
        assertTrue(ImageSaver.supportsOplusLivePhoto("realme", "realme"))
        assertTrue(ImageSaver.supportsOplusLivePhoto("OPPO", "PKH110"))
    }

    @Test
    fun otherVendorsKeepGoogleMotionPhoto() {
        assertFalse(ImageSaver.supportsOplusLivePhoto("Xiaomi", "Redmi"))
        assertFalse(ImageSaver.supportsOplusLivePhoto("HUAWEI", "HONOR"))
        assertFalse(ImageSaver.supportsOplusLivePhoto("vivo", "iQOO"))
        assertFalse(ImageSaver.supportsOplusLivePhoto("samsung", "SM-S9180"))
        assertFalse(ImageSaver.supportsOplusLivePhoto(null, null))
    }

    /** Motion Photo 1.0 规范给出的写入方文件名模式。 */
    private val specName = Regex("^([^\\s/\\\\][^/\\\\]*MP)\\.(JPG|jpg|JPEG|jpeg|HEIC|heic|AVIF|avif)$")

    @Test
    fun livePhotoFileNameSatisfiesSpecPattern() {
        val name = ImageSaver.livePhotoFileName("https://wx1.sinaimg.cn/large/007abcXYZ.jpg")
        assertTrue(name, specName.matches(name))
        assertTrue(name, name.startsWith("MVIMG_"))
        assertTrue(name, name.endsWith("_MP.jpg"))
    }

    @Test
    fun livePhotoFileNameKeepsUrlStemAndSilencesIllegalChars() {
        val name = ImageSaver.livePhotoFileName("http://g.us.sinaimg.cn/o0/a b#c/live?.png")
        assertTrue(name, specName.matches(name))
        assertTrue(name, name.contains("a_b"))
    }

    @Test
    fun livePhotoFileNameFallsBackWhenUrlHasNoUsableName() {
        val name = ImageSaver.livePhotoFileName("https://wx1.sinaimg.cn/large/")
        assertTrue(name, specName.matches(name))
        assertTrue(name, name.startsWith("MVIMG_"))
    }
}

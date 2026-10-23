package org.kazusa.matter.core.media

import java.io.ByteArrayOutputStream
import java.util.Base64
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.kazusa.matter.data.model.*

class AndroidEmojiPackageStoreTest {
    @get:Rule val folder = TemporaryFolder()
    private val pack = OnlineEmojiPackage("com.sina.test", "1", "https://face.t.sinajs.cn/emoji/package/test.zip")
    private val pixel = Base64.getDecoder().decode("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+a/MsAAAAASUVORK5CYII=")

    private fun zip(vararg entries: Pair<String, ByteArray>): ByteArray {
        val output = ByteArrayOutputStream()
        ZipOutputStream(output).use { stream -> entries.forEach { (name, bytes) ->
            stream.putNextEntry(ZipEntry(name)); stream.write(bytes); stream.closeEntry()
        } }
        return output.toByteArray()
    }

    private fun metadata(version: Int = 1) = """{"id":"com.sina.test","version":$version,"group_name_cn":"测试包","emoticons":[
        {"chs":"[测试]","cht":"[測試]","en":"[test]","png":"face.png","type":0}
    ]}""".toByteArray()

    @Test
    fun onlineCatalogSurvivesStoreRecreationWithoutAffectingZipPackages() = runBlocking {
        val root = folder.newFolder("catalog")
        val groups = listOf(InstalledEmojiPackage("catalog.default", "1", "微博表情",
            listOf(OnlineEmoji("[微笑]", "https://face.t.sinajs.cn/smile.png"))))
        AndroidEmojiPackageStore(root).saveCatalog(groups)
        val recreated = AndroidEmojiPackageStore(root)
        assertEquals(groups, recreated.restoreCatalog())
        assertTrue(recreated.restore().isEmpty())
    }

    @Test
    fun completePackPersistsAndRestoresItsImagesAndAliases() = runBlocking {
        val store = AndroidEmojiPackageStore(folder.newFolder("emoji"))
        val installed = store.install(pack, zip("info.json" to metadata(), "face.png" to pixel))
        assertEquals("测试包", installed.title)
        assertEquals(listOf("[測試]", "[test]"), installed.emojis.single().aliases)
        assertEquals(listOf(installed), store.restore())
    }

    @Test
    fun malformedUpdateKeepsPreviousInstalledPack() = runBlocking {
        val store = AndroidEmojiPackageStore(folder.newFolder("emoji"))
        val installed = store.install(pack, zip("info.json" to metadata(), "face.png" to pixel))
        try {
            store.install(pack.copy(version = "2"), zip("info.json" to metadata(2), "face.png" to "not an image".toByteArray()))
            fail("Must reject invalid image")
        } catch (_: IllegalArgumentException) { }
        assertEquals(listOf(installed), store.restore())
    }

    @Test
    fun traversalZipIsRejectedBeforeWritingOutsideThePack() = runBlocking {
        val root = folder.newFolder("emoji")
        val store = AndroidEmojiPackageStore(root)
        try {
            store.install(pack, zip("../escape.png" to pixel, "info.json" to metadata()))
            fail("Must reject traversal")
        } catch (_: IllegalArgumentException) { }
        assertFalse(java.io.File(root.parentFile, "escape.png").exists())
        assertTrue(store.restore().isEmpty())
    }
}

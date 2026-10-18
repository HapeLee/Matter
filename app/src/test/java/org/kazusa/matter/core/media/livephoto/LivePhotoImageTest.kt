package org.kazusa.matter.core.media.livephoto

import android.app.Application
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class LivePhotoImageTest {
    @get:Rule val tempFolder = TemporaryFolder()

    @Test fun pngIsActuallyConvertedToJpeg() {
        val image = tempFolder.newFile("cover.png")
        val bitmap = Bitmap.createBitmap(8, 6, Bitmap.Config.ARGB_8888)
        image.outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        bitmap.recycle()
        LivePhotoImage.normalizeJpeg(image)
        val bytes = image.readBytes()
        assertEquals(0xff, bytes[0].toInt() and 0xff)
        assertEquals(0xd8, bytes[1].toInt() and 0xff)
        val decoded = BitmapFactory.decodeFile(image.absolutePath)
        assertEquals(8, decoded.width)
        assertEquals(6, decoded.height)
        decoded.recycle()
    }

    @Test fun jpegIsPreservedByteForByte() {
        val image = tempFolder.newFile("cover.jpg")
        val bitmap = Bitmap.createBitmap(8, 6, Bitmap.Config.ARGB_8888)
        image.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 95, it) }
        bitmap.recycle()
        val original = image.readBytes()
        LivePhotoImage.normalizeJpeg(image)
        assertArrayEquals(original, image.readBytes())
    }

    @Test
    @Config(sdk = [26], application = Application::class)
    @GraphicsMode(GraphicsMode.Mode.LEGACY)
    fun pngConversionAlsoWorksOnMinSdk() {
        pngIsActuallyConvertedToJpeg()
    }

    @Test fun generatedXmpIsReadableByAndroidXmlParser() {
        val bitmap = Bitmap.createBitmap(8, 6, Bitmap.Config.ARGB_8888)
        val jpeg = java.io.ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.JPEG, 95, it) }
        bitmap.recycle()
        val output = java.io.ByteArrayOutputStream()
        val clip = byteArrayOf(1, 2, 3)
        MotionPhotoComposer.compose(jpeg.toByteArray().inputStream(), clip.inputStream(), clip.size.toLong(), output)
        val xmp = JpegContainer.read(output.toByteArray().inputStream())!!.xmpSegments.single().xml
        val document = javax.xml.parsers.DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
            .newDocumentBuilder().parse(xmp.inputStream())
        assertEquals(1, document.getElementsByTagNameNS("http://ns.google.com/photos/1.0/container/", "Directory").length)
    }

    @Test fun invalidImageFailsWithoutOverwritingInput() {
        val image = tempFolder.newFile()
        val original = "not an image".toByteArray()
        image.writeBytes(original)
        assertThrows(Exception::class.java) { LivePhotoImage.normalizeJpeg(image) }
        assertArrayEquals(original, image.readBytes())
    }
}

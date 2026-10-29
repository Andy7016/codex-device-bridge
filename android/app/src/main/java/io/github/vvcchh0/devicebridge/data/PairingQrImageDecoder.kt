package io.github.vvcchh0.devicebridge.data

import android.content.ContentResolver
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.MultiFormatReader
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.HybridBinarizer
import java.io.ByteArrayOutputStream

/** Bounded image-to-QR recovery path. Camera scanning is provided by JourneyApps' embedded CaptureActivity. */
object PairingQrImageDecoder {
    private const val MAX_IMAGE_BYTES = 8 * 1024 * 1024
    private const val MAX_IMAGE_DIMENSION = 12_000
    private const val MAX_DECODE_SIDE = 2048
    private const val MAX_DECODE_PIXELS = MAX_DECODE_SIDE * MAX_DECODE_SIDE

    fun decode(contentResolver: ContentResolver, uri: Uri): String {
        val encoded = readBounded(contentResolver, uri)
        var bitmap: Bitmap? = null
        var pixels: IntArray? = null
        try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(encoded, 0, encoded.size, bounds)
            if (bounds.outWidth !in 1..MAX_IMAGE_DIMENSION || bounds.outHeight !in 1..MAX_IMAGE_DIMENSION) {
                throw PairingException("qr_image_invalid")
            }
            val sample = sampleSize(bounds.outWidth, bounds.outHeight)
            val options = BitmapFactory.Options().apply {
                inJustDecodeBounds = false
                inSampleSize = sample
                inPreferredConfig = Bitmap.Config.ARGB_8888
            }
            val decodedBitmap = BitmapFactory.decodeByteArray(encoded, 0, encoded.size, options)
                ?: throw PairingException("qr_image_invalid")
            bitmap = decodedBitmap
            val width = decodedBitmap.width
            val height = decodedBitmap.height
            if (width <= 0 || height <= 0 || width.toLong() * height > MAX_DECODE_PIXELS) {
                throw PairingException("qr_image_invalid")
            }
            val pixelBuffer = IntArray(width * height)
            pixels = pixelBuffer
            decodedBitmap.getPixels(pixelBuffer, 0, width, 0, 0, width, height)
            val hints = mapOf(
                DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE),
                DecodeHintType.TRY_HARDER to true,
            )
            return try {
                val binary = BinaryBitmap(HybridBinarizer(RGBLuminanceSource(width, height, pixelBuffer)))
                val reader = MultiFormatReader().apply { setHints(hints) }
                val result = try { reader.decodeWithState(binary) } catch (_: com.google.zxing.ReaderException) {
                    // Computer-generated images can be very dense. A pure
                    // symbol pass avoids perspective detection in that case.
                    MultiFormatReader().decode(binary, hints + (DecodeHintType.PURE_BARCODE to true))
                }
                result
                    .text
                    .takeIf { it.length <= 11_000 }
                    ?: throw PairingException("qr_image_invalid")
            } catch (e: PairingException) {
                throw e
            } catch (_: Exception) {
                throw PairingException("qr_image_unreadable")
            }
        } finally {
            encoded.fill(0)
            pixels?.fill(0)
            bitmap?.recycle()
        }
    }

    private fun readBounded(contentResolver: ContentResolver, uri: Uri): ByteArray {
        val stream = contentResolver.openInputStream(uri) ?: throw PairingException("qr_image_unavailable")
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        try {
            stream.use { input ->
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    if (output.size() + count > MAX_IMAGE_BYTES) throw PairingException("qr_image_too_large")
                    output.write(buffer, 0, count)
                }
            }
            return output.toByteArray()
        } catch (e: PairingException) {
            throw e
        } catch (_: Exception) {
            throw PairingException("qr_image_unavailable")
        } finally {
            buffer.fill(0)
        }
    }

    private fun sampleSize(width: Int, height: Int): Int {
        var sample = 1
        while (width / sample > MAX_DECODE_SIDE || height / sample > MAX_DECODE_SIDE ||
            width.toLong() * height / (sample.toLong() * sample) > MAX_DECODE_PIXELS
        ) {
            sample *= 2
        }
        return sample
    }
}

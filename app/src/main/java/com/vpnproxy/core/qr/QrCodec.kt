package com.vpnproxy.core.qr

import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.EncodeHintType
import com.google.zxing.MultiFormatReader
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel

/**
 * QR encode/decode with no Android types, so both directions are unit tested on the JVM.
 *
 * Payloads are computed in ARGB pixels; the UI layer converts them to a `Bitmap`. Decoding
 * accepts pixels of any size (the caller downscales a gallery image) and returns null instead of
 * throwing when nothing readable is found.
 */
object QrCodec {
    /** Byte-mode QR caps out near 2900 characters; stay well below the densest version. */
    const val MAX_PAYLOAD_CHARS = 1500

    const val MIN_SIZE_PX = 160
    private const val BLACK = 0xFF000000.toInt()
    private const val WHITE = 0xFFFFFFFF.toInt()

    class QrImage(val size: Int, val argb: IntArray)

    fun encode(text: String, sizePx: Int = 640): QrImage? {
        if (text.isBlank() || text.length > MAX_PAYLOAD_CHARS) return null
        val size = sizePx.coerceAtLeast(MIN_SIZE_PX)
        val hints = mapOf(
            EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M,
            EncodeHintType.MARGIN to 1,
            EncodeHintType.CHARACTER_SET to "UTF-8"
        )
        val matrix = runCatching {
            QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, size, size, hints)
        }.getOrNull() ?: return null

        val argb = IntArray(size * size)
        for (y in 0 until size) {
            val rowOffset = y * size
            for (x in 0 until size) {
                argb[rowOffset + x] = if (matrix[x, y]) BLACK else WHITE
            }
        }
        return QrImage(size, argb)
    }

    fun decode(argb: IntArray, width: Int, height: Int): String? {
        if (width <= 0 || height <= 0 || argb.size < width * height) return null
        val source = RGBLuminanceSource(width, height, argb)
        val bitmap = BinaryBitmap(HybridBinarizer(source))
        val hints = mapOf(
            DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE),
            DecodeHintType.TRY_HARDER to true,
            DecodeHintType.CHARACTER_SET to "UTF-8"
        )
        return runCatching {
            MultiFormatReader().apply { setHints(hints) }.decode(bitmap).text
        }.getOrNull()
    }
}

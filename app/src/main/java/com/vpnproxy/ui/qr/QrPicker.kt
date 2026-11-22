package com.vpnproxy.ui.qr

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import com.vpnproxy.core.qr.QrCodec

/**
 * Android glue for [QrCodec]: turns computed pixels into a `Bitmap` and reads a QR code out of an
 * image the user picked from the gallery. No camera permission is needed.
 */
object QrPicker {
    /** Large gallery photos are downsampled before decoding; QR decoding does not need full size. */
    private const val MAX_DECODE_DIMENSION = 1600

    fun toBitmap(image: QrCodec.QrImage): Bitmap =
        Bitmap.createBitmap(image.argb, image.size, image.size, Bitmap.Config.ARGB_8888)

    fun decodeImage(context: Context, uri: Uri): String? {
        val bitmap = decodeSampled(context, uri) ?: return null
        return try {
            val pixels = IntArray(bitmap.width * bitmap.height)
            bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
            QrCodec.decode(pixels, bitmap.width, bitmap.height)
        } finally {
            bitmap.recycle()
        }
    }

    private fun decodeSampled(context: Context, uri: Uri): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, bounds)
        } ?: return null
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

        var sampleSize = 1
        val longestSide = maxOf(bounds.outWidth, bounds.outHeight)
        while (longestSide / (sampleSize * 2) >= MAX_DECODE_DIMENSION) sampleSize *= 2

        val options = BitmapFactory.Options().apply { inSampleSize = sampleSize }
        return context.contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, options)
        }
    }
}

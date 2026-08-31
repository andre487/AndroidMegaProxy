package net.megaproxy487.model

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint

object ProfileColorMatcher {
    fun colorIndexForFlag(countryCode: String, fallback: Int): Int {
        val profile = ProxyProfile(id = "color-sample", colorIndex = fallback, countryCode = countryCode)
        val flag = profile.flagEmoji
        if (flag.isEmpty()) return fallback

        val bitmap = Bitmap.createBitmap(BITMAP_SIZE, BITMAP_SIZE, Bitmap.Config.ARGB_8888)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = TEXT_SIZE
            textAlign = Paint.Align.CENTER
        }
        Canvas(bitmap).drawText(flag, BITMAP_SIZE / 2f, BASELINE, paint)

        val votes = IntArray(ProfileColors.argb.size)
        val pixels = IntArray(BITMAP_SIZE * BITMAP_SIZE)
        bitmap.getPixels(pixels, 0, BITMAP_SIZE, 0, 0, BITMAP_SIZE, BITMAP_SIZE)
        bitmap.recycle()
        pixels.forEach { pixel ->
            if (Color.alpha(pixel) < 96) return@forEach
            val red = Color.red(pixel)
            val green = Color.green(pixel)
            val blue = Color.blue(pixel)
            val max = maxOf(red, green, blue)
            val min = minOf(red, green, blue)
            if (max > 242 && min > 242) return@forEach
            if (max - min < 24) return@forEach
            val nearest = ProfileColors.argb.indices.minByOrNull { index ->
                val candidate = ProfileColors.argb[index].toInt()
                squared(red - Color.red(candidate)) +
                    squared(green - Color.green(candidate)) +
                    squared(blue - Color.blue(candidate))
            } ?: return@forEach
            votes[nearest]++
        }
        return votes.indices.maxByOrNull { votes[it] }?.takeIf { votes[it] > 0 } ?: fallback
    }

    private fun squared(value: Int) = value * value

    private const val BITMAP_SIZE = 96
    private const val TEXT_SIZE = 72f
    private const val BASELINE = 72f
}

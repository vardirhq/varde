package no.vardir.varde

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import java.util.Random
import kotlin.math.roundToInt

// A compact native adaptation of Startpage's seeded, dithered sky and mountain layers
// (vardirhq/startpage, js/scene.js). Deliberately static: live weather is not included.
internal fun landscapePixels(seed: Long, night: Boolean, width: Int = 240, height: Int = 90): IntArray {
    require(width > 0 && height > 0)
    val random = Random(seed)
    val pixels = IntArray(width * height)
    val bayer = intArrayOf(0, 8, 2, 10, 12, 4, 14, 6, 3, 11, 1, 9, 15, 7, 13, 5)
    fun rgb(r: Int, g: Int, b: Int) = (0xff shl 24) or (r shl 16) or (g shl 8) or b
    val top = if (night) intArrayOf(15, 19, 41) else intArrayOf(36, 40, 64)
    val bottom = if (night) intArrayOf(99, 57, 74) else intArrayOf(174, 106, 83)
    for (y in 0 until height) for (x in 0 until width) {
        val t = ((y.toFloat() / height * 8 + bayer[(y % 4) * 4 + x % 4] / 16f).toInt().coerceIn(0, 8)) / 8f
        pixels[y * width + x] = rgb((top[0] + (bottom[0] - top[0]) * t).toInt(),
            (top[1] + (bottom[1] - top[1]) * t).toInt(), (top[2] + (bottom[2] - top[2]) * t).toInt())
    }
    if (night) repeat(width / 3) {
        val x = random.nextInt(width); val y = random.nextInt((height / 2).coerceAtLeast(1))
        pixels[y * width + x] = rgb(180, 164, 155)
    }
    fun ridge(base: Float, variation: Float, color: Int) {
        var current = base
        var target = base
        for (x in 0 until width) {
            if (x % 16 == 0) target = base + (random.nextFloat() - .5f) * variation
            current += (target - current) * .13f
            val peak = (height * current).roundToInt().coerceIn(0, height)
            for (y in peak until height) pixels[y * width + x] = color
        }
    }
    ridge(.62f, .35f, rgb(52, 46, 66))
    ridge(.75f, .30f, rgb(30, 33, 46))
    ridge(.88f, .24f, rgb(17, 24, 33))
    return pixels
}

@Composable
internal fun LandscapeBanner(seed: Long, night: Boolean, modifier: Modifier = Modifier) {
    val bitmap = remember(seed, night) {
        Bitmap.createBitmap(landscapePixels(seed, night), 240, 90, Bitmap.Config.ARGB_8888).asImageBitmap()
    }
    Box(modifier) {
        Image(bitmap, contentDescription = null, modifier = Modifier.matchParentSize(), contentScale = ContentScale.Crop,
            filterQuality = FilterQuality.None)
        Box(Modifier.matchParentSize().background(Brush.verticalGradient(listOf(Color(0x990C111A), Color(0x440C111A), Color(0xFF101720)))))
    }
}

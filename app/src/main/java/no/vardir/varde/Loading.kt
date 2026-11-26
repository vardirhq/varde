package no.vardir.varde

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.*
import androidx.compose.animation.fadeIn
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

// The stones of the Varde mark (res/drawable/ic_varde_mark.xml) in its 108-unit viewport, in building order:
// bottom course, middle course, slab, top stone. Keep in sync with the icon.
private val MarkStones = listOf(
    floatArrayOf(29f, 82.8f, 30.3f, 71.8f, 33f, 69.6f, 47f, 72.4f, 49.7f, 74.6f, 51f, 82.8f, 48.8f, 85f, 31.2f, 85f),
    floatArrayOf(55f, 82.8f, 56.3f, 73.4f, 59f, 71.2f, 75f, 68.8f, 77.7f, 71f, 79f, 82.8f, 76.8f, 85f, 57.2f, 85f),
    floatArrayOf(33f, 64.8f, 34.3f, 59.4f, 37f, 57.2f, 56f, 54.8f, 58.7f, 57f, 60f, 64.8f, 57.8f, 67f, 35.2f, 67f),
    floatArrayOf(64f, 64.8f, 65.3f, 58.2f, 68f, 56f, 72f, 58f, 74.7f, 60.2f, 76f, 64.8f, 73.8f, 67f, 66.2f, 67f),
    floatArrayOf(36f, 49.8f, 37.3f, 45f, 40f, 42.8f, 68f, 45.2f, 70.7f, 47.4f, 72f, 49.8f, 69.8f, 52f, 38.2f, 52f),
    floatArrayOf(42f, 37.8f, 43.3f, 36.2f, 46f, 34f, 60f, 32f, 62.7f, 34.2f, 64f, 37.8f, 61.8f, 40f, 44.2f, 40f),
)
private val MarkCap = floatArrayOf(53f, 17f, 58.5f, 23f, 53f, 29f, 47.5f, 23f)
/** The part of the viewport the mark occupies, with room above for stones to drop from. */
private const val ViewLeft = 16f
private const val ViewTop = 12f
private const val ViewSize = 76f
private const val GroundY = 86.5f

private const val CycleMs = 2200
/** Each piece drops in its own slice of the cycle; the finished cairn holds, then fades before the next round. */
private const val DropShare = 0.075f
private const val FadeAt = 0.82f

private fun ease(t: Float) = FastOutSlowInEasing.transform(t.coerceIn(0f, 1f))

/**
 * Varde's loading mark: the cairn from the app icon, built stone by stone and crowned with the amber capstone.
 * Shown centered while a screen's content loads. It appears after a short delay so fast or cached loads don't flash it.
 */
@Composable
internal fun CairnLoader(label: String, modifier: Modifier = Modifier) {
    var visible by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { delay(150); visible = true }
    AnimatedVisibility(visible, modifier, enter = fadeIn(tween(250))) {
        Column(Modifier.semantics(mergeDescendants = true) { contentDescription = label; liveRegion = LiveRegionMode.Polite },
            horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Cairn(Modifier.size(80.dp))
            Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun Cairn(modifier: Modifier) {
    val progress by rememberInfiniteTransition(label = "cairn").animateFloat(0f, 1f,
        infiniteRepeatable(tween(CycleMs, easing = LinearEasing)), label = "cycle")
    CairnFrame(progress, modifier)
}

/** One frame of the cairn animation at [progress] (0 to 1). Separate so frames can be rendered in screenshot checks. */
@Composable
internal fun CairnFrame(progress: Float, modifier: Modifier) {
    val stone = MaterialTheme.colorScheme.onSurfaceVariant
    val ground = MaterialTheme.colorScheme.outlineVariant
    val cap = MaterialTheme.colorScheme.primary
    Canvas(modifier) {
        val scale = minOf(size.width, size.height) / ViewSize
        val fade = 1f - ease((progress - FadeAt) / (1f - FadeAt))
        val pieces = MarkStones.size + 1
        val built = (0 until pieces).count { progress >= (it + 1) * DropShare }
        // Ground shadow grows as the cairn does.
        val shadowWidth = (34f + 14f * built / pieces) * scale
        drawRoundRect(ground.copy(alpha = ground.alpha * fade), Offset(size.width / 2 - shadowWidth / 2, (GroundY - ViewTop) * scale),
            Size(shadowWidth, 2.2f * scale), CornerRadius(2f * scale))
        (0 until pieces).forEach { index ->
            val start = index * DropShare
            if (progress < start) return@forEach
            val landed = ease((progress - start) / DropShare)
            val drop = (1f - landed) * 22f * scale
            val isCap = index == MarkStones.size
            drawPiece(if (isCap) MarkCap else MarkStones[index], scale, drop,
                (if (isCap) cap else stone).let { it.copy(alpha = it.alpha * landed * fade) })
        }
    }
}

private fun DrawScope.drawPiece(points: FloatArray, scale: Float, drop: Float, color: Color) {
    val path = Path()
    for (i in points.indices step 2) {
        val x = (points[i] - ViewLeft) * scale
        val y = (points[i + 1] - ViewTop) * scale - drop
        if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
    }
    path.close()
    drawPath(path, color)
}

/** Fills the available space and centers [CairnLoader] in it. */
@Composable
internal fun CenteredLoader(label: String, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxSize().padding(bottom = 48.dp), contentAlignment = Alignment.Center) { CairnLoader(label) }
}

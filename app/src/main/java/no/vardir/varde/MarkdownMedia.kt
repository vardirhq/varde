package no.vardir.varde

import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.commonmark.node.*

internal data class MarkdownImageRef(val src: String, val alt: String)

private val ImgTag = Regex("""(?is)<img\b[^>]*>""")
private fun attribute(tag: String, name: String): String? =
    Regex("""(?is)\b$name\s*=\s*(?:"([^"]*)"|'([^']*)'|([^\s>]+))""").find(tag)?.groupValues?.drop(1)?.firstOrNull { it.isNotEmpty() }

/** `<img>` tags in an HTML fragment, in order. */
internal fun htmlImages(html: String): List<MarkdownImageRef> = ImgTag.findAll(html).mapNotNull { m ->
    attribute(m.value, "src")?.let { MarkdownImageRef(it, attribute(m.value, "alt").orEmpty()) }
}.toList()

/** Visible text of an HTML fragment: comments and tags removed, `<br>` and block ends become line breaks, entities decoded. */
internal fun stripHtml(html: String): String = html
    .replace(Regex("""(?s)<!--.*?-->"""), "")
    .replace(Regex("""(?i)<br\s*/?>|</(p|div|h[1-6]|li|tr|summary)>"""), "\n")
    .replace(Regex("""(?s)<[^>]+>"""), "")
    .replace("&nbsp;", " ").replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&#39;", "'").replace("&amp;", "&")
    .lines().map { it.trim() }.joinToString("\n").replace(Regex("\n{3,}"), "\n\n").trim()

/** The repository path an image URL points to, if it's a file in [owner]/[repo] on GitHub. */
internal fun repoImagePath(url: String, owner: String, repo: String): String? {
    val uri = runCatching { java.net.URI(url) }.getOrNull() ?: return null
    val parts = (uri.path ?: return null).trim('/').split('/')
    fun same(o: String, r: String) = o.equals(owner, true) && r.equals(repo, true)
    return when (uri.host) {
        // https://github.com/o/r/blob/<ref>/path or .../raw/<ref>/path
        "github.com" -> if (parts.size >= 5 && same(parts[0], parts[1]) && parts[2] in setOf("blob", "raw")) parts.drop(4).joinToString("/") else null
        // https://raw.githubusercontent.com/o/r/<ref>/path
        "raw.githubusercontent.com" -> if (parts.size >= 4 && same(parts[0], parts[1])) parts.drop(3).joinToString("/") else null
        else -> null
    }?.takeIf { it.isNotBlank() }?.let { java.net.URLDecoder.decode(it, "UTF-8") }
}

internal fun isRasterImage(path: String) = path.substringAfterLast('.', "").lowercase() in setOf("png", "jpg", "jpeg", "gif", "webp", "bmp")

/**
 * Images when a paragraph holds nothing but images (optionally wrapped in links, as badges are), else null so the
 * paragraph renders as text. Images inside running text keep showing their alt text.
 */
internal fun paragraphImages(paragraph: Node): List<MarkdownImageRef>? {
    val images = mutableListOf<MarkdownImageRef>()
    fun altOf(node: Node): String { val sb = StringBuilder(); var c = node.firstChild; while (c != null) { if (c is Text) sb.append(c.literal); c = c.next }; return sb.toString() }
    fun walk(node: Node, insideLink: Boolean): Boolean {
        var child = node.firstChild
        while (child != null) {
            when (child) {
                is Image -> images += MarkdownImageRef(child.destination, altOf(child))
                is Link -> if (insideLink || !walk(child, true)) return false
                is SoftLineBreak, is HardLineBreak -> Unit
                is Text -> if (child.literal.isNotBlank()) return false
                is HtmlInline -> { val found = htmlImages(child.literal); if (found.isEmpty() && stripHtml(child.literal).isNotBlank()) return false; images += found }
                else -> return false
            }
            child = child.next
        }
        return true
    }
    return if (walk(paragraph, false) && images.isNotEmpty()) images else null
}

/**
 * Loads images that live in the repository through the GitHub API (so private repositories work), decoded at screen
 * size and kept in memory only. Anything else is not fetched: READMEs shouldn't make Varde contact arbitrary servers.
 */
internal class RepoImageLoader(private val github: GitHubApi, private val repo: Repo) {
    private val cache = object : LruCache<String, ImageBitmap>(24 * 1024 * 1024) {
        override fun sizeOf(key: String, value: ImageBitmap) = value.width * value.height * 4
    }

    fun canLoad(url: String) = repoImagePath(url, repo.owner, repo.name)?.let(::isRasterImage) == true

    suspend fun load(url: String): ImageBitmap? {
        cache.get(url)?.let { return it }
        val path = repoImagePath(url, repo.owner, repo.name)?.takeIf(::isRasterImage) ?: return null
        val bytes = github.rawFile(repo, path, maxBytes = 10L * 1024 * 1024)
        val bitmap = withContext(Dispatchers.Default) {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            var sample = 1
            while (maxOf(bounds.outWidth, bounds.outHeight) / sample > 1600) sample *= 2
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })?.asImageBitmap()
        } ?: return null
        cache.put(url, bitmap)
        return bitmap
    }
}

internal val LocalRepoImages = staticCompositionLocalOf<RepoImageLoader?> { null }

/** One image fills the width; several (like a row of screenshots) become a horizontal strip. */
@Composable
internal fun MarkdownImages(images: List<MarkdownImageRef>, linkBase: String?) {
    val resolved = images.mapNotNull { img -> resolveLink(img.src, linkBase)?.let { it to img.alt } }
    if (resolved.isEmpty()) return
    if (resolved.size == 1) MarkdownImage(resolved[0].first, resolved[0].second, Modifier.fillMaxWidth(), maxHeight = 420)
    else Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        resolved.forEach { (url, alt) -> MarkdownImage(url, alt, Modifier.height(360.dp), maxHeight = 360) }
    }
}

@Composable
private fun MarkdownImage(url: String, alt: String, modifier: Modifier, maxHeight: Int) {
    val loader = LocalRepoImages.current
    val uri = LocalUriHandler.current
    var bitmap by remember(url) { mutableStateOf<ImageBitmap?>(null) }
    var failed by remember(url) { mutableStateOf(loader?.canLoad(url) != true) }
    LaunchedEffect(url) {
        if (failed || loader == null) return@LaunchedEffect
        bitmap = try { loader.load(url) } catch (e: CancellationException) { throw e } catch (_: Exception) { null }
        if (bitmap == null) failed = true
    }
    val image = bitmap
    when {
        image != null -> Image(image, contentDescription = alt.ifBlank { null },
            modifier = modifier.heightIn(max = maxHeight.dp).aspectRatio(image.width.toFloat() / image.height.coerceAtLeast(1), matchHeightConstraintsFirst = true)
                .clip(RoundedCornerShape(8.dp)), contentScale = ContentScale.Fit)
        failed -> AssistChip(onClick = { runCatching { uri.openUri(url) } },
            label = { Text(alt.ifBlank { "Image" }, maxLines = 1, overflow = TextOverflow.Ellipsis) },
            leadingIcon = { Text("↗", style = MaterialTheme.typography.labelMedium) })
        else -> Box(modifier.heightIn(min = 120.dp, max = maxHeight.dp).widthIn(min = 160.dp)
            .background(MaterialTheme.colorScheme.surfaceContainerHigh, RoundedCornerShape(8.dp)), contentAlignment = Alignment.Center) {
            Text(alt.ifBlank { "Loading image…" }, Modifier.padding(12.dp), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

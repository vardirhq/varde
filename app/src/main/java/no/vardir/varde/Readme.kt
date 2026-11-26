package no.vardir.varde

import android.util.Base64
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import org.json.JSONObject

/** A repository's README from GET /repos/{owner}/{repo}/readme. [htmlUrl] is the base for its relative links. */
internal data class Readme(val path: String, val text: String, val htmlUrl: String) {
    val markdown get() = path.endsWith(".md", true) || path.endsWith(".markdown", true) || !path.contains('.')
}

internal fun parseReadme(body: String, decode: (String) -> ByteArray = { Base64.decode(it, Base64.DEFAULT) }): Readme? = runCatching {
    val o = JSONObject(body)
    val bytes = decode(jsonText(o, "content").replace("\n", ""))
    val text = String(bytes, Charsets.UTF_8)
    if (text.contains('\u0000')) return null
    Readme(jsonText(o, "path", "README"), text.take(200_000), jsonText(o, "html_url"))
}.getOrNull()

/**
 * The start of a README for the overview: whole paragraphs up to about [maxChars], never cut inside a code block.
 * Returns the preview and whether anything was left out.
 */
internal fun readmePreview(text: String, maxChars: Int = 1200): Pair<String, Boolean> {
    if (text.length <= maxChars) return text.trim() to false
    val blocks = text.split(Regex("\n\\s*\n"))
    val out = StringBuilder()
    var fenceOpen = false
    for (block in blocks) {
        if (out.isNotEmpty() && out.length + block.length > maxChars && !fenceOpen) break
        if (out.isNotEmpty()) out.append("\n\n")
        out.append(block)
        if (Regex("```").findAll(block).count() % 2 == 1) fenceOpen = !fenceOpen
        if (out.length > maxChars * 2) break
    }
    if (fenceOpen) out.append("\n```")
    return out.toString().trim() to true
}

@Composable
internal fun ReadmeContent(readme: Readme) {
    if (readme.markdown) WorkspaceMarkdown(readme.text, linkBase = readme.htmlUrl.ifBlank { null })
    else Text(readme.text, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
}

/** README preview at the end of the repository overview, like GitHub's repository page. */
@Composable
internal fun ReadmePreviewCard(readme: Readme, onOpen: () -> Unit) {
    val (preview, more) = readmePreview(readme.text)
    VardeCard {
        Text(readme.path, Modifier.semantics { heading() }, style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold)
        ReadmeContent(readme.copy(text = preview))
        if (more) TextButton(onClick = onOpen, modifier = Modifier.padding(top = 4.dp)) { Text("Read full README") }
    }
}

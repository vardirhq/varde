package no.vardir.varde

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import org.json.JSONArray
import org.json.JSONObject

private val DocExtensions = setOf("md", "markdown", "mdx", "rst", "adoc")
private val SkippedFolders = setOf("node_modules", "vendor", "third_party", "third-party", "build", "dist", "out", "target", "Pods", ".git")
/** Root files an agent or newcomer should read first, in this order. */
private val KeyDocs = listOf("README", "AGENTS", "CLAUDE", "CONTRIBUTING", "ARCHITECTURE", "CHANGELOG", "SECURITY", "CODE_OF_CONDUCT")
private val DocFolders = setOf("docs", "doc", "documentation", "guides", "wiki")

internal data class DocEntry(val path: String) {
    val name get() = path.substringAfterLast('/')
    val folder get() = path.substringBeforeLast('/', "")
}

internal data class DocIndex(val docs: List<DocEntry>, val truncated: Boolean)

internal fun isDocPath(path: String): Boolean {
    val parts = path.split('/')
    if (parts.dropLast(1).any { it in SkippedFolders }) return false
    return parts.last().substringAfterLast('.', "").lowercase() in DocExtensions
}

private fun docRank(doc: DocEntry): Int {
    val stem = doc.name.substringBeforeLast('.').uppercase()
    return when {
        doc.folder.isEmpty() && stem in KeyDocs -> KeyDocs.indexOf(stem)
        doc.folder.isEmpty() -> 20
        doc.folder.split('/').first().lowercase() in DocFolders -> 30
        doc.folder.startsWith(".github") -> 50
        else -> 40
    }
}

/** Docs from a recursive git tree: key root docs first, then other root docs, the docs folder, other folders, and .github. */
internal fun parseDocIndex(body: String, limit: Int = 300): DocIndex {
    val root = JSONObject(body)
    val tree = root.optJSONArray("tree") ?: JSONArray()
    val docs = (0 until tree.length()).mapNotNull { tree.optJSONObject(it) }
        .filter { it.optString("type") == "blob" && isDocPath(it.optString("path")) }
        .map { DocEntry(it.optString("path")) }
        .sortedWith(compareBy<DocEntry> { docRank(it) }.thenBy { it.folder.lowercase() }.thenBy { it.name.lowercase() })
    return DocIndex(docs.take(limit), root.optBoolean("truncated") || docs.size > limit)
}

/** The repository path of a github.com blob URL in [owner]/[repo], e.g. ".../blob/main/docs/A.md" -> "docs/A.md". */
internal fun repoBlobPath(url: String, owner: String, repo: String): String? {
    val uri = runCatching { java.net.URI(url) }.getOrNull() ?: return null
    if (uri.host != "github.com") return null
    val parts = (uri.path ?: return null).trim('/').split('/')
    if (parts.size < 5 || !parts[0].equals(owner, true) || !parts[1].equals(repo, true) || parts[2] != "blob") return null
    return parts.drop(4).joinToString("/").takeIf { it.isNotBlank() }
}

/** Document list with a filter, grouped by folder. */
@Composable
internal fun DocsList(index: DocIndex, onOpen: (DocEntry) -> Unit) {
    var query by rememberSaveable { mutableStateOf("") }
    val shown = index.docs.filter { it.path.contains(query.trim(), ignoreCase = true) }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (index.docs.size > 8) OutlinedTextField(query, { query = it }, Modifier.fillMaxWidth(), singleLine = true,
            placeholder = { Text("Filter ${index.docs.size} documents") }, leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) })
        if (index.docs.isEmpty()) Notice("No docs found", "This repository has no markdown documents on its default branch.")
        else if (shown.isEmpty()) Notice("No matching documents", "Try another filter.")
        var folder: String? = null
        shown.forEach { doc ->
            if (doc.folder != folder) {
                folder = doc.folder
                Text(doc.folder.ifEmpty { "Repository root" }, Modifier.padding(top = 8.dp).semantics { heading() },
                    style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            NavRow(doc.name) { onOpen(doc) }
        }
        if (index.truncated) Notice("Some documents may be missing", "This repository is large; GitHub returned a partial file list. Use Files to browse the rest.", NoticeKind.Warning)
    }
}

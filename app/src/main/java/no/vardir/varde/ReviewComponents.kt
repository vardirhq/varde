package no.vardir.varde

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import org.json.JSONObject

@Composable
internal fun PullSummary(body: String) {
    val pr = remember(body) { runCatching { JSONObject(body) }.getOrNull() } ?: return
    val head = pr.optJSONObject("head")?.optString("ref").orEmpty()
    val base = pr.optJSONObject("base")?.optString("ref").orEmpty()
    val state = when {
        pr.optBoolean("draft") -> "Draft"
        pr.optBoolean("merged") -> "Merged"
        else -> jsonText(pr, "state", "Unknown").replaceFirstChar { it.uppercase() }
    }
    Surface(color = MaterialTheme.colorScheme.surfaceVariant, shape = RoundedCornerShape(14.dp)) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Text(state, color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelLarge)
            Text("$head → $base", style = MaterialTheme.typography.bodyMedium)
            Text("${pr.optInt("changed_files")} files · +${pr.optInt("additions")} / −${pr.optInt("deletions")}",
                color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
internal fun DiffCard(title: String, patch: String, comments: List<String>, onOpen: (() -> Unit)?) {
    var expanded by remember(title, patch) { mutableStateOf(false) }
    val colored = remember(patch) {
        buildAnnotatedString {
            patch.lines().forEachIndexed { index, line ->
                if (index > 0) append("\n")
                val start = length
                append(line)
                val color = when {
                    line.startsWith("+") && !line.startsWith("+++") -> Color(0xFF9DDBAE)
                    line.startsWith("-") && !line.startsWith("---") -> Color(0xFFFFA9A4)
                    line.startsWith("@@") -> Color(0xFFADC8FF)
                    else -> Color(0xFFE6E1DC)
                }
                addStyle(SpanStyle(color = color), start, length)
            }
        }
    }
    Surface(color = MaterialTheme.colorScheme.surfaceVariant, shape = RoundedCornerShape(14.dp)) {
        Column(Modifier.fillMaxWidth().padding(14.dp)) {
            TextButton(onClick = { expanded = !expanded }, modifier = Modifier.fillMaxWidth()) {
                Text((if (expanded) "▾  " else "▸  ") + title, modifier = Modifier.weight(1f))
            }
            if (expanded) {
                Text(colored, modifier = Modifier.fillMaxWidth()
                    .background(MaterialTheme.colorScheme.background, RoundedCornerShape(8.dp))
                    .horizontalScroll(rememberScrollState()).padding(12.dp),
                    fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall, softWrap = false)
                if (comments.isNotEmpty()) {
                    Spacer(Modifier.height(12.dp))
                    Text("Review comments", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                    comments.forEach { comment ->
                        Spacer(Modifier.height(8.dp))
                        WorkspaceMarkdown(comment)
                    }
                }
                if (onOpen != null) TextButton(onClick = onOpen) { Text("Open file on GitHub") }
            }
        }
    }
}

@Composable
internal fun JobLogPanel(body: String) {
    val clipboard = LocalClipboardManager.current
    val excerpt = remember(body) { failureExcerpt(body) }
    var showFull by remember(body) { mutableStateOf(false) }
    Surface(color = MaterialTheme.colorScheme.surfaceVariant, shape = RoundedCornerShape(14.dp)) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(if (excerpt.isNotBlank() && !showFull) "Possible error lines" else "Job log",
                color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold)
            if (excerpt.isNotBlank()) {
                Text("Matches are selected by keywords and may not be the cause.", style = MaterialTheme.typography.bodySmall)
                TextButton(onClick = { clipboard.setText(AnnotatedString(excerpt)) }) { Text("Copy error excerpt") }
                TextButton(onClick = { showFull = !showFull }) { Text(if (showFull) "Show excerpt" else "Show full loaded log") }
            }
            Text(if (showFull || excerpt.isBlank()) body else excerpt, style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.onSurface)
        }
    }
}

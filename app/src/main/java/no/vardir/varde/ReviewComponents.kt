package no.vardir.varde

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import org.json.JSONObject

/**
 * A changed file's diff. With [onCommentLine], tapping a line starts a review comment on it; lines are separate
 * rows so each can be focused and activated with TalkBack too.
 */
@Composable
internal fun DiffCard(title: String, patch: String, comments: List<String>, onOpen: (() -> Unit)?, onCommentLine: ((DiffLine) -> Unit)? = null) {
    var expanded by remember(title, patch) { mutableStateOf(false) }
    val diff = VardeTheme.colors
    val lines = remember(patch) { parsePatch(patch) }
    VardeCard(contentPadding = PaddingValues(6.dp)) {
        Column(Modifier.fillMaxWidth().padding(8.dp)) {
            TextButton(onClick = { expanded = !expanded }, modifier = Modifier.fillMaxWidth()) {
                Text((if (expanded) "▾  " else "▸  ") + title, modifier = Modifier.weight(1f))
            }
            if (expanded) {
                if (onCommentLine != null) Text("Tap a line to comment on it.", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 4.dp, vertical = 4.dp))
                Column(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.background, RoundedCornerShape(8.dp))
                    .horizontalScroll(rememberScrollState()).padding(vertical = 8.dp).width(IntrinsicSize.Max)) {
                    lines.forEach { line ->
                        val color = when (line.kind) {
                            DiffKind.Added -> diff.diffAdded
                            DiffKind.Removed -> diff.diffRemoved
                            DiffKind.Hunk -> diff.diffHunk
                            else -> diff.diffContext
                        }
                        val canComment = onCommentLine != null && line.line != null
                        Text(line.text.ifEmpty { " " }, color = color, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall,
                            softWrap = false, modifier = Modifier.fillMaxWidth()
                                .then(if (canComment) Modifier.clickable(onClickLabel = "Comment on line ${line.line}") { onCommentLine?.invoke(line) } else Modifier)
                                .padding(horizontal = 12.dp, vertical = 1.dp))
                    }
                }
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
internal fun JobLogPanel(body: String, assistant: OnDeviceAssistant) {
    val clipboard = LocalClipboardManager.current
    val scope = rememberCoroutineScope()
    var suggesting by remember(body) { mutableStateOf(false) }
    var suggestion by remember(body) { mutableStateOf<String?>(null) }
    var suggestionFailed by remember(body) { mutableStateOf<String?>(null) }
    val excerpt = remember(body) { failureExcerpt(body) }
    var showFull by remember(body) { mutableStateOf(false) }
    VardeCard {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(if (excerpt.isNotBlank() && !showFull) "Possible error lines" else "Job log",
                color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold)
            // The model only picks line numbers; the suggestion is cut from the real log.
            if (assistant.ready && suggestion == null) AssistButton("Suggest the important lines", suggesting) {
                suggesting = true; suggestionFailed = null
                scope.launch {
                    when (val result = assistant.use { suggestLogExcerpt(it, body) }) {
                        is AssistResult.Done -> suggestion = result.value
                        is AssistResult.Failed -> suggestionFailed = result.reason
                    }
                    suggesting = false
                }
            }
            suggestionFailed?.let { reason -> Text("Couldn't suggest lines. $reason", style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant) }
            suggestion?.let { lines ->
                Text("Suggested lines", fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.primary)
                Text("Picked by on-device AI from this log. It may be wrong; the full log is below.", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(lines, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.onSurface)
                TextButton(onClick = { clipboard.setText(AnnotatedString(lines)) }) { Text("Copy suggested lines") }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            }
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

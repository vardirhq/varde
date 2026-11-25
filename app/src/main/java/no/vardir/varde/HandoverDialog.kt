package no.vardir.varde

import android.content.Intent
import android.util.Base64
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import org.json.JSONObject

@Composable
internal fun HandoverDialog(vm: Workspace, repo: Repo, page: String, response: CachedResponse?, onClose: () -> Unit) {
    val clipboard = LocalClipboardManager.current
    val context = LocalContext.current
    var request by remember { mutableStateOf("") }
    var instructions by remember { mutableStateOf<List<Pair<String, String>>>(emptyList()) }
    var warnings by remember { mutableStateOf<List<String>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var draft by remember { mutableStateOf<String?>(null) }
    val evidence = remember(page, response) {
        when {
            response == null -> ""
            page.startsWith("log:") -> failureExcerpt(response.body).ifBlank { "No obvious error lines found in the loaded log." }
            page.startsWith("pull:") -> runCatching {
                val pr = JSONObject(response.body)
                val head = pr.optJSONObject("head")
                val base = pr.optJSONObject("base")
                listOf(
                    "PR: " + jsonText(pr, "html_url"),
                    "Title: " + jsonText(pr, "title"),
                    "State: " + jsonText(pr, "state"),
                    "Head: " + (head?.optString("ref") ?: "unknown") + " (" + (head?.optString("sha") ?: "unknown") + ")",
                    "Base: " + (base?.optString("ref") ?: "unknown"),
                    "Description:\n" + jsonText(pr, "body", "No description").take(12000)
                ).joinToString("\n")
            }.getOrDefault("PR details unavailable.")
            else -> ""
        }
    }
    LaunchedEffect(repo.id) {
        val found = mutableListOf<Pair<String, String>>()
        val missing = mutableListOf<String>()
        for (name in listOf("AGENTS.md", "CLAUDE.md")) {
            try {
                val result = vm.workspaceLoad(repoApi(repo) + "/contents/" + name)
                val json = JSONObject(result.body)
                val encoded = jsonText(json, "content")
                if (encoded.isBlank()) throw IllegalStateException("No file content")
                val decoded = String(Base64.decode(encoded, Base64.DEFAULT), Charsets.UTF_8)
                found.add(("$name (repository default branch" + if (result.cached) ", cached)" else ")") to decoded.take(16000))
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { missing.add("$name unavailable; check it manually.") }
        }
        instructions = found
        warnings = missing
        loading = false
    }
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text("Agent handover") },
        text = {
            Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (draft == null) {
                    Text("Add your request, then review and edit the brief before copying or sharing it.",
                        style = MaterialTheme.typography.bodyMedium)
                    OutlinedTextField(request, { request = it }, Modifier.fillMaxWidth(),
                        label = { Text("Task and acceptance criteria") }, minLines = 3)
                    if (evidence.isNotBlank()) Text("Includes the current screen's selected evidence.",
                        style = MaterialTheme.typography.bodySmall)
                    if (response?.cached == true) Text("Current screen uses cached data.", color = MaterialTheme.colorScheme.primary)
                    if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
                    warnings.forEach { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                    Text("Repository instructions are read from the default branch; check nested instructions and the target branch.",
                        style = MaterialTheme.typography.bodySmall)
                } else {
                    Text("Review before sharing: this can contain private repository information.",
                        style = MaterialTheme.typography.bodySmall)
                    OutlinedTextField(draft.orEmpty(), { draft = it }, Modifier.fillMaxWidth(),
                        label = { Text("Editable brief") }, minLines = 8)
                }
            }
        },
        confirmButton = {
            if (draft == null) TextButton(enabled = !loading, onClick = {
                val note = if (warnings.isEmpty()) "" else warnings.joinToString("\n")
                draft = buildHandover(repo.url, page, request, listOf(evidence, note).filter { it.isNotBlank() }.joinToString("\n\n"), instructions)
            }) { Text("Review brief") }
            else TextButton(onClick = {
                val intent = Intent(Intent.ACTION_SEND).setType("text/plain")
                    .putExtra(Intent.EXTRA_TEXT, draft.orEmpty())
                context.startActivity(Intent.createChooser(intent, "Share handover"))
            }) { Text("Share") }
        },
        dismissButton = {
            Row {
                if (draft != null) TextButton(onClick = { clipboard.setText(AnnotatedString(draft.orEmpty())) }) { Text("Copy") }
                TextButton(onClick = onClose) { Text("Close") }
            }
        }
    )
}

package no.vardir.varde

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@Composable
internal fun ProductIssueDialog(vm: Workspace, item: WorkItem, repos: List<Repo>, onClose: () -> Unit, onCreated: (String) -> Boolean) {
    val scope = rememberCoroutineScope()
    val uri = LocalUriHandler.current
    var repoId by remember { mutableStateOf(repos.firstOrNull()?.id) }
    var title by remember { mutableStateOf(item.title) }
    var body by remember { mutableStateOf(item.details) }
    var menu by remember { mutableStateOf(false) }
    var confirming by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var unknown by remember { mutableStateOf(false) }
    var created by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    val repo = repos.firstOrNull { it.id == repoId }
    AlertDialog(onDismissRequest = { if (!busy) onClose() }, title = { Text("Create GitHub issue") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (created != null) {
                    Text("Issue created, but it could not be saved to the product. Open it and copy its link before closing.")
                    TextButton(onClick = { runCatching { uri.openUri(created.orEmpty()) } }) { Text(created.orEmpty()) }
                } else if (!confirming) {
                    Box {
                        OutlinedButton(onClick = { menu = true }, enabled = repos.isNotEmpty()) { Text(repo?.let { "${it.owner}/${it.name} ▾" } ?: "No visible repositories") }
                        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                            repos.forEach { candidate ->
                                DropdownMenuItem(text = { Text("${candidate.owner}/${candidate.name}") },
                                    onClick = { repoId = candidate.id; menu = false })
                            }
                        }
                    }
                    OutlinedTextField(title, { title = it }, label = { Text("Issue title") }, singleLine = true)
                    OutlinedTextField(body, { body = it }, label = { Text("Issue description") }, minLines = 3)
                } else {
                    Text("Create in ${repo?.owner}/${repo?.name}?")
                    Text(title, style = MaterialTheme.typography.titleSmall)
                    if (body.isNotBlank()) Text(body, style = MaterialTheme.typography.bodySmall)
                }
                error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            }
        },
        confirmButton = {
            if (created == null && !confirming) TextButton(enabled = repo != null && title.isNotBlank(), onClick = { confirming = true }) { Text("Review") }
            else if (created == null) TextButton(enabled = !busy && !unknown, onClick = {
                val target = repo ?: return@TextButton
                busy = true; error = null
                scope.launch {
                    try {
                        val url = vm.createProductIssue(target, title.trim(), body)
                        created = url
                        if (onCreated(url)) onClose()
                    } catch (e: CancellationException) { throw e }
                    catch (e: WriteOutcomeUnknown) { unknown = true; error = e.message }
                    catch (e: Exception) { error = e.message ?: "Could not create issue." }
                    finally { busy = false }
                }
            }) { Text(if (busy) "Creating…" else "Confirm") }
        },
        dismissButton = { TextButton(enabled = !busy, onClick = {
            if (confirming && !unknown && created == null) confirming = false else onClose()
        }) { Text(if (confirming && !unknown && created == null) "Back" else "Close") } })
}

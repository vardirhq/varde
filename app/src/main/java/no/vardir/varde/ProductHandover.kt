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
import kotlinx.coroutines.launch
import org.json.JSONObject

@Composable
internal fun ProductHandover(
    github: GitHubApi, inbox: AttentionInbox, repositories: RepositoryState, delegations: DelegationTracker, assistant: OnDeviceAssistant,
    product: Product, repos: List<Repo>, onClose: () -> Unit) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    var selected by remember { mutableStateOf(repos.map { it.id }.toSet()) }
    var request by remember { mutableStateOf("") }
    var draft by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var track by remember { mutableStateOf(delegations.error == null) }
    var drafting by remember { mutableStateOf(false) }
    var beforeDraft by remember { mutableStateOf<String?>(null) }
    var draftNote by remember { mutableStateOf<String?>(null) }
    var trackRepoId by remember { mutableStateOf(repos.firstOrNull()?.id) }
    var trackMenu by remember { mutableStateOf(false) }
    var tracked by remember { mutableStateOf(false) }
    val trackingId = remember { newTrackingId() }
    // The agent opens its pull request in one repository; follow that one.
    val trackRepo = repos.firstOrNull { it.id == trackRepoId && it.id in selected } ?: repos.firstOrNull { it.id in selected }
    fun record() {
        val target = trackRepo
        if (!track || tracked || target == null) return
        val title = request.lineSequence().map { it.trim() }.firstOrNull { it.isNotBlank() }?.take(100) ?: product.name
        tracked = delegations.add(Delegation(trackingId, target.id, "${target.owner}/${target.name}", title, java.time.Instant.now().toString()))
        if (!tracked) error = "Couldn't save tracking. The brief is unchanged."
    }
    AlertDialog(onDismissRequest = { if (!busy) onClose() }, title = { Text("Product handover") },
        text = {
            Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (draft == null) {
                    Text("Choose the relevant repositories. Root instructions are read from each default branch; check target branches and nested instructions separately.",
                        style = MaterialTheme.typography.bodySmall)
                    repos.forEach { repo ->
                        Row {
                            Checkbox(repo.id in selected, { selected = if (it) selected + repo.id else selected - repo.id })
                            Text("${repo.owner}/${repo.name}", Modifier.padding(top = 14.dp))
                        }
                    }
                    OutlinedTextField(request, { request = it; beforeDraft = null; draftNote = null }, label = { Text("Task and acceptance criteria") }, minLines = 3)
                    // Context is the product's own notes and open work; repository instructions are added later, verbatim.
                    if (assistant.ready) AssistButton("Draft task and criteria", drafting, enabled = request.isNotBlank() && !busy) {
                        drafting = true
                        scope.launch {
                            val context = buildString {
                                appendLine("Product: ${product.name} (${product.stage})")
                                if (product.description.isNotBlank()) appendLine("Description: ${product.description}")
                                if (product.next.isNotBlank()) appendLine("Next: ${product.next}")
                                if (product.milestones.isNotBlank()) appendLine("Milestones: ${product.milestones}")
                                product.work.filterNot { it.done }.take(15).forEach { appendLine("Open ${it.kind.lowercase()}: ${it.title}") }
                                appendLine("Repositories: " + repos.filter { it.id in selected }.joinToString { "${it.owner}/${it.name}" })
                            }
                            val result = assistant.use { draftBrief(it, request, context) }
                            if (result != null) { beforeDraft = request; request = result.asRequest(); draftNote = DraftedNote }
                            else draftNote = "Couldn't draft on this phone. Your text is unchanged."
                            drafting = false
                        }
                    }
                    draftNote?.let { note -> GeneratedNote(note, beforeDraft?.let { previous -> { request = previous; beforeDraft = null; draftNote = null } }) }
                    TrackOption(track, enabled = delegations.error == null && trackRepo != null,
                        detail = "Adds a tracking ID for the agent to put in its pull request, then follows it in the Inbox.") { track = it }
                    if (track && trackRepo != null && selected.size > 1) Box {
                        OutlinedButton(onClick = { trackMenu = true }) { Text("Pull request expected in ${trackRepo.name}") }
                        DropdownMenu(expanded = trackMenu, onDismissRequest = { trackMenu = false }) {
                            repos.filter { it.id in selected }.forEach { candidate ->
                                DropdownMenuItem(text = { Text("${candidate.owner}/${candidate.name}") },
                                    onClick = { trackRepoId = candidate.id; trackMenu = false })
                            }
                        }
                    }
                    if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                } else {
                    Text("Review before sharing; this brief may contain private product and repository information.", style = MaterialTheme.typography.bodySmall)
                    OutlinedTextField(draft.orEmpty(), { draft = it }, label = { Text("Editable brief") }, minLines = 8)
                    if (tracked) Text("Tracking in the Inbox under Delegated.", style = MaterialTheme.typography.bodySmall,
                        color = VardeTheme.colors.success)
                }
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            if (draft == null) TextButton(enabled = !busy && request.isNotBlank(), onClick = {
                busy = true; error = null
                scope.launch {
                    try {
                        val chosen = repos.filter { it.id in selected }
                        val parts = mutableListOf<String>()
                        for (repo in chosen) {
                            for (name in listOf("AGENTS.md", "CLAUDE.md")) {
                                try {
                                    val result = github.load(repoApi(repo) + "/contents/" + name)
                                    val value = JSONObject(result.body).optString("content", "")
                                    require(value.isNotBlank())
                                    val decoded = String(Base64.decode(value, Base64.DEFAULT), Charsets.UTF_8)
                                    parts.add("## ${repo.owner}/${repo.name} — $name (default branch" +
                                        (if (result.cached) ", cached" else "") + ")\n" + decoded.take(16000) +
                                        (if (decoded.length > 16000) "\n[Instructions truncated; read original.]" else ""))
                                } catch (e: CancellationException) { throw e }
                                catch (_: Exception) { parts.add("## ${repo.owner}/${repo.name} — $name\nUnavailable. Check manually.") }
                            }
                        }
                        draft = buildString {
                            appendLine("# Product handover")
                            appendLine("Product: ${product.name}")
                            appendLine("Stage: ${product.stage} · Priority: ${product.priority}")
                            appendLine("Description: ${product.description}")
                            appendLine("Next: ${product.next}")
                            if (product.milestones.isNotBlank()) appendLine("Milestone notes: ${product.milestones}")
                            if (product.ideas.isNotBlank()) appendLine("Idea notes: ${product.ideas}")
                            appendLine("\n## Request\n$request")
                            appendLine("\n## Repositories")
                            if (chosen.isEmpty()) appendLine("None selected.")
                            chosen.forEach { appendLine("- ${it.owner}/${it.name}: ${it.url}") }
                            if (product.work.isNotEmpty()) {
                                appendLine("\n## Product work")
                                product.work.forEach { appendLine("- [${if (it.done) "x" else " "}] ${it.kind}: ${it.title}" +
                                    (if (it.url.isBlank()) "" else " — ${it.url}") +
                                    (if (it.details.isBlank()) "" else "\n  ${it.details}")) }
                            }
                            val selectedAttention = inbox.items(repositories.excluded).filter { it.repositoryId in selected }
                            appendLine("\n## Available attention")
                            if (inbox.mayBeIncomplete(offline = repositories.snapshotTime != null))
                                appendLine("Attention data may be incomplete or stale; verify on GitHub.")
                            if (selectedAttention.isEmpty()) appendLine("No items in the available attention feed.")
                            selectedAttention.forEach { appendLine("- ${it.repository} · ${it.kind.label}: ${it.title} — ${it.url}") }
                            parts.forEach { appendLine("\n$it") }
                            if (track && trackRepo != null) append(trackingInstruction(trackingId))
                        }
                    } catch (e: CancellationException) { throw e }
                    catch (_: Exception) { error = "Couldn't prepare the handover." }
                    finally { busy = false }
                }
            }) { Text("Review brief") }
            else TextButton(onClick = {
                record()
                context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, draft.orEmpty()), "Share handover"))
            }) { Text("Share") }
        },
        dismissButton = { Row {
            if (draft != null) TextButton(onClick = { record(); clipboard.setText(AnnotatedString(draft.orEmpty())) }) { Text("Copy") }
            TextButton(enabled = !busy, onClick = onClose) { Text("Close") }
        } })
}

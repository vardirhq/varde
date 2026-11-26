package no.vardir.varde

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

internal data class Workflow(val id: Long, val name: String, val path: String)

/** Active workflows from GET /actions/workflows. */
internal fun parseWorkflows(body: String): List<Workflow> {
    val array = JSONObject(body).optJSONArray("workflows") ?: JSONArray()
    return (0 until array.length()).mapNotNull { array.optJSONObject(it) }
        .filter { jsonText(it, "state") == "active" }
        .map { Workflow(it.optLong("id"), jsonText(it, "name", "Workflow"), jsonText(it, "path")) }
}

/** Why a manual run was refused, in words. A 422 almost always means the workflow has no `workflow_dispatch` trigger. */
internal fun dispatchFailure(e: Exception): String = when {
    e is HttpFailure && e.code == 422 -> "GitHub can't start this workflow by hand. Its file needs a workflow_dispatch trigger, " +
        "and any required inputs need defaults. (${e.message})"
    else -> e.message ?: "GitHub refused to start the workflow."
}

/** Starts a workflow on a branch, after confirmation. Workflows with required inputs are best started on GitHub. */
@Composable
internal fun RunWorkflowDialog(github: GitHubApi, repo: Repo, onDismiss: () -> Unit, onStarted: (String) -> Unit) {
    val scope = rememberCoroutineScope()
    var workflows by remember { mutableStateOf<List<Workflow>?>(null) }
    var loadError by remember { mutableStateOf<String?>(null) }
    var selected by remember { mutableStateOf<Workflow?>(null) }
    var ref by remember { mutableStateOf(repo.defaultBranch) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var unknown by remember { mutableStateOf(false) }
    LaunchedEffect(repo.id) {
        try { workflows = parseWorkflows(github.load(repoApi(repo) + "/actions/workflows?per_page=100").body) }
        catch (e: CancellationException) { throw e }
        catch (e: Exception) { loadError = e.message ?: "Couldn't list workflows." }
    }
    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text("Run a workflow") },
        text = {
            Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Starts the workflow on GitHub Actions. It uses Actions minutes like any other run.", style = MaterialTheme.typography.bodyMedium)
                when {
                    loadError != null -> Text(loadError.orEmpty(), color = MaterialTheme.colorScheme.error)
                    workflows == null -> LinearProgressIndicator(Modifier.fillMaxWidth())
                    workflows!!.isEmpty() -> Text("This repository has no active workflows.")
                    else -> workflows!!.forEach { w ->
                        Row(Modifier.fillMaxWidth().selectable(selected == w, enabled = !busy, role = Role.RadioButton) { selected = w },
                            verticalAlignment = Alignment.CenterVertically) {
                            RadioButton(selected == w, onClick = null, enabled = !busy)
                            Column(Modifier.padding(start = 8.dp)) {
                                Text(w.name, style = MaterialTheme.typography.bodyMedium)
                                Text(w.path, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
                OutlinedTextField(ref, { ref = it.trim() }, Modifier.fillMaxWidth(), label = { Text("Branch or tag") }, singleLine = true,
                    enabled = !busy && !unknown)
                error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            }
        },
        confirmButton = {
            if (!unknown) TextButton(enabled = !busy && selected != null && ref.isNotBlank(), onClick = {
                val workflow = selected ?: return@TextButton
                busy = true; error = null
                scope.launch {
                    try {
                        github.send("POST", repoApi(repo) + "/actions/workflows/${workflow.id}/dispatches", "Starting the workflow",
                            JSONObject().put("ref", ref))
                        onStarted("GitHub started “${workflow.name}” on $ref. Refresh in a moment to see the run.")
                    } catch (e: CancellationException) { throw e }
                    catch (e: WriteOutcomeUnknown) { unknown = true; error = e.message }
                    catch (e: Exception) { error = dispatchFailure(e) }
                    finally { busy = false }
                }
            }) { Text(if (busy) "Starting…" else "Run workflow") }
        },
        dismissButton = { TextButton(enabled = !busy, onClick = onDismiss) { Text(if (unknown) "Close and refresh" else "Cancel") } },
    )
}

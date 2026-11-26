package no.vardir.varde

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import org.json.JSONObject

/** The fields of a workflow run the run actions need. */
internal data class RunInfo(val id: Long, val number: Int, val workflowId: Long, val name: String, val status: String, val url: String)

internal fun parseRunInfo(body: String): RunInfo? = runCatching {
    val o = JSONObject(body)
    RunInfo(o.getLong("id"), o.optInt("run_number"), o.getLong("workflow_id"), jsonText(o, "name", "Workflow"),
        jsonText(o, "status"), jsonText(o, "html_url"))
}.getOrNull()

private enum class RunWrite { Delete, Disable }

/**
 * Ways to deal with a failed run that won't be fixed: hide it in Varde, disable its workflow, or delete the run.
 * GitHub writes need explicit confirmation; a completed run can't be cancelled, so delete is the GitHub-side option.
 */
@Composable
internal fun RunActions(
    github: GitHubApi, dismissed: DismissedItems, repo: Repo, runId: Long, onDeleted: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var info by remember(runId) { mutableStateOf<RunInfo?>(null) }
    var confirm by remember { mutableStateOf<RunWrite?>(null) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<Pair<NoticeKind, String>?>(null) }
    LaunchedEffect(repo.id, runId) {
        info = try { parseRunInfo(github.load(repoApi(repo) + "/actions/runs/$runId").body) }
        catch (e: CancellationException) { throw e } catch (_: Exception) { null }
    }
    val inboxItem = AttentionItem(info?.name ?: "Workflow", "${repo.owner}/${repo.name}", AttentionKind.WorkflowFailed,
        info?.url?.ifBlank { null } ?: "https://github.com/${repo.owner}/${repo.name}/actions/runs/$runId", repo.id)
    val hidden = isDismissed(inboxItem, dismissed.keys)

    confirm?.let { kind ->
        val run = info
        AlertDialog(onDismissRequest = { if (!busy) confirm = null },
            title = { Text(if (kind == RunWrite.Delete) "Delete run #${run?.number ?: runId}?" else "Disable “${run?.name ?: "this workflow"}”?") },
            text = { Text(if (kind == RunWrite.Delete)
                "This permanently deletes the run and its logs from ${repo.owner}/${repo.name}. It can't be undone. The workflow itself is unchanged."
                else "GitHub will stop running this workflow on every trigger, including pushes and schedules, until it's enabled again in the repository's Actions tab. Past runs stay.") },
            confirmButton = { TextButton(enabled = !busy && run != null, onClick = {
                val target = run ?: return@TextButton
                busy = true
                scope.launch {
                    try {
                        if (kind == RunWrite.Delete) github.deleteRun(repo, target.id) else github.disableWorkflow(repo, target.workflowId)
                        dismissed.dismiss(listOf(inboxItem))
                        confirm = null
                        if (kind == RunWrite.Delete) onDeleted()
                        else message = NoticeKind.Success to "“${target.name}” is disabled on GitHub and removed from your Inbox."
                    } catch (e: CancellationException) { throw e }
                    catch (e: WriteOutcomeUnknown) { confirm = null; message = NoticeKind.Warning to "The connection dropped, so it's unclear whether GitHub did it. Refresh to check." }
                    catch (e: Exception) { confirm = null; message = NoticeKind.Error to (e.message ?: "GitHub refused the request.") }
                    finally { busy = false }
                }
            }) { Text(if (busy) "Working…" else if (kind == RunWrite.Delete) "Delete run" else "Disable workflow") } },
            dismissButton = { TextButton(enabled = !busy, onClick = { confirm = null }) { Text("Cancel") } })
    }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        message?.let { (kind, text) -> Notice(if (kind == NoticeKind.Success) "Done" else "Not done", text, kind) }
        if (hidden) OutlinedButton(onClick = { dismissed.restore(listOf(inboxItem)) }, modifier = Modifier.fillMaxWidth()) { Text("Show in Inbox again") }
        else OutlinedButton(onClick = { dismissed.dismiss(listOf(inboxItem)) }, modifier = Modifier.fillMaxWidth()) { Text("Dismiss from Inbox") }
        OutlinedButton(onClick = { confirm = RunWrite.Disable }, enabled = info != null, modifier = Modifier.fillMaxWidth()) {
            Text("Disable workflow on GitHub…")
        }
        OutlinedButton(onClick = { confirm = RunWrite.Delete }, enabled = info != null && info?.status == "completed", modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error)) {
            Text("Delete run on GitHub…")
        }
        Text("Dismissing only hides it in Varde until a new run fails. Disabling stops the workflow; deleting removes this run and its logs.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

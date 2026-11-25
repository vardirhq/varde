package no.vardir.varde

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import org.json.JSONObject

@Composable
internal fun GitHubActionDialog(
    vm: Workspace, repo: Repo, number: Int, kind: WriteKind, reviewedSha: String,
    isPull: Boolean, onDismiss: () -> Unit, onSuccess: () -> Unit
) {
    val scope = rememberCoroutineScope()
    var body by remember { mutableStateOf("") }
    var mergeMethod by remember { mutableStateOf("merge") }
    var busy by remember { mutableStateOf(false) }
    var outcomeUnknown by remember { mutableStateOf(false) }
    var verifying by remember { mutableStateOf(kind != WriteKind.COMMENT) }
    var ready by remember { mutableStateOf(kind == WriteKind.COMMENT) }
    var confirmation by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var prInfo by remember { mutableStateOf("") }
    var checks by remember { mutableStateOf("") }
    val title = when (kind) {
        WriteKind.COMMENT -> "Add comment"
        WriteKind.APPROVE -> "Approve pull request"
        WriteKind.REQUEST_CHANGES -> "Request changes"
        WriteKind.MERGE -> "Merge pull request"
    }
    LaunchedEffect(kind, number, reviewedSha) {
        if (kind != WriteKind.COMMENT) {
            try {
                val result = vm.workspaceLoad(repoApi(repo) + "/pulls/" + number)
                check(!result.cached) { "Cannot verify the current pull request offline. Reconnect and refresh." }
                val pr = JSONObject(result.body)
                val sha = pr.optJSONObject("head")?.optString("sha").orEmpty()
                check(sha == reviewedSha && sha.matches(Regex("[a-fA-F0-9]{40}"))) {
                    "The PR head has changed or is unavailable. Close this dialog and refresh before continuing."
                }
                check(pr.optString("state") == "open") { "This pull request is no longer open." }
                if (kind == WriteKind.MERGE) check(!pr.optBoolean("draft")) { "Draft pull requests cannot be merged." }
                val head = pr.optJSONObject("head")?.optString("ref").orEmpty()
                val base = pr.optJSONObject("base")?.optString("ref").orEmpty()
                prInfo = "${jsonText(pr, "title")}\n$head → $base\nCommit: ${sha.take(12)}"
                if (kind == WriteKind.MERGE) {
                    try {
                        val checkResponse = vm.workspaceLoad(repoApi(repo) + "/commits/" + pathPart(sha) + "/check-runs?per_page=100")
                        val statusResponse = vm.workspaceLoad(repoApi(repo) + "/commits/" + pathPart(sha) + "/status")
                        check(!checkResponse.cached && !statusResponse.cached) { "Checks could not be verified online." }
                        val checkJson = JSONObject(checkResponse.body)
                        val runs = checkJson.optJSONArray("check_runs")
                        val count = runs?.length() ?: 0
                        val failed = (0 until count).count { runs?.optJSONObject(it)?.optString("conclusion") in setOf("failure", "timed_out", "action_required", "cancelled") }
                        val pending = (0 until count).count { runs?.optJSONObject(it)?.optString("status") != "completed" }
                        val state = JSONObject(statusResponse.body).optString("state", "unknown")
                        checks = "$count check runs · $failed failed · $pending pending\nCommit status: $state" +
                            if (checkJson.optInt("total_count") > count) "\nOnly the first 100 checks are shown." else ""
                    } catch (e: CancellationException) { throw e }
                    catch (_: Exception) { checks = "Checks could not be verified. Open GitHub or review the checks before merging." }
                }
                ready = true
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { error = e.message ?: "Unable to verify pull request." }
            finally { verifying = false }
        }
    }
    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text(if (confirmation) "Confirm $title".replaceFirstChar { it.uppercase() } else title) },
        text = {
            Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("${repo.owner}/${repo.name} · ${if (isPull) "PR" else "Issue"} #$number", style = MaterialTheme.typography.labelLarge)
                if (verifying) LinearProgressIndicator(Modifier.fillMaxWidth())
                if (prInfo.isNotBlank()) Text(prInfo, style = MaterialTheme.typography.bodyMedium)
                if (checks.isNotBlank()) Text(checks, style = MaterialTheme.typography.bodySmall)
                if (kind == WriteKind.MERGE) {
                    Text("GitHub enforces branch rules and checks when you submit. The merge is locked to the displayed commit.",
                        style = MaterialTheme.typography.bodySmall)
                    if (!confirmation) {
                        listOf("merge" to "Merge commit", "squash" to "Squash", "rebase" to "Rebase").forEach { (method, label) ->
                            Row {
                                RadioButton(selected = mergeMethod == method, onClick = { mergeMethod = method })
                                Text(label, Modifier.padding(top = 12.dp))
                            }
                        }
                    } else Text("Method: $mergeMethod")
                } else if (!confirmation) {
                    OutlinedTextField(body, { body = it }, Modifier.fillMaxWidth(),
                        label = { Text(if (kind == WriteKind.APPROVE) "Comment (optional)" else "Comment") }, minLines = 3)
                } else if (body.isNotBlank()) Text(body)
                error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                if (confirmation) Text("This will submit to GitHub.", color = MaterialTheme.colorScheme.primary)
            }
        },
        confirmButton = {
            if (!confirmation) TextButton(enabled = ready && !verifying &&
                (kind !in setOf(WriteKind.COMMENT, WriteKind.REQUEST_CHANGES) || body.isNotBlank()),
                onClick = { error = null; confirmation = true }) { Text("Review") }
            else TextButton(enabled = !busy && !outcomeUnknown, onClick = {
                busy = true; error = null
                scope.launch {
                    try {
                        vm.submitWrite(repo, WriteAction(kind, number, body, reviewedSha, mergeMethod))
                        onSuccess()
                    } catch (e: CancellationException) { throw e }
                    catch (e: WriteOutcomeUnknown) { outcomeUnknown = true; error = e.message }
                    catch (e: Exception) { error = e.message ?: "Action failed." }
                    finally { busy = false }
                }
            }) { Text(if (busy) "Submitting…" else "Confirm") }
        },
        dismissButton = {
            TextButton(enabled = !busy, onClick = { if (confirmation && !outcomeUnknown) { confirmation = false; error = null } else onDismiss() }) {
                Text(if (outcomeUnknown) "Close and refresh" else if (confirmation) "Back" else "Cancel")
            }
        }
    )
}

package no.vardir.varde

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.ui.Alignment
import androidx.compose.ui.semantics.Role
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
    github: GitHubApi, assistant: OnDeviceAssistant, repo: Repo, number: Int, kind: WriteKind, reviewedSha: String,
    isPull: Boolean, onDismiss: () -> Unit, onSuccess: (String) -> Unit
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val mergePrefs = remember { context.getSharedPreferences("merge", android.content.Context.MODE_PRIVATE) }
    var cleanup by remember { mutableStateOf<BranchCleanup?>(null) }
    // Remembered across merges; the repository's own auto-delete setting takes precedence when it's on.
    var deleteBranch by remember { mutableStateOf(mergePrefs.getBoolean("deleteBranch", true)) }
    val scope = rememberCoroutineScope()
    var body by remember { mutableStateOf("") }
    var rewriting by remember { mutableStateOf(false) }
    var beforeRewrite by remember { mutableStateOf<String?>(null) }
    var rewriteNote by remember { mutableStateOf<String?>(null) }
    var mergeMethod by remember { mutableStateOf("merge") }
    var busy by remember { mutableStateOf(false) }
    var outcomeUnknown by remember { mutableStateOf(false) }
    var verifying by remember { mutableStateOf(kind != WriteKind.COMMENT) }
    var ready by remember { mutableStateOf(kind == WriteKind.COMMENT) }
    var confirmation by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var prInfo by remember { mutableStateOf("") }
    var checks by remember { mutableStateOf("") }
    var mergeMethods by remember { mutableStateOf(MergeMethods.map { it.first }) }
    val title = when (kind) {
        WriteKind.COMMENT -> "Add comment"
        WriteKind.APPROVE -> "Approve pull request"
        WriteKind.REQUEST_CHANGES -> "Request changes"
        WriteKind.MERGE -> "Merge pull request"
    }
    LaunchedEffect(kind, number, reviewedSha) {
        if (kind != WriteKind.COMMENT) {
            try {
                val result = github.load(repoApi(repo) + "/pulls/" + number)
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
                    // Offer only the merge methods the repository allows, so GitHub doesn't refuse after confirmation.
                    val repoJson = runCatching { JSONObject(github.load(repoApi(repo)).body) }.getOrNull()
                    repoJson?.let { allowedMergeMethods(it) }
                        ?.takeIf { it.isNotEmpty() }?.let { allowed -> mergeMethods = allowed; if (mergeMethod !in allowed) mergeMethod = allowed.first() }
                    // Other open pull requests built on this branch would be closed by deleting it.
                    val dependents = runCatching {
                        val r = github.load(repoApi(repo) + "/pulls?state=open&per_page=100&base=" + pathPart(head))
                        if (r.cached) null else org.json.JSONArray(r.body).length()
                    }.getOrNull()
                    cleanup = branchCleanup(pr, repoJson, dependents)
                    try {
                        val checkResponse = github.load(repoApi(repo) + "/commits/" + pathPart(sha) + "/check-runs?per_page=100")
                        val statusResponse = github.load(repoApi(repo) + "/commits/" + pathPart(sha) + "/status")
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
                        MergeMethods.filter { it.first in mergeMethods }.forEach { (method, label) ->
                            Row {
                                RadioButton(selected = mergeMethod == method, onClick = { mergeMethod = method })
                                Text(label, Modifier.padding(top = 12.dp))
                            }
                        }
                    } else Text("Method: " + (MergeMethods.firstOrNull { it.first == mergeMethod }?.second ?: mergeMethod))
                    cleanup?.let { c ->
                        when {
                            c.automatic -> Text("GitHub deletes ${c.branch} after merging (repository setting).", style = MaterialTheme.typography.bodySmall)
                            c.offer && !confirmation -> Row(Modifier.fillMaxWidth().toggleable(deleteBranch, role = Role.Checkbox) {
                                deleteBranch = it; mergePrefs.edit().putBoolean("deleteBranch", it).apply()
                            }, verticalAlignment = Alignment.CenterVertically) {
                                Checkbox(deleteBranch, onCheckedChange = null)
                                Text("Delete ${c.branch} after merging", Modifier.padding(start = 8.dp), style = MaterialTheme.typography.bodyMedium)
                            }
                            c.offer -> Text(if (deleteBranch) "Then deletes ${c.branch}. It can be restored from the pull request on GitHub." else "Keeps ${c.branch}.",
                                style = MaterialTheme.typography.bodySmall)
                            c.reason != null -> Text("${c.branch} is kept: ${c.reason}", style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                } else if (!confirmation) {
                    OutlinedTextField(body, { body = it; beforeRewrite = null; rewriteNote = null }, Modifier.fillMaxWidth(),
                        label = { Text(if (kind == WriteKind.APPROVE) "Comment (optional)" else "Comment") }, minLines = 3)
                    if (assistant.ready) AssistButton("Rewrite clearly", rewriting, enabled = body.isNotBlank()) {
                        rewriting = true
                        scope.launch {
                            when (val result = assistant.use { rewriteComment(it, body) }) {
                                is AssistResult.Done -> { beforeRewrite = body; body = result.value; rewriteNote = "Rewritten on this phone by on-device AI. Check it before submitting." }
                                is AssistResult.Failed -> rewriteNote = "Couldn't rewrite; your text is unchanged. ${result.reason}"
                            }
                            rewriting = false
                        }
                    }
                    rewriteNote?.let { note -> GeneratedNote(note, beforeRewrite?.let { previous -> { body = previous; beforeRewrite = null; rewriteNote = null } }) }
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
                        github.submitWrite(repo, WriteAction(kind, number, body, reviewedSha, mergeMethod))
                        val c = cleanup
                        onSuccess(if (kind == WriteKind.MERGE && c != null && c.offer && deleteBranch) {
                            // The merge is done; a failed deletion is reported, never undone or retried silently.
                            try { github.send("DELETE", deleteBranchPath(repo, c.branch), "Deleting the branch"); "Merged, and deleted ${c.branch}." }
                            catch (e: CancellationException) { throw e }
                            catch (e: Exception) { "Merged. The branch ${c.branch} wasn't deleted: ${e.message ?: "GitHub refused."}" }
                        } else if (kind == WriteKind.MERGE) "Merged." + (c?.automatic?.takeIf { it }?.let { " GitHub deletes ${c.branch} automatically." } ?: "")
                        else "GitHub accepted the action.")
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

private val MergeMethods = listOf("merge" to "Merge commit", "squash" to "Squash and merge", "rebase" to "Rebase and merge")

/** Merge methods the repository allows, in GitHub's order; empty when the response doesn't say (no write access). */
internal fun allowedMergeMethods(repo: JSONObject): List<String> = listOf(
    "merge" to "allow_merge_commit", "squash" to "allow_squash_merge", "rebase" to "allow_rebase_merge",
).filter { (_, field) -> repo.has(field) && repo.optBoolean(field) }.map { it.first }

/**
 * Whether the merged branch can be deleted from here. [automatic] when the repository deletes merged branches itself.
 * Not offered for a fork's branch, the default branch, or a branch other open pull requests target (they'd be closed).
 */
internal data class BranchCleanup(val branch: String, val offer: Boolean, val automatic: Boolean, val reason: String?)

internal fun branchCleanup(pr: JSONObject, repo: JSONObject?, dependents: Int?): BranchCleanup {
    val head = pr.optJSONObject("head")
    val branch = head?.let { jsonText(it, "ref") }.orEmpty()
    val headRepo = head?.optJSONObject("repo")?.let { jsonText(it, "full_name") }
    val baseRepo = pr.optJSONObject("base")?.optJSONObject("repo")?.let { jsonText(it, "full_name") }
    val automatic = repo?.optBoolean("delete_branch_on_merge") == true
    fun keep(reason: String) = BranchCleanup(branch, offer = false, automatic = false, reason = reason)
    return when {
        branch.isBlank() -> keep("its name is unknown.")
        headRepo == null || !headRepo.equals(baseRepo, ignoreCase = true) -> keep("it's in a fork, not in this repository.")
        repo != null && branch == jsonText(repo, "default_branch") -> keep("it's the default branch.")
        dependents == null -> keep("Varde couldn't check whether other pull requests are built on it.")
        dependents > 0 -> keep("$dependents open pull request${if (dependents == 1) " targets" else "s target"} it and would be closed.")
        automatic -> BranchCleanup(branch, offer = false, automatic = true, reason = null)
        else -> BranchCleanup(branch, offer = true, automatic = false, reason = null)
    }
}

/** The ref to delete for a branch; slashes in branch names stay path separators. */
internal fun deleteBranchPath(repo: Repo, branch: String): String =
    repoApi(repo) + "/git/refs/heads/" + branch.split('/').joinToString("/") { pathPart(it) }

package no.vardir.varde

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException

private const val DescriptionPreview = 600

/**
 * Single-screen pull request review: what it is, whether it can merge, what changed, then the actions.
 * Consequential actions sit at the end so the verdict and changes are seen first.
 */
@Composable
internal fun PullRequestPage(
    github: GitHubApi, repo: Repo, pr: PullRequest, cached: Boolean,
    onNavigate: (String) -> Unit, onAction: (WriteKind) -> Unit, onHandover: () -> Unit, onOpenUrl: (String) -> Unit,
    onChanged: (String) -> Unit = {},
) {
    var pending by remember { mutableStateOf<PullChange?>(null) }
    pending?.let { change -> PullChangeDialog(github, repo, pr, change, onDismiss = { pending = null }, onDone = { pending = null; onChanged(it) }) }
    var checks by remember(pr.headSha) { mutableStateOf<CheckSummary?>(null) }
    var checksLoading by remember(pr.headSha) { mutableStateOf(true) }
    LaunchedEffect(repo.id, pr.headSha) {
        checksLoading = true
        checks = try {
            if (pr.headSha.isBlank()) null else {
                val result = github.load(repoApi(repo) + "/commits/" + pathPart(pr.headSha) + "/check-runs?per_page=100")
                parseCheckRuns(result.body, result.cached)
            }
        } catch (e: CancellationException) { throw e } catch (_: Exception) { null }
        checksLoading = false
    }
    val verdict = pullVerdict(pr, checks)
    val now = remember(pr) { java.time.Instant.now() }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        // What it is
        VardeCard {
            StatusBadge(pr.status)
            Text(pr.title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
            Text(listOfNotNull(pr.author.ifBlank { null }?.let { "by $it" },
                relativeAge(pr.updatedAt, now)?.let { if (it == "now") "updated just now" else "updated $it ago" }).joinToString(" · "),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text("${pr.headRef} → ${pr.baseRef}", style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
            Text("${plural(pr.changedFiles, "file")} · +${pr.additions} −${pr.deletions} · ${plural(pr.commits, "commit")}",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }

        // Can it merge?
        VardeCard(onClick = if (pr.headSha.isNotBlank()) ({ onNavigate("checks:${pr.headSha}") }) else null) {
            if (checksLoading) {
                Text("Checking status…", style = MaterialTheme.typography.titleSmall)
                LinearProgressIndicator(Modifier.fillMaxWidth())
            } else {
                StatusBadge(verdict.status)
                Text(verdict.detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (pr.headSha.isNotBlank()) Text("View checks", style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary)
            }
        }

        // Description
        if (pr.body.isNotBlank()) {
            var expanded by rememberSaveable(pr.number) { mutableStateOf(pr.body.length <= DescriptionPreview) }
            VardeCard {
                Text("Description", Modifier.semantics { heading() }, style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary)
                WorkspaceMarkdown(if (expanded) pr.body else pr.body.take(DescriptionPreview).trimEnd() + "…")
                if (pr.body.length > DescriptionPreview) TextButton(onClick = { expanded = !expanded }) {
                    Text(if (expanded) "Show less" else "Show full description")
                }
            }
        }

        // What changed and what people said
        SectionHeader("Review")
        NavRow("Changed files", "${plural(pr.changedFiles, "file")} · +${pr.additions} −${pr.deletions}") { onNavigate("changes:${pr.number}") }
        NavRow("Conversation", plural(pr.comments, "comment")) { onNavigate("comments:${pr.number}") }
        NavRow("Review comments", plural(pr.reviewComments, "comment") + " on changed lines") { onNavigate("reviewcomments:${pr.number}") }
        if (pr.headSha.isNotBlank()) NavRow("Commit statuses", "Statuses reported by external services") { onNavigate("statuses:${pr.headSha}") }

        // Actions
        SectionHeader("Act")
        OutlinedButton(onClick = onHandover, modifier = Modifier.fillMaxWidth()) { Text("Prepare agent handover") }
        if (pr.open && pr.headSha.isNotBlank()) OutlinedButton(onClick = { onNavigate(WorkspacePage.Builds(pr.headSha).route) },
            modifier = Modifier.fillMaxWidth()) { Text("Install a build of this PR") }
        when {
            cached -> Notice("Actions unavailable offline", "Reconnect and refresh before submitting GitHub actions.")
            !pr.open -> OutlinedButton(onClick = { onAction(WriteKind.COMMENT) }, modifier = Modifier.fillMaxWidth()) { Text("Add comment") }
            else -> {
                // Full-width buttons keep labels on one line even at large font sizes.
                OutlinedButton(onClick = { onAction(WriteKind.APPROVE) }, modifier = Modifier.fillMaxWidth()) { Text("Approve") }
                OutlinedButton(onClick = { onAction(WriteKind.REQUEST_CHANGES) }, modifier = Modifier.fillMaxWidth()) { Text("Request changes") }
                OutlinedButton(onClick = { onAction(WriteKind.COMMENT) }, modifier = Modifier.fillMaxWidth()) { Text("Comment") }
                HorizontalDivider(Modifier.padding(vertical = 4.dp), color = MaterialTheme.colorScheme.outlineVariant)
                // Merge is never hidden (branch protection is GitHub's call), but it is only emphasized when the evidence says it's ready.
                if (pr.draft) OutlinedButton(onClick = { pending = PullChange.Ready }, enabled = pr.nodeId.isNotBlank(),
                    modifier = Modifier.fillMaxWidth()) { Text("Mark ready for review…") }
                if (pr.mergeableState == "behind") OutlinedButton(onClick = { pending = PullChange.UpdateBranch }, modifier = Modifier.fillMaxWidth()) {
                    Text("Update branch from ${pr.baseRef}…")
                }
                if (verdict.readyToMerge && !pr.draft) Button(onClick = { onAction(WriteKind.MERGE) }, modifier = Modifier.fillMaxWidth()) { Text("Merge…") }
                else OutlinedButton(onClick = { onAction(WriteKind.MERGE) }, enabled = !pr.draft && !checksLoading,
                    modifier = Modifier.fillMaxWidth()) { Text(if (pr.draft) "Drafts can't be merged" else "Merge anyway…") }
                TextButton(onClick = { pending = PullChange.Close }, modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)) { Text("Close without merging…") }
            }
        }
        if (pr.htmlUrl.startsWith("https://github.com/")) TextButton(onClick = { onOpenUrl(pr.htmlUrl) }) { Text("Open on GitHub") }
    }
}

private fun plural(n: Int, word: String) = "$n $word" + if (n == 1) "" else "s"

private enum class PullChange { Ready, UpdateBranch, Close }

/** Confirms and makes one of the smaller pull request changes. Each is locked to the head commit you reviewed. */
@Composable
private fun PullChangeDialog(github: GitHubApi, repo: Repo, pr: PullRequest, change: PullChange, onDismiss: () -> Unit, onDone: (String) -> Unit) {
    val path = repoApi(repo) + "/pulls/" + pr.number
    when (change) {
        PullChange.Ready -> ConfirmedWriteDialog("Mark #${pr.number} ready for review?",
            "GitHub will take it out of draft and notify requested reviewers. Checks that only run on non-draft pull requests will start.",
            "Mark ready", onDismiss, onDone) {
            github.graphql("mutation(\$id: ID!) { markPullRequestReadyForReview(input: {pullRequestId: \$id}) { pullRequest { isDraft } } }",
                org.json.JSONObject().put("id", pr.nodeId), "Marking it ready for review")
            "#${pr.number} is ready for review."
        }
        PullChange.UpdateBranch -> ConfirmedWriteDialog("Update ${pr.headRef}?",
            "GitHub will merge the latest ${pr.baseRef} into ${pr.headRef}, adding a merge commit to the pull request. Checks will run again.",
            "Update branch", onDismiss, onDone) {
            github.send("PUT", "$path/update-branch", "Updating the branch", org.json.JSONObject().put("expected_head_sha", pr.headSha))
            "GitHub is updating ${pr.headRef}. Refresh in a moment to see the new commit."
        }
        PullChange.Close -> ConfirmedWriteDialog("Close #${pr.number} without merging?",
            "The pull request is closed on GitHub and its branch is kept. It can be reopened on GitHub.", "Close pull request",
            onDismiss, onDone, destructive = true, field = WriteField("Comment (optional)", required = false)) { comment ->
            if (comment.isNotBlank()) github.send("POST", repoApi(repo) + "/issues/${pr.number}/comments", "Commenting", org.json.JSONObject().put("body", comment))
            github.send("PATCH", path, "Closing the pull request", org.json.JSONObject().put("state", "closed"))
            "#${pr.number} is closed."
        }
    }
}

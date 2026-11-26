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
) {
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
                if (verdict.readyToMerge && !pr.draft) Button(onClick = { onAction(WriteKind.MERGE) }, modifier = Modifier.fillMaxWidth()) { Text("Merge…") }
                else OutlinedButton(onClick = { onAction(WriteKind.MERGE) }, enabled = !pr.draft && !checksLoading,
                    modifier = Modifier.fillMaxWidth()) { Text(if (pr.draft) "Drafts can't be merged" else "Merge anyway…") }
            }
        }
        if (pr.htmlUrl.startsWith("https://github.com/")) TextButton(onClick = { onOpenUrl(pr.htmlUrl) }) { Text("Open on GitHub") }
    }
}

private fun plural(n: Int, word: String) = "$n $word" + if (n == 1) "" else "s"

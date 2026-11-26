package no.vardir.varde

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import java.time.Instant

/**
 * What happened in a product this week and what's stuck, then whether each repository is ready for a release.
 * Everything is counted from GitHub; nothing is scored or estimated.
 */
@Composable
internal fun ProductThisWeek(
    github: GitHubApi, insights: ProductInsights, inbox: AttentionInbox, delegations: DelegationTracker, repositories: RepositoryState,
    product: Product, repos: List<Repo>, onOpenRepo: (Repo, String) -> Unit,
) {
    val scope = rememberCoroutineScope()
    var refreshing by remember { mutableStateOf(false) }
    LaunchedEffect(product.id, repos) { insights.refresh(product, repos, github::load) }
    val week = insights.weeks[product.id]
    val now = remember(week) { Instant.now() }
    val failing = mergeAttention(productAttention(product, inbox.feedItems, repositories.excluded)
        .filter { it.kind == AttentionKind.ChecksFailing || it.kind == AttentionKind.WorkflowFailed })
    val waiting = delegations.active.filter { it.repoId in product.repositoryIds && delegations.statuses[it.id]?.stage?.needsYou == true }
    fun repo(id: Long) = repos.firstOrNull { it.id == id }
    fun ago(at: String) = relativeAge(at, now)?.let { if (it == "now") "just now" else "$it ago" }

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("This week", Modifier.weight(1f).semantics { heading() }, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            TextButton(enabled = !refreshing && repos.isNotEmpty(), onClick = {
                refreshing = true
                scope.launch { try { insights.refresh(product, repos, github::load, force = true) } finally { refreshing = false } }
            }) { Text(if (refreshing) "Checking…" else "Refresh") }
        }
        when {
            repos.isEmpty() -> Notice("No repositories linked", "Link repositories to see what merged, shipped and stalled.")
            week == null -> CenteredLoader("Checking this week…", Modifier.height(160.dp))
            else -> {
                if (week.incomplete) Text("Some GitHub data couldn't be loaded or was cut short, so this may be missing items.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                if (week.merged.isEmpty() && week.released.isEmpty() && failing.isEmpty() && week.idle.isEmpty() && waiting.isEmpty())
                    Notice("A quiet week", "Nothing merged or released in the last 7 days, and nothing is stuck.")
                WeekGroup("Merged", week.merged.size, StatusTone.Success) {
                    week.merged.take(8).forEach { item -> repo(item.repoId)?.let { r -> NavRow(item.title, listOfNotNull(item.repo, ago(item.at)).joinToString(" · ")) { onOpenRepo(r, item.route) } } }
                    if (week.merged.size > 8) Text("and ${week.merged.size - 8} more", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                WeekGroup("Released", week.released.size, StatusTone.Success) {
                    week.released.forEach { item -> repo(item.repoId)?.let { r -> NavRow(item.title, listOfNotNull(item.repo, item.detail.ifBlank { null }, ago(item.at)).joinToString(" · ")) { onOpenRepo(r, item.route) } } }
                }
                // Stuck: what needs someone to act, most urgent first.
                WeekGroup("Waiting for you", waiting.size, StatusTone.Pending) {
                    waiting.forEach { d -> repo(d.repoId)?.let { r ->
                        val status = delegations.statuses[d.id]
                        NavRow(d.title, listOfNotNull(r.name, status?.stage?.label).joinToString(" · "), status?.stage?.let { StatusLabel(it.tone, it.label) }) {
                            onOpenRepo(r, (status?.pull?.number ?: d.pullNumber)?.let { WorkspacePage.Pull(it).route } ?: WorkspacePage.Overview.route)
                        }
                    } }
                }
                WeekGroup("Failing", failing.size, StatusTone.Failure) {
                    failing.forEach { item -> repo(item.repositoryId)?.let { r ->
                        NavRow(item.title, listOfNotNull(r.name, item.kind.label, ago(item.updatedAt)).joinToString(" · ")) { onOpenRepo(r, attentionRoute(item) ?: WorkspacePage.Overview.route) }
                    } }
                }
                WeekGroup("Idle for a week or more", week.idle.size, StatusTone.Neutral) {
                    week.idle.take(6).forEach { item -> repo(item.repoId)?.let { r ->
                        NavRow(item.title, listOfNotNull(item.repo, item.detail.ifBlank { null }, relativeAge(item.at, now)?.let { "untouched for $it" }).joinToString(" · ")) { onOpenRepo(r, item.route) }
                    } }
                    if (week.idle.size > 6) Text("and ${week.idle.size - 6} more", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        if (repos.isNotEmpty()) ReleaseReadiness(github, repos, onOpenRepo)
    }
}

@Composable
private fun WeekGroup(title: String, count: Int, tone: StatusTone, content: @Composable ColumnScope.() -> Unit) {
    if (count == 0) return
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        StatusBadge(StatusLabel(tone, "$title · $count"))
        content()
    }
}

/** Each repository's unreleased commits and default-branch checks; tapping one opens its Releases page. */
@Composable
private fun ReleaseReadiness(github: GitHubApi, repos: List<Repo>, onOpenRepo: (Repo, String) -> Unit) {
    var readiness by remember(repos) { mutableStateOf<Map<Long, RepoReadiness>>(emptyMap()) }
    LaunchedEffect(repos) {
        for (repo in repos.take(12)) {
            val r = try { loadReadiness(repo, github::load) } catch (e: CancellationException) { throw e } catch (_: Exception) { RepoReadiness(repo, null, null, null, failed = true) }
            readiness = readiness + (repo.id to r)
        }
    }
    Text("Release readiness", Modifier.padding(top = 8.dp).semantics { heading() }, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
    repos.take(12).forEach { repo ->
        val r = readiness[repo.id]
        NavRow(repo.name, r?.let(::readinessLine) ?: "Checking…", r?.let { StatusLabel(readinessTone(it), readinessBadge(it)) }) {
            onOpenRepo(repo, WorkspacePage.Releases.route)
        }
    }
    if (repos.size > 12) Text("Showing the first 12 repositories.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

private fun readinessBadge(r: RepoReadiness): String = when (readinessTone(r)) {
    StatusTone.Failure -> "Failing"
    StatusTone.Pending -> "Running"
    StatusTone.Success -> "Ready"
    else -> when {
        r.latestTag == null && !r.failed -> "No release"
        r.unreleased == 0 -> "Up to date"
        else -> "Unknown"
    }
}

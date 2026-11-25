package no.vardir.varde

import android.util.Base64
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.Alignment
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import kotlinx.coroutines.CancellationException
import org.json.JSONArray
import org.json.JSONObject
import java.text.DateFormat
import java.util.Date

private data class WorkspaceLink(val title: String, val subtitle: String, val route: String)

@Composable
internal fun RepositoryWorkspace(vm: Workspace, repo: Repo, initialPage: String = "overview", onBack: () -> Unit) {
    var page by remember(repo.id) { mutableStateOf(initialPage) }
    var payload by remember { mutableStateOf<CachedResponse?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(false) }
    var generation by remember { mutableIntStateOf(0) }
    val history = remember(repo.id) { mutableStateListOf<String>() }
    var showHandover by remember { mutableStateOf(false) }
    var confirmRerun by remember { mutableStateOf(false) }
    var rerunning by remember { mutableStateOf(false) }
    var rerunMessage by remember { mutableStateOf<String?>(null) }
    var diffComments by remember { mutableStateOf<Map<String, List<String>>>(emptyMap()) }
    var diffCommentWarning by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    fun navigate(next: String) { history.add(page); page = next; rerunMessage = null }
    val uri = LocalUriHandler.current
    val base = repoApi(repo)
    val endpoint = when {
        page == "overview" -> base
        page == "commits" -> "$base/commits?per_page=30"
        page == "issues" -> "$base/issues?state=open&per_page=100"
        page == "pulls" -> "$base/pulls?state=open&per_page=100"
        page == "runs" -> "$base/actions/runs?per_page=50"
        page.startsWith("files:") -> "$base/contents/" + page.removePrefix("files:").split('/').joinToString("/") { pathPart(it) }
        page.startsWith("pull:") -> "$base/pulls/" + page.removePrefix("pull:")
        page.startsWith("changes:") -> "$base/pulls/" + page.removePrefix("changes:") + "/files?per_page=100"
        page.startsWith("reviewcomments:") -> "$base/pulls/" + page.removePrefix("reviewcomments:") + "/comments?per_page=100"
        page.startsWith("comments:") -> "$base/issues/" + page.removePrefix("comments:") + "/comments?per_page=100"
        page.startsWith("checks:") -> "$base/commits/" + pathPart(page.removePrefix("checks:")) + "/check-runs?per_page=100"
        page.startsWith("statuses:") -> "$base/commits/" + pathPart(page.removePrefix("statuses:")) + "/status"
        page.startsWith("run:") -> "$base/actions/runs/" + page.removePrefix("run:") + "/jobs?per_page=100"
        page.startsWith("log:") -> "$base/actions/jobs/" + page.removePrefix("log:").substringBefore(':') + "/logs"
        else -> base
    }
    LaunchedEffect(repo.id, page, generation) {
        loading = true; error = null; payload = null
        try { payload = vm.workspaceLoad(endpoint) }
        catch (e: CancellationException) { throw e }
        catch (e: Exception) { error = e.message ?: "Unable to load this view." }
        finally { loading = false }
    }
    LaunchedEffect(repo.id, page, generation) {
        diffComments = emptyMap()
        diffCommentWarning = null
        if (page.startsWith("changes:")) {
            try {
                val response = vm.workspaceLoad(base + "/pulls/" + page.substringAfter(':') + "/comments?per_page=100")
                val comments = JSONArray(response.body)
                diffComments = (0 until comments.length()).mapNotNull { comments.optJSONObject(it) }
                    .groupBy { jsonText(it, "path") }
                    .mapValues { (_, entries) -> entries.map {
                        val author = it.optJSONObject("user")?.optString("login").orEmpty()
                        val line = if (it.isNull("line")) "outdated line" else "line " + it.optInt("line")
                        "$author · $line\n" + jsonText(it, "body")
                    } }
                if (response.cached || comments.length() == 100) diffCommentWarning =
                    "Review comments may be cached or incomplete. Open GitHub for the full conversation."
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { diffCommentWarning = "Review comments couldn't be loaded." }
        }
    }
    fun back() {
        if (history.isNotEmpty()) { page = history.removeAt(history.lastIndex); return }
        page = when {
            page == "overview" -> { onBack(); return }
            page.startsWith("changes:") || page.startsWith("comments:") || page.startsWith("reviewcomments:") -> "pull:" + page.substringAfter(':')
            page.startsWith("statuses:") || page.startsWith("checks:") -> "pulls"
            page.startsWith("pull:") -> "pulls"
            page.startsWith("log:") -> "runs"
            page.startsWith("run:") -> "runs"
            page.startsWith("files:") && page.removePrefix("files:").contains('/') -> "files:" + page.removePrefix("files:").substringBeforeLast('/')
            else -> "overview"
        }
    }
    androidx.activity.compose.BackHandler { back() }
    if (showHandover) HandoverDialog(vm, repo, page, payload) { showHandover = false }
    if (confirmRerun) AlertDialog(
        onDismissRequest = { confirmRerun = false },
        title = { Text("Rerun failed jobs?") },
        text = { Text("GitHub will rerun failed jobs in this workflow run. This may consume Actions minutes.") },
        confirmButton = { TextButton(onClick = {
            confirmRerun = false
            val runId = page.substringAfter(':').toLongOrNull()
            if (runId != null) scope.launch {
                rerunning = true
                try {
                    vm.rerunFailedJobs(repo, runId)
                    rerunMessage = "GitHub accepted the rerun. Refresh to see the new attempt."
                } catch (e: CancellationException) { throw e }
                catch (e: Exception) { rerunMessage = e.message ?: "Rerun failed." }
                finally { rerunning = false }
            }
        }) { Text("Rerun failed jobs") } },
        dismissButton = { TextButton(onClick = { confirmRerun = false }) { Text("Cancel") } }
    )
    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background,
        contentColor = MaterialTheme.colorScheme.onSurface
    ) {
        LazyColumn(
            modifier = Modifier.fillMaxSize().safeDrawingPadding(),
            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            item {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TextButton(onClick = { back() }) { Text("‹  Back") }
                    TextButton(onClick = { generation++ }, enabled = !loading) { Text("Refresh") }
                }
                Spacer(Modifier.height(8.dp))
                Text(repo.owner.uppercase(), style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.height(4.dp))
                Text(repo.name, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                Text(workspacePageTitle(page), color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(14.dp))
            }
            if (page.startsWith("pull:") || page.startsWith("log:") || page.startsWith("run:") || page.startsWith("changes:")) item {
                OutlinedButton(onClick = { showHandover = true }, modifier = Modifier.fillMaxWidth()) { Text("Prepare agent handover") }
            }
            if (loading) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
            rerunMessage?.let { message -> item { WorkspaceNotice("Workflow rerun", message) } }
            error?.let { message -> item {
                WorkspaceNotice("Couldn't load this view", message, isError = true)
            } }
            payload?.let { response ->
                if (response.cached) item {
                    WorkspaceNotice("Offline copy", "Saved ${DateFormat.getDateTimeInstance().format(Date(response.savedAt))}")
                }
                if (page.startsWith("log:")) {
                    item { JobLogPanel(response.body) }
                } else {
                    val result = runCatching { workspaceLinks(page, response.body) }
                    val links = result.getOrDefault(emptyList())
                    if (result.isFailure) item {
                        WorkspaceNotice("Couldn't read this view", "The GitHub response could not be displayed.", isError = true)
                    }
                    if (page == "overview" && repo.description.isNotBlank()) item {
                        Text(repo.description, color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(bottom = 8.dp))
                    }
                    if (links.isEmpty() && result.isSuccess) item {
                        WorkspaceNotice("Nothing here yet", "There is nothing to show in this view.")
                    }
                    if (page.startsWith("pull:")) {
                        item { PullSummary(response.body) }
                        item { PullChecks(vm, repo, response.body) { navigate(it) } }
                    }
                    if (page.startsWith("run:") && hasFailedJobs(response.body)) item {
                        OutlinedButton(onClick = { confirmRerun = true }, enabled = !rerunning,
                            modifier = Modifier.fillMaxWidth()) { Text(if (rerunning) "Requesting rerun…" else "Rerun failed jobs") }
                    }
                    diffCommentWarning?.let { message -> item { WorkspaceNotice("Review comments", message) } }
                    items(links) { link ->
                        val canOpen = link.route.isNotBlank()
                        val longContent = page.startsWith("changes:") ||
                            (page.startsWith("files:") && link.route.startsWith("https://github.com/")) ||
                            page.startsWith("comments:") || page.startsWith("reviewcomments:") ||
                            (page.startsWith("pull:") && !canOpen)
                        if (page.startsWith("changes:")) {
                            DiffCard(link.title, link.subtitle, diffComments[link.title].orEmpty(), onOpen =
                                if (link.route.startsWith("https://github.com/")) ({ uri.openUri(link.route) }) else null)
                        } else if (longContent) {
                            WorkspaceTextBlock(link.title, link.subtitle,
                                monospace = page.startsWith("changes:") || page.startsWith("files:"),
                                markdown = page.startsWith("pull:") || page.startsWith("comments:") ||
                                    page.startsWith("reviewcomments:") ||
                                    (page.startsWith("files:") && link.title.endsWith(".md", ignoreCase = true)),
                                languageHint = if (page.startsWith("files:")) link.title else "",
                                onOpen = if (link.route.startsWith("https://github.com/")) ({ uri.openUri(link.route) }) else null)
                        } else {
                            WorkspaceRow(link, onClick = if (!canOpen) null else {
                                {
                                    if (link.route.startsWith("https://github.com/")) uri.openUri(link.route)
                                    else navigate(link.route)
                                }
                            })
                        }
                    }
                }
            }
        }
    }
}

private fun workspacePageTitle(page: String): String = when {
    page == "overview" -> "Repository overview"
    page == "commits" -> "Recent commits"
    page == "issues" -> "Open issues"
    page == "pulls" -> "Pull requests"
    page == "runs" -> "Workflow runs"
    page.startsWith("pull:") -> "Pull request #${page.substringAfter(':')}"
    page.startsWith("changes:") -> "Changed files"
    page.startsWith("reviewcomments:") -> "Review comments"
    page.startsWith("comments:") -> "Conversation"
    page.startsWith("checks:") -> "Checks"
    page.startsWith("statuses:") -> "Commit status"
    page.startsWith("run:") -> "Workflow jobs"
    page.startsWith("log:") -> "Job log"
    page.startsWith("files:") -> page.substringAfter(':').ifBlank { "Files" }
    else -> "Repository"
}

@Composable
private fun WorkspaceRow(link: WorkspaceLink, onClick: (() -> Unit)?) {
    val shape = RoundedCornerShape(14.dp)
    val background = MaterialTheme.colorScheme.surfaceVariant
    val body: @Composable () -> Unit = {
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(link.title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                if (link.subtitle.isNotBlank()) Text(link.subtitle, style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (onClick != null) Text("›", Modifier.padding(start = 12.dp),
                style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.primary)
        }
    }
    if (onClick == null) Surface(color = background, shape = shape, content = body)
    else Surface(onClick = onClick, color = background, shape = shape, content = body)
}

@Composable
private fun WorkspaceTextBlock(
    title: String, text: String, monospace: Boolean = false, markdown: Boolean = false,
    languageHint: String = "", onOpen: (() -> Unit)? = null
) {
    Surface(color = MaterialTheme.colorScheme.surfaceVariant, shape = RoundedCornerShape(14.dp)) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.primary)
            when {
                markdown -> WorkspaceMarkdown(text)
                monospace && languageHint.isNotBlank() -> WorkspaceCode(text, languageHint)
                else -> Text(text, style = MaterialTheme.typography.bodySmall,
                    fontFamily = if (monospace) FontFamily.Monospace else FontFamily.Default,
                    color = MaterialTheme.colorScheme.onSurface)
            }
            if (onOpen != null) TextButton(onClick = onOpen) { Text("Open on GitHub") }
        }
    }
}

@Composable
private fun WorkspaceNotice(title: String, message: String, isError: Boolean = false) {
    Column(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(14.dp))
        .padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold,
            color = if (isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary)
        Text(message, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

private fun workspaceLinks(page: String, body: String): List<WorkspaceLink> {
    if (page == "overview") {
        val details = JSONObject(body)
        return listOf(
        WorkspaceLink("Repository details", "Default branch: ${jsonText(details, "default_branch", "unknown")} · ${details.optInt("open_issues_count")} open issues and PRs", ""),

        WorkspaceLink("Commits", "Recent changes", "commits"), WorkspaceLink("Issues", "Open issues", "issues"),
        WorkspaceLink("Pull requests", "Review changes and checks", "pulls"), WorkspaceLink("Workflows", "Runs, jobs and logs", "runs"),
        WorkspaceLink("Files", "Browse repository files", "files:")
        )
    }
    val parsed = org.json.JSONTokener(body).nextValue()
    if (page.startsWith("pull:") && parsed is JSONObject) {
        val number = page.substringAfter(':')
        val sha = parsed.optJSONObject("head")?.optString("sha").orEmpty()
        return listOf(
            WorkspaceLink(jsonText(parsed, "title"), jsonText(parsed, "body", "No description"), ""),
            WorkspaceLink("Changed files", "View patches", "changes:$number"),
            WorkspaceLink("Comments", "Read conversation", "comments:$number"),
            WorkspaceLink("Review comments", "Read comments on changed lines", "reviewcomments:$number"),
            WorkspaceLink("Checks", "View GitHub check runs", "checks:$sha"),
            WorkspaceLink("Commit status", "View reported statuses for the head commit", "statuses:$sha")
        )
    }
    if (page.startsWith("files:") && parsed is JSONObject) {
        val content = jsonText(parsed, "content").replace("\n", "")
        if (content.isBlank()) return listOf(WorkspaceLink(jsonText(parsed, "name", "File"), "This file cannot be displayed here.", jsonText(parsed, "html_url")))
        val decoded = try { String(Base64.decode(content, Base64.DEFAULT), Charsets.UTF_8) } catch (_: Exception) { "Unable to decode file." }
        val display = if (decoded.contains('\u0000')) "Binary file; open on GitHub." else decoded.take(100_000) + if (decoded.length > 100_000) "\n\n[File truncated]" else ""
        return listOf(WorkspaceLink(jsonText(parsed, "name"), display, jsonText(parsed, "html_url")))
    }
    if (page.startsWith("checks:") && parsed is JSONObject) {
        val checks = parsed.optJSONArray("check_runs") ?: JSONArray()
        return (0 until checks.length()).map { i ->
            val o = checks.getJSONObject(i)
            val target = jsonText(o, "details_url").ifBlank { jsonText(o, "html_url") }
            WorkspaceLink(jsonText(o, "name"), "${jsonText(o, "status")} · ${jsonText(o, "conclusion")}",
                actionJobRoute(target) ?: target)
        }.ifEmpty { listOf(WorkspaceLink("No check runs", "No check runs were returned for this commit.", "")) }
    }
    if (page.startsWith("statuses:") && parsed is JSONObject) {
        val statuses = parsed.optJSONArray("statuses") ?: JSONArray()
        return (0 until statuses.length()).map { i -> val o = statuses.getJSONObject(i); WorkspaceLink(jsonText(o, "context"), "${jsonText(o, "state")} · ${jsonText(o, "description")}", jsonText(o, "target_url")) }
            .ifEmpty { listOf(WorkspaceLink("No commit statuses", "GitHub checks may be available on the pull request page.", "")) }
    }
    val array = when { page == "runs" && parsed is JSONObject -> parsed.optJSONArray("workflow_runs")
        page.startsWith("run:") && parsed is JSONObject -> parsed.optJSONArray("jobs")
        parsed is JSONArray -> parsed
        else -> null } ?: return emptyList()
    return (0 until array.length()).mapNotNull { i ->
        val o = array.optJSONObject(i) ?: return@mapNotNull null
        when {
            page == "commits" -> WorkspaceLink(o.optJSONObject("commit")?.optString("message")?.lineSequence()?.firstOrNull().orEmpty(), jsonText(o, "sha").take(8), jsonText(o, "html_url"))
            page == "issues" && o.has("pull_request") -> null
            page == "issues" -> WorkspaceLink("#${o.optInt("number")} ${jsonText(o, "title")}", "Open issue", jsonText(o, "html_url"))
            page == "pulls" -> WorkspaceLink("#${o.optInt("number")} ${jsonText(o, "title")}", jsonText(o, "state"), "pull:${o.optInt("number")}")
            page == "runs" -> WorkspaceLink(jsonText(o, "name", "Workflow"), "${jsonText(o, "status")} · ${jsonText(o, "conclusion")}", "run:${o.optLong("id")}")
            page.startsWith("run:") -> WorkspaceLink(jsonText(o, "name", "Job"), "${jsonText(o, "status")} · ${jsonText(o, "conclusion")}", "log:${o.optLong("id")}:${page.substringAfter(':')}")
            page.startsWith("files:") -> WorkspaceLink(jsonText(o, "name"), jsonText(o, "type"), "files:${jsonText(o, "path")}")
            page.startsWith("changes:") -> WorkspaceLink(jsonText(o, "filename"), jsonText(o, "patch", "Patch unavailable"), jsonText(o, "blob_url"))
            page.startsWith("comments:") || page.startsWith("reviewcomments:") -> WorkspaceLink(o.optJSONObject("user")?.optString("login").orEmpty(), jsonText(o, "body"), jsonText(o, "html_url"))
            else -> null
        }
    }
}

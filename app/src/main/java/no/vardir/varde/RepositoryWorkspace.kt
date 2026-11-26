package no.vardir.varde

import android.util.Base64
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.Alignment
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
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

private data class WorkspaceLink(val title: String, val subtitle: String, val route: String, val status: StatusLabel? = null)

@Composable
internal fun RepositoryWorkspace(github: GitHubApi, delegations: DelegationTracker, assistant: OnDeviceAssistant, dismissed: DismissedItems, repo: Repo, page: String, onNavigate: (String) -> Unit, onBack: () -> Unit) {
    var payload by remember { mutableStateOf<CachedResponse?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(true) }
    var generation by remember { mutableIntStateOf(0) }
    var showHandover by remember { mutableStateOf(false) }
    var confirmRerun by remember { mutableStateOf(false) }
    var writeKind by remember { mutableStateOf<WriteKind?>(null) }
    var writeMessage by remember { mutableStateOf<String?>(null) }
    var rerunning by remember { mutableStateOf(false) }
    var rerunResult by remember { mutableStateOf<Pair<NoticeKind, String>?>(null) }
    // The overview also shows a README preview; a missing README just means no card.
    var readme by remember(repo.id) { mutableStateOf<Readme?>(null) }
    LaunchedEffect(repo.id, page, generation) {
        if (page != "overview") return@LaunchedEffect
        readme = try { parseReadme(github.load(repoApi(repo) + "/readme").body) } catch (e: CancellationException) { throw e } catch (_: Exception) { null }
    }
    var diffComments by remember { mutableStateOf<Map<String, List<String>>>(emptyMap()) }
    var diffCommentWarning by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    fun navigate(next: String) = onNavigate(next)
    val uri = LocalUriHandler.current
    val base = repoApi(repo)
    val endpoint = when {
        page == "overview" -> base
        page == "readme" -> "$base/readme"
        page == "docs" -> "$base/git/trees/HEAD?recursive=1"
        page == "commits" -> "$base/commits?per_page=30"
        page == "issues" -> "$base/issues?state=open&per_page=100"
        page.startsWith("issue:") -> "$base/issues/" + page.removePrefix("issue:")
        page.startsWith("issuecomments:") -> "$base/issues/" + page.removePrefix("issuecomments:") + "/comments?per_page=100"
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
        try { payload = github.load(endpoint) }
        catch (e: CancellationException) { throw e }
        catch (e: Exception) { error = e.message ?: "Unable to load this view." }
        finally { loading = false }
    }
    LaunchedEffect(repo.id, page, generation) {
        diffComments = emptyMap()
        diffCommentWarning = null
        if (page.startsWith("changes:")) {
            try {
                val response = github.load(base + "/pulls/" + page.substringAfter(':') + "/comments?per_page=100")
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
    writeKind?.let { kind ->
        val number = page.substringAfter(':').toIntOrNull()
        val data = payload
        if (number != null && data != null) {
            val sha = runCatching { JSONObject(data.body).optJSONObject("head")?.optString("sha").orEmpty() }.getOrDefault("")
            GitHubActionDialog(github, assistant, repo, number, kind, sha, page.startsWith("pull:"),
                onDismiss = { writeKind = null; generation++ }, onSuccess = {
                    writeKind = null; writeMessage = "GitHub accepted the action."; generation++
                })
        }
    }
    if (showHandover) HandoverDialog(github, delegations, assistant, repo, page, payload) { showHandover = false }
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
                    github.rerunFailedJobs(repo, runId)
                    rerunResult = NoticeKind.Success to "GitHub accepted the rerun. Refresh to see the new attempt."
                } catch (e: CancellationException) { throw e }
                catch (e: Exception) { rerunResult = NoticeKind.Error to (e.message ?: "Rerun failed.") }
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
      // Links to other docs in this repository open here instead of in the browser.
      CompositionLocalProvider(LocalInAppLink provides { url ->
          repoBlobPath(url.substringBefore('#'), repo.owner, repo.name)?.takeIf { isDocPath(it) }?.let { navigate("files:$it"); true } ?: false
      }) {
      Column(Modifier.fillMaxSize()) {
        DetailTopBar(if (page == "overview") repo.name else workspacePageTitle(page), onBack,
            eyebrow = if (page == "overview") repo.owner else "${repo.owner} / ${repo.name}", actions = {
            IconButton(onClick = { generation++ }, enabled = !loading) {
                Icon(Icons.Default.Refresh, contentDescription = "Refresh")
            }
        })
        if (loading && payload == null && error == null) CenteredLoader(loadingLabel(page))
        else LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = ScreenGutter, end = ScreenGutter, bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            if (page.startsWith("log:") || page.startsWith("changes:")) item {
                OutlinedButton(onClick = { showHandover = true }, modifier = Modifier.fillMaxWidth()) { Text("Prepare agent handover") }
            }
            writeMessage?.let { message -> item { Notice("GitHub action", message, NoticeKind.Success) } }
            error?.let { message -> item {
                if (page == "readme" && message.contains("404")) Notice("No README", "This repository has no README on its default branch.")
                else Notice("Couldn't load this view", message, NoticeKind.Error)
            } }
            payload?.let { response ->
                if (response.cached) item {
                    Notice("Offline copy", "Saved ${DateFormat.getDateTimeInstance().format(Date(response.savedAt))}")
                }
                if (page.startsWith("log:")) {
                    item { JobLogPanel(response.body, assistant) }
                } else if (page == "docs") {
                    item {
                        val index = runCatching { parseDocIndex(response.body) }.getOrNull()
                        if (index == null) Notice("Couldn't list docs", "Use Files to browse the repository.", NoticeKind.Warning)
                        else DocsList(index) { doc -> navigate("files:${doc.path}") }
                    }
                } else if (page == "readme") {
                    val parsed = parseReadme(response.body)
                    item {
                        if (parsed == null) Notice("Couldn't show this README", "It may be binary or too unusual to display. Open it on GitHub.", NoticeKind.Warning)
                        else VardeCard {
                            ReadmeContent(parsed)
                            if (parsed.htmlUrl.startsWith("https://github.com/")) TextButton(onClick = { runCatching { uri.openUri(parsed.htmlUrl) } }) { Text("Open on GitHub") }
                        }
                    }
                } else if (page.startsWith("pull:")) {
                    val pr = runCatching { parsePullRequest(response.body) }.getOrNull()
                    if (pr == null) item { Notice("Couldn't read this pull request", "The GitHub response could not be displayed.", NoticeKind.Error) }
                    else item(key = "pull:${pr.number}") {
                        PullRequestPage(github, repo, pr, response.cached, onNavigate = { navigate(it) },
                            onAction = { writeKind = it }, onHandover = { showHandover = true },
                            onOpenUrl = { runCatching { uri.openUri(it) } })
                    }
                } else {
                    val result = runCatching { workspaceLinks(page, response.body) }
                    val links = result.getOrDefault(emptyList())
                    if (result.isFailure) item {
                        Notice("Couldn't read this view", "The GitHub response could not be displayed.", NoticeKind.Error)
                    }
                    if (page == "overview" && repo.description.isNotBlank()) item {
                        Text(repo.description, color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(bottom = 8.dp))
                    }
                    if (links.isEmpty() && result.isSuccess) item {
                        Notice("Nothing here yet", "There is nothing to show in this view.")
                    }
                    // Overview: the starting point for handing a new task about this repository to an agent.
                    if (page == "overview") item {
                        OutlinedButton(onClick = { showHandover = true }, modifier = Modifier.fillMaxWidth()) { Text("Hand a task to an agent") }
                    }
                    if (page.startsWith("issue:")) item {
                        OutlinedButton(onClick = { showHandover = true }, modifier = Modifier.fillMaxWidth()) { Text("Hand this issue to an agent") }
                    }
                    if (page.startsWith("issue:") && !response.cached) item {
                        OutlinedButton(onClick = { writeKind = WriteKind.COMMENT }, modifier = Modifier.fillMaxWidth()) { Text("Add comment") }
                    }
                    if (page.startsWith("issue:") && response.cached) item {
                        Notice("Actions unavailable offline", "Reconnect and refresh before submitting GitHub actions.")
                    }
                    diffCommentWarning?.let { message -> item { Notice("Review comments", message) } }
                    items(links) { link ->
                        val canOpen = link.route.isNotBlank()
                        val longContent = page.startsWith("changes:") ||
                            (page.startsWith("files:") && link.route.startsWith("https://github.com/")) ||
                            page.startsWith("comments:") || page.startsWith("reviewcomments:") || page.startsWith("issuecomments:") ||
                            ((page.startsWith("pull:") || page.startsWith("issue:")) && !canOpen)
                        if (page.startsWith("changes:")) {
                            DiffCard(link.title, link.subtitle, diffComments[link.title].orEmpty(), onOpen =
                                if (link.route.startsWith("https://github.com/")) ({ uri.openUri(link.route) }) else null)
                        } else if (longContent) {
                            WorkspaceTextBlock(link.title, link.subtitle,
                                monospace = page.startsWith("changes:") || page.startsWith("files:"),
                                markdown = page.startsWith("pull:") || page.startsWith("issue:") || page.startsWith("issuecomments:") || page.startsWith("comments:") ||
                                    page.startsWith("reviewcomments:") ||
                                    (page.startsWith("files:") && link.title.substringAfterLast('.', "").lowercase() in setOf("md", "markdown", "mdx")),
                                languageHint = if (page.startsWith("files:")) link.title else "",
                                linkBase = if (page.startsWith("files:") && link.route.startsWith("https://github.com/")) link.route else null,
                                onOpen = if (link.route.startsWith("https://github.com/")) ({ uri.openUri(link.route) }) else null)
                        } else {
                            NavRow(link.title, link.subtitle, link.status, onClick = if (!canOpen) null else {
                                {
                                    if (link.route.startsWith("https://github.com/")) uri.openUri(link.route)
                                    else navigate(link.route)
                                }
                            })
                        }
                    }
                    if (page == "overview") readme?.let { r -> item(key = "readme") { ReadmePreviewCard(r) { navigate("readme") } } }
                    // Run page: jobs first, then what you can do about them, with the result next to the button.
                    if (page.startsWith("run:")) {
                        item { SectionHeader("Act") }
                        if (hasFailedJobs(response.body)) item {
                            Button(onClick = { confirmRerun = true }, enabled = !rerunning && !response.cached,
                                modifier = Modifier.fillMaxWidth()) { Text(if (rerunning) "Requesting rerun…" else "Rerun failed jobs") }
                        }
                        rerunResult?.let { (kind, message) -> item {
                            Notice(if (kind == NoticeKind.Error) "Rerun not started" else "Rerun requested", message, kind)
                        } }
                        item { OutlinedButton(onClick = { showHandover = true }, modifier = Modifier.fillMaxWidth()) { Text("Prepare agent handover") } }
                        page.substringAfter(':').toLongOrNull()?.let { runId ->
                            item { SectionHeader("Not going to fix it?") }
                            item { RunActions(github, dismissed, repo, runId, onDeleted = onBack) }
                        }
                    }
                }
            }
        }
      }
      }
    }
}

internal fun workspacePageTitle(page: String): String = when {
    page == "overview" -> "Repository overview"
    page == "readme" -> "README"
    page == "docs" -> "Docs"
    page == "commits" -> "Recent commits"
    page == "issues" -> "Open issues"
    page.startsWith("issue:") -> "Issue #" + page.substringAfter(':')
    page.startsWith("issuecomments:") -> "Issue conversation"
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
private fun WorkspaceTextBlock(
    title: String, text: String, monospace: Boolean = false, markdown: Boolean = false,
    languageHint: String = "", linkBase: String? = null, onOpen: (() -> Unit)? = null
) {
    VardeCard {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.primary)
            when {
                markdown -> WorkspaceMarkdown(text, linkBase)
                monospace && languageHint.isNotBlank() -> WorkspaceCode(text, languageHint)
                else -> Text(text, style = MaterialTheme.typography.bodySmall,
                    fontFamily = if (monospace) FontFamily.Monospace else FontFamily.Default,
                    color = MaterialTheme.colorScheme.onSurface)
            }
            if (onOpen != null) TextButton(onClick = onOpen) { Text("Open on GitHub") }
        }
    }
}

private fun workspaceLinks(page: String, body: String): List<WorkspaceLink> {
    if (page == "overview") {
        val details = JSONObject(body)
        return listOf(
        WorkspaceLink("Repository details", "README · default branch ${jsonText(details, "default_branch", "unknown")} · ${details.optInt("open_issues_count")} open issues and PRs", "readme"),

        WorkspaceLink("Commits", "Recent changes", "commits"), WorkspaceLink("Issues", "Open issues", "issues"),
        WorkspaceLink("Pull requests", "Review changes and checks", "pulls"), WorkspaceLink("Workflows", "Runs, jobs and logs", "runs"),
        WorkspaceLink("Docs", "README, guides and other markdown documents", "docs"),
        WorkspaceLink("Files", "Browse repository files", "files:")
        )
    }
    val parsed = org.json.JSONTokener(body).nextValue()
    if (page.startsWith("issue:") && parsed is JSONObject) {
        val number = page.substringAfter(':')
        return listOf(
            WorkspaceLink(jsonText(parsed, "title"), jsonText(parsed, "body", "No description"), ""),
            WorkspaceLink("Comments", "Read conversation", "issuecomments:$number")
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
            WorkspaceLink(jsonText(o, "name"), "", actionJobRoute(target) ?: target,
                ciStatus(jsonText(o, "status"), jsonText(o, "conclusion")))
        }.ifEmpty { listOf(WorkspaceLink("No check runs", "No check runs were returned for this commit.", "")) }
    }
    if (page.startsWith("statuses:") && parsed is JSONObject) {
        val statuses = parsed.optJSONArray("statuses") ?: JSONArray()
        return (0 until statuses.length()).map { i -> val o = statuses.getJSONObject(i); WorkspaceLink(jsonText(o, "context"), jsonText(o, "description"), jsonText(o, "target_url"), commitStatus(jsonText(o, "state"))) }
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
            page == "issues" -> WorkspaceLink("#${o.optInt("number")} ${jsonText(o, "title")}", "Open issue", "issue:${o.optInt("number")}")
            page == "pulls" -> WorkspaceLink("#${o.optInt("number")} ${jsonText(o, "title")}", o.optJSONObject("user")?.optString("login").orEmpty(),
                "pull:${o.optInt("number")}", pullState(jsonText(o, "state"), o.optBoolean("draft"), false))
            page == "runs" -> WorkspaceLink(jsonText(o, "name", "Workflow"), listOf(jsonText(o, "head_branch"), jsonText(o, "event")).filter { it.isNotBlank() }.joinToString(" · "),
                "run:${o.optLong("id")}", ciStatus(jsonText(o, "status"), jsonText(o, "conclusion")))
            page.startsWith("run:") -> WorkspaceLink(jsonText(o, "name", "Job"), "", "log:${o.optLong("id")}:${page.substringAfter(':')}",
                ciStatus(jsonText(o, "status"), jsonText(o, "conclusion")))
            page.startsWith("files:") -> WorkspaceLink(jsonText(o, "name"), jsonText(o, "type"), "files:${jsonText(o, "path")}")
            page.startsWith("changes:") -> WorkspaceLink(jsonText(o, "filename"), jsonText(o, "patch", "Patch unavailable"), jsonText(o, "blob_url"))
            page.startsWith("comments:") || page.startsWith("reviewcomments:") || page.startsWith("issuecomments:") -> WorkspaceLink(o.optJSONObject("user")?.optString("login").orEmpty(), jsonText(o, "body"), jsonText(o, "html_url"))
            else -> null
        }
    }
}

private fun loadingLabel(page: String): String = when {
    page == "overview" -> "Loading repository…"
    page == "readme" -> "Loading README…"
    page == "docs" -> "Finding docs…"
    page.startsWith("pull:") -> "Loading pull request #${page.substringAfter(':')}…"
    page.startsWith("issue:") -> "Loading issue #${page.substringAfter(':')}…"
    page.startsWith("log:") -> "Loading job log…"
    page.startsWith("files:") -> "Loading files…"
    else -> "Loading ${workspacePageTitle(page).lowercase()}…"
}

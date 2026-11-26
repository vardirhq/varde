package no.vardir.varde

import android.util.Base64
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.text.DateFormat
import java.util.Date

private data class WorkspaceLink(val title: String, val subtitle: String, val route: String, val status: StatusLabel? = null)

/** A diff line someone wants to comment on, with its file. */
private data class LineComment(val path: String, val line: DiffLine)

@Composable
internal fun RepositoryWorkspace(github: GitHubApi, delegations: DelegationTracker, assistant: OnDeviceAssistant, dismissed: DismissedItems, repo: Repo, route: String, onNavigate: (String) -> Unit, onBack: () -> Unit) {
    val page = remember(route) { WorkspacePage.parse(route) }
    if (page == null) {
        Column(Modifier.fillMaxSize()) {
            DetailTopBar("Page not found", onBack, eyebrow = "${repo.owner} / ${repo.name}")
            Notice("This page can't be opened", "The link may be from an older version of Varde.", NoticeKind.Warning, Modifier.padding(horizontal = ScreenGutter))
        }
        return
    }
    var payload by remember { mutableStateOf<CachedResponse?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(true) }
    var generation by remember { mutableIntStateOf(0) }
    var showHandover by remember { mutableStateOf(false) }
    var confirmRerun by remember { mutableStateOf(false) }
    var runWorkflow by remember { mutableStateOf(false) }
    var writeKind by remember { mutableStateOf<WriteKind?>(null) }
    var lineComment by remember { mutableStateOf<LineComment?>(null) }
    // The result of the last GitHub action on this page, shown at the top.
    var notice by remember(route) { mutableStateOf<Pair<NoticeKind, String>?>(null) }
    var rerunning by remember { mutableStateOf(false) }
    // The overview also shows a README preview; a missing README just means no card.
    var readme by remember(repo.id) { mutableStateOf<Readme?>(null) }
    var diffComments by remember { mutableStateOf<Map<String, List<String>>>(emptyMap()) }
    var diffCommentWarning by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val uri = LocalUriHandler.current
    fun navigate(next: String) = onNavigate(next)
    fun done(message: String) { notice = NoticeKind.Success to message; generation++ }
    val source = page.source(repo)

    LaunchedEffect(repo.id, route, generation) {
        loading = true; error = null; payload = null
        try { payload = if (source.all) github.loadAll(source.path, source.maxItems) else github.load(source.path) }
        catch (e: CancellationException) { throw e }
        catch (e: Exception) { error = e.message ?: "Unable to load this view." }
        finally { loading = false }
    }
    LaunchedEffect(repo.id, route, generation) {
        if (page != WorkspacePage.Overview) return@LaunchedEffect
        readme = try { parseReadme(github.load(repoApi(repo) + "/readme").body) } catch (e: CancellationException) { throw e } catch (_: Exception) { null }
    }
    LaunchedEffect(repo.id, route, generation) {
        diffComments = emptyMap()
        diffCommentWarning = null
        if (page is WorkspacePage.Changes) {
            try {
                val response = github.loadAll(WorkspacePage.ReviewComments(page.number).source(repo).path)
                val comments = JSONArray(response.body)
                diffComments = (0 until comments.length()).mapNotNull { comments.optJSONObject(it) }
                    .groupBy { jsonText(it, "path") }
                    .mapValues { (_, entries) -> entries.map {
                        val author = it.optJSONObject("user")?.optString("login").orEmpty()
                        val line = if (it.isNull("line")) "outdated line" else "line " + it.optInt("line")
                        "$author · $line\n" + jsonText(it, "body")
                    } }
                if (response.cached || response.truncated) diffCommentWarning =
                    "Review comments may be cached or incomplete. Open GitHub for the full conversation."
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { diffCommentWarning = "Review comments couldn't be loaded." }
        }
    }

    // Dialogs
    writeKind?.let { kind ->
        val number = page.itemNumber
        val data = payload
        if (number != null && data != null) {
            val sha = runCatching { JSONObject(data.body).optJSONObject("head")?.optString("sha").orEmpty() }.getOrDefault("")
            GitHubActionDialog(github, assistant, repo, number, kind, sha, page is WorkspacePage.Pull,
                onDismiss = { writeKind = null; generation++ }, onSuccess = { writeKind = null; done("GitHub accepted the action.") })
        }
    }
    lineComment?.let { target ->
        val number = page.itemNumber ?: return@let
        val side = if (target.line.side == DiffSide.LEFT) "removed" else "new"
        ConfirmedWriteDialog("Comment on ${target.path.substringAfterLast('/')}:${target.line.line}",
            "Posts a review comment on $side line ${target.line.line} of ${target.path} in #$number:\n\n${target.line.text.take(200)}",
            "Post comment", onDismiss = { lineComment = null }, onDone = { lineComment = null; done(it) }, field = WriteField("Comment")) { body ->
            // Comments attach to the pull request's current head commit, checked online right before posting.
            val pr = github.load(repoApi(repo) + "/pulls/$number")
            check(!pr.cached) { "Can't check the pull request offline. Reconnect and try again." }
            val head = parsePullRequest(pr.body)
            check(head.open) { "#$number is no longer open." }
            github.send("POST", repoApi(repo) + "/pulls/$number/comments", "Posting the comment", JSONObject().put("body", body)
                .put("commit_id", head.headSha).put("path", target.path).put("line", target.line.line).put("side", target.line.side?.name))
            "Comment posted on ${target.path.substringAfterLast('/')} line ${target.line.line}."
        }
    }
    if (runWorkflow) RunWorkflowDialog(github, repo, onDismiss = { runWorkflow = false }, onStarted = { runWorkflow = false; done(it) })
    if (showHandover) HandoverDialog(github, delegations, assistant, repo, route, payload) { showHandover = false }
    if (confirmRerun) AlertDialog(
        onDismissRequest = { confirmRerun = false },
        title = { Text("Rerun failed jobs?") },
        text = { Text("GitHub will rerun failed jobs in this workflow run. This may consume Actions minutes.") },
        confirmButton = { TextButton(onClick = {
            confirmRerun = false
            val runId = (page as? WorkspacePage.Run)?.id
            if (runId != null) scope.launch {
                rerunning = true
                try {
                    github.rerunFailedJobs(repo, runId)
                    notice = NoticeKind.Success to "GitHub accepted the rerun. Refresh to see the new attempt."
                } catch (e: CancellationException) { throw e }
                catch (e: Exception) { notice = NoticeKind.Error to (e.message ?: "Rerun failed.") }
                finally { rerunning = false }
            }
        }) { Text("Rerun failed jobs") } },
        dismissButton = { TextButton(onClick = { confirmRerun = false }) { Text("Cancel") } }
    )

    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background, contentColor = MaterialTheme.colorScheme.onSurface) {
      // Links to other docs in this repository open here instead of in the browser.
      val images = remember(repo.id) { RepoImageLoader(github, repo) }
      CompositionLocalProvider(LocalRepoImages provides images, LocalInAppLink provides { url ->
          repoBlobPath(url.substringBefore('#'), repo.owner, repo.name)?.takeIf { isDocPath(it) }?.let { navigate(WorkspacePage.Files(it).route); true } ?: false
      }) {
      Column(Modifier.fillMaxSize()) {
        DetailTopBar(if (page == WorkspacePage.Overview) repo.name else page.title, onBack,
            eyebrow = if (page == WorkspacePage.Overview) repo.owner else "${repo.owner} / ${repo.name}", actions = {
            IconButton(onClick = { generation++ }, enabled = !loading) { Icon(Icons.Default.Refresh, contentDescription = "Refresh") }
        })
        if (loading && payload == null && error == null) CenteredLoader(page.loadingLabel)
        else LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = ScreenGutter, end = ScreenGutter, bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            if (page is WorkspacePage.Log || page is WorkspacePage.Changes) item {
                OutlinedButton(onClick = { showHandover = true }, modifier = Modifier.fillMaxWidth()) { Text("Prepare agent handover") }
            }
            notice?.let { (kind, message) -> item { Notice(if (kind == NoticeKind.Error) "Not done" else "Done", message, kind) } }
            error?.let { message -> item {
                if (page == WorkspacePage.Readme && message.contains("404")) Notice("No README", "This repository has no README on its default branch.")
                else Notice("Couldn't load this view", message, NoticeKind.Error)
            } }
            payload?.let { response ->
                if (response.cached) item { Notice("Offline copy", "Saved ${DateFormat.getDateTimeInstance().format(Date(response.savedAt))}") }
                if (response.truncated) item {
                    Notice("Not everything is shown", "This list is longer than Varde loads at once. Open it on GitHub for the rest.", NoticeKind.Warning)
                }
                when (page) {
                    is WorkspacePage.Log -> item { JobLogPanel(response.body, assistant) }
                    WorkspacePage.Docs -> item {
                        val index = runCatching { parseDocIndex(response.body) }.getOrNull()
                        if (index == null) Notice("Couldn't list docs", "Use Files to browse the repository.", NoticeKind.Warning)
                        else DocsList(index) { doc -> navigate(WorkspacePage.Files(doc.path).route) }
                    }
                    WorkspacePage.Readme -> item {
                        val parsed = parseReadme(response.body)
                        if (parsed == null) Notice("Couldn't show this README", "It may be binary or too unusual to display. Open it on GitHub.", NoticeKind.Warning)
                        else VardeCard {
                            ReadmeContent(parsed)
                            if (parsed.htmlUrl.startsWith("https://github.com/")) TextButton(onClick = { runCatching { uri.openUri(parsed.htmlUrl) } }) { Text("Open on GitHub") }
                        }
                    }
                    is WorkspacePage.Builds -> item(key = "builds") { BuildsPage(github, repo, response.body, response.cached, page.sha) }
                    WorkspacePage.Releases -> item(key = "releases") { ReleasesPage(github, assistant, repo, response.body, response.cached, onChanged = ::done) }
                    is WorkspacePage.Pull -> {
                        val pr = runCatching { parsePullRequest(response.body) }.getOrNull()
                        if (pr == null) item { Notice("Couldn't read this pull request", "The GitHub response could not be displayed.", NoticeKind.Error) }
                        else item(key = "pull:${pr.number}") {
                            PullRequestPage(github, repo, pr, response.cached, onNavigate = { navigate(it) },
                                onAction = { writeKind = it }, onHandover = { showHandover = true },
                                onOpenUrl = { runCatching { uri.openUri(it) } }, onChanged = ::done)
                        }
                    }
                    is WorkspacePage.Files -> fileItems(github, repo, page, response.body) { navigate(it) }
                    else -> linkItems(page, response, repo, readme, diffComments, diffCommentWarning, rerunning,
                        onNavigate = { navigate(it) }, onOpenUrl = { runCatching { uri.openUri(it) } },
                        onHandover = { showHandover = true }, onComment = { writeKind = WriteKind.COMMENT }, onRerun = { confirmRerun = true },
                        onRunWorkflow = { runWorkflow = true }, onCommentLine = if (response.cached) null else { path, line -> lineComment = LineComment(path, line) },
                        runBuilds = { runId -> item(key = "run-builds") { RunBuilds(github, repo, runId) } },
                        runExtras = { runId ->
                            item { CancelRun(github, repo, runId, onDone = ::done) }
                            item { SectionHeader("Not going to fix it?") }
                            item { RunActions(github, dismissed, repo, runId, onDeleted = onBack) }
                        })
                }
            }
        }
      }
      }
    }
}

/** A file shows as text, markdown, code or an image; a folder as a list. Files over 1 MB are fetched raw. */
private fun LazyListScope.fileItems(github: GitHubApi, repo: Repo, page: WorkspacePage.Files, body: String, onNavigate: (String) -> Unit) {
    val parsed = runCatching { org.json.JSONTokener(body).nextValue() }.getOrNull()
    if (parsed is JSONArray) {
        val entries = (0 until parsed.length()).mapNotNull { parsed.optJSONObject(it) }
            // Folders first, like GitHub.
            .sortedWith(compareBy<JSONObject> { jsonText(it, "type") != "dir" }.thenBy { jsonText(it, "name").lowercase() })
        if (entries.isEmpty()) item { Notice("Empty folder") }
        items(entries, key = { jsonText(it, "path") }) { o ->
            NavRow((if (jsonText(o, "type") == "dir") "📁 " else "") + jsonText(o, "name"), jsonText(o, "type").takeIf { it != "file" && it != "dir" }.orEmpty()) {
                onNavigate(WorkspacePage.Files(jsonText(o, "path")).route)
            }
        }
        return
    }
    val file = parsed as? JSONObject ?: run { item { Notice("Couldn't read this file", "The GitHub response could not be displayed.", NoticeKind.Error) }; return }
    val name = jsonText(file, "name", page.path.substringAfterLast('/'))
    val htmlUrl = jsonText(file, "html_url").takeIf { it.startsWith("https://github.com/") }
    item(key = "file") {
        val uri = LocalUriHandler.current
        val content = jsonText(file, "content").replace("\n", "")
        VardeCard {
            Text(name, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.primary)
            when {
                isRasterImage(name) && htmlUrl != null -> MarkdownImages(listOf(MarkdownImageRef(htmlUrl, name)), null)
                content.isNotBlank() -> FileText(name, decodeFileContent(content), htmlUrl)
                jsonText(file, "type") == "file" -> LargeFile(github, repo, page.path, name, htmlUrl)
                else -> Text("This item can't be displayed here.", style = MaterialTheme.typography.bodySmall)
            }
            if (htmlUrl != null) TextButton(onClick = { runCatching { uri.openUri(htmlUrl) } }) { Text("Open on GitHub") }
        }
    }
}

/** Text of a base64 file from the contents API, or a note when it's binary or can't be decoded. */
internal fun decodeFileContent(content: String, maxChars: Int = 100_000): String {
    val decoded = try { String(Base64.decode(content, Base64.DEFAULT), Charsets.UTF_8) } catch (_: Exception) { return "Unable to decode file." }
    return displayableText(decoded, maxChars)
}

internal fun displayableText(text: String, maxChars: Int = 100_000): String =
    if (text.contains('\u0000')) "Binary file; open it on GitHub." else text.take(maxChars) + if (text.length > maxChars) "\n\n[File truncated]" else ""

@Composable
private fun FileText(name: String, text: String, htmlUrl: String?) {
    when {
        name.substringAfterLast('.', "").lowercase() in setOf("md", "markdown", "mdx") -> WorkspaceMarkdown(text, htmlUrl)
        else -> WorkspaceCode(text, name)
    }
}

/** The contents API leaves out files over 1 MB; those are fetched raw, up to a size a phone can show. */
@Composable
private fun LargeFile(github: GitHubApi, repo: Repo, path: String, name: String, htmlUrl: String?) {
    var text by remember(path) { mutableStateOf<String?>(null) }
    var failed by remember(path) { mutableStateOf<String?>(null) }
    LaunchedEffect(repo.id, path) {
        try { text = displayableText(String(github.rawFile(repo, path, maxBytes = 3_000_000), Charsets.UTF_8)) }
        catch (e: CancellationException) { throw e }
        catch (e: Exception) { failed = e.message ?: "Couldn't load this file." }
    }
    when {
        failed != null -> Text(failed.orEmpty() + " Open it on GitHub instead.", style = MaterialTheme.typography.bodySmall)
        text == null -> LinearProgressIndicator(Modifier.fillMaxWidth())
        else -> FileText(name, text.orEmpty(), htmlUrl)
    }
}

/** Every list-shaped page, plus the overview, issue and run pages that are built from rows and buttons. */
private fun LazyListScope.linkItems(
    page: WorkspacePage, response: CachedResponse, repo: Repo, readme: Readme?, diffComments: Map<String, List<String>>,
    diffCommentWarning: String?, rerunning: Boolean, onNavigate: (String) -> Unit, onOpenUrl: (String) -> Unit,
    onHandover: () -> Unit, onComment: () -> Unit, onRerun: () -> Unit, onRunWorkflow: () -> Unit,
    onCommentLine: ((String, DiffLine) -> Unit)?, runBuilds: LazyListScope.(Long) -> Unit, runExtras: LazyListScope.(Long) -> Unit,
) {
    val result = runCatching { workspaceLinks(page, response.body) }
    val links = result.getOrDefault(emptyList())
    if (result.isFailure) item { Notice("Couldn't read this view", "The GitHub response could not be displayed.", NoticeKind.Error) }
    if (page == WorkspacePage.Overview && repo.description.isNotBlank()) item {
        Text(repo.description, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(bottom = 8.dp))
    }
    if (page == WorkspacePage.Runs && !response.cached) item {
        OutlinedButton(onClick = onRunWorkflow, modifier = Modifier.fillMaxWidth()) { Text("Run a workflow…") }
    }
    if (links.isEmpty() && result.isSuccess) item { Notice("Nothing here yet", "There is nothing to show in this view.") }
    // Overview: the starting point for handing a new task about this repository to an agent.
    if (page == WorkspacePage.Overview) item {
        OutlinedButton(onClick = onHandover, modifier = Modifier.fillMaxWidth()) { Text("Hand a task to an agent") }
    }
    if (page is WorkspacePage.Issue) {
        item { OutlinedButton(onClick = onHandover, modifier = Modifier.fillMaxWidth()) { Text("Hand this issue to an agent") } }
        if (response.cached) item { Notice("Actions unavailable offline", "Reconnect and refresh before submitting GitHub actions.") }
        else item { OutlinedButton(onClick = onComment, modifier = Modifier.fillMaxWidth()) { Text("Add comment") } }
    }
    diffCommentWarning?.let { message -> item { Notice("Review comments", message) } }
    val markdown = page is WorkspacePage.Issue || page is WorkspacePage.IssueComments || page is WorkspacePage.Comments || page is WorkspacePage.ReviewComments
    items(links) { link ->
        val external = link.route.startsWith("https://github.com/")
        when {
            page is WorkspacePage.Changes -> DiffCard(link.title, link.subtitle, diffComments[link.title].orEmpty(),
                onOpen = if (external) ({ onOpenUrl(link.route) }) else null,
                onCommentLine = onCommentLine?.let { comment -> { line: DiffLine -> comment(link.title, line) } })
            markdown && (link.route.isBlank() || external) -> WorkspaceTextBlock(link.title, link.subtitle, markdown = true,
                onOpen = if (external) ({ onOpenUrl(link.route) }) else null)
            else -> NavRow(link.title, link.subtitle, link.status, onClick = if (link.route.isBlank()) null else {
                { if (external) onOpenUrl(link.route) else onNavigate(link.route) }
            })
        }
    }
    if (page == WorkspacePage.Overview) readme?.let { r -> item(key = "readme") { ReadmePreviewCard(r) { onNavigate(WorkspacePage.Readme.route) } } }
    // Run page: jobs first, then what you can do about them.
    if (page is WorkspacePage.Run) {
        // Builds first: for a green run, installing what it built is usually why you're here.
        if (!response.cached) runBuilds(page.id)
        item { SectionHeader("Act") }
        if (hasFailedJobs(response.body)) item {
            Button(onClick = onRerun, enabled = !rerunning && !response.cached, modifier = Modifier.fillMaxWidth()) {
                Text(if (rerunning) "Requesting rerun…" else "Rerun failed jobs")
            }
        }
        item { OutlinedButton(onClick = onHandover, modifier = Modifier.fillMaxWidth()) { Text("Prepare agent handover") } }
        if (!response.cached) runExtras(page.id)
    }
}

@Composable
private fun WorkspaceTextBlock(title: String, text: String, markdown: Boolean = false, onOpen: (() -> Unit)? = null) {
    VardeCard {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.primary)
            if (markdown) WorkspaceMarkdown(text)
            else Text(text, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Default, color = MaterialTheme.colorScheme.onSurface)
            if (onOpen != null) TextButton(onClick = onOpen) { Text("Open on GitHub") }
        }
    }
}

private fun workspaceLinks(page: WorkspacePage, body: String): List<WorkspaceLink> {
    if (page == WorkspacePage.Overview) {
        val details = JSONObject(body)
        return listOf(
            WorkspaceLink("Repository details", "README · default branch ${jsonText(details, "default_branch", "unknown")} · ${details.optInt("open_issues_count")} open issues and PRs", WorkspacePage.Readme.route),
            WorkspaceLink("Commits", "Recent changes", WorkspacePage.Commits.route),
            WorkspaceLink("Issues", "Open issues", WorkspacePage.Issues.route),
            WorkspaceLink("Pull requests", "Review changes and checks", WorkspacePage.Pulls.route),
            WorkspaceLink("Workflows", "Runs, jobs and logs", WorkspacePage.Runs.route),
            WorkspaceLink("Releases", "What's unreleased, and drafting the next release", WorkspacePage.Releases.route),
            WorkspaceLink("Install a build", "Download and install an APK from a release or a workflow run", WorkspacePage.Builds().route),
            WorkspaceLink("Docs", "README, guides and other markdown documents", WorkspacePage.Docs.route),
            WorkspaceLink("Files", "Browse repository files", WorkspacePage.Files("").route),
        )
    }
    val parsed = org.json.JSONTokener(body).nextValue()
    if (page is WorkspacePage.Issue && parsed is JSONObject) return listOf(
        WorkspaceLink(jsonText(parsed, "title"), jsonText(parsed, "body", "No description"), ""),
        WorkspaceLink("Comments", "Read conversation", WorkspacePage.IssueComments(page.number).route),
    )
    if (page is WorkspacePage.Checks && parsed is JSONObject) {
        val checks = parsed.optJSONArray("check_runs") ?: JSONArray()
        return (0 until checks.length()).map { i ->
            val o = checks.getJSONObject(i)
            val target = jsonText(o, "details_url").ifBlank { jsonText(o, "html_url") }
            WorkspaceLink(jsonText(o, "name"), "", actionJobRoute(target) ?: target, ciStatus(jsonText(o, "status"), jsonText(o, "conclusion")))
        }.ifEmpty { listOf(WorkspaceLink("No check runs", "No check runs were returned for this commit.", "")) }
    }
    if (page is WorkspacePage.Statuses && parsed is JSONObject) {
        val statuses = parsed.optJSONArray("statuses") ?: JSONArray()
        return (0 until statuses.length()).map { i -> val o = statuses.getJSONObject(i); WorkspaceLink(jsonText(o, "context"), jsonText(o, "description"), jsonText(o, "target_url"), commitStatus(jsonText(o, "state"))) }
            .ifEmpty { listOf(WorkspaceLink("No commit statuses", "GitHub checks may be available on the pull request page.", "")) }
    }
    val array = when {
        page == WorkspacePage.Runs && parsed is JSONObject -> parsed.optJSONArray("workflow_runs")
        page is WorkspacePage.Run && parsed is JSONObject -> parsed.optJSONArray("jobs")
        parsed is JSONArray -> parsed
        else -> null
    } ?: return emptyList()
    return (0 until array.length()).mapNotNull { i ->
        val o = array.optJSONObject(i) ?: return@mapNotNull null
        when (page) {
            WorkspacePage.Commits -> WorkspaceLink(o.optJSONObject("commit")?.optString("message")?.lineSequence()?.firstOrNull().orEmpty(), jsonText(o, "sha").take(8), jsonText(o, "html_url"))
            WorkspacePage.Issues -> if (o.has("pull_request")) null
                else WorkspaceLink("#${o.optInt("number")} ${jsonText(o, "title")}", "Open issue", WorkspacePage.Issue(o.optInt("number")).route)
            WorkspacePage.Pulls -> WorkspaceLink("#${o.optInt("number")} ${jsonText(o, "title")}", o.optJSONObject("user")?.optString("login").orEmpty(),
                WorkspacePage.Pull(o.optInt("number")).route, pullState(jsonText(o, "state"), o.optBoolean("draft"), false))
            WorkspacePage.Runs -> WorkspaceLink(jsonText(o, "name", "Workflow"), listOf(jsonText(o, "head_branch"), jsonText(o, "event")).filter { it.isNotBlank() }.joinToString(" · "),
                WorkspacePage.Run(o.optLong("id")).route, ciStatus(jsonText(o, "status"), jsonText(o, "conclusion")))
            is WorkspacePage.Run -> WorkspaceLink(jsonText(o, "name", "Job"), "", WorkspacePage.Log(o.optLong("id"), page.id).route,
                ciStatus(jsonText(o, "status"), jsonText(o, "conclusion")))
            is WorkspacePage.Changes -> WorkspaceLink(jsonText(o, "filename"), jsonText(o, "patch", "Patch unavailable"), jsonText(o, "blob_url"))
            is WorkspacePage.Comments, is WorkspacePage.ReviewComments, is WorkspacePage.IssueComments ->
                WorkspaceLink(o.optJSONObject("user")?.optString("login").orEmpty(), jsonText(o, "body"), jsonText(o, "html_url"))
            else -> null
        }
    }
}

package no.vardir.varde

import android.util.Base64
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
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
        page.startsWith("comments:") -> "$base/issues/" + page.removePrefix("comments:") + "/comments?per_page=100"
        page.startsWith("statuses:") -> "$base/commits/" + pathPart(page.removePrefix("statuses:")) + "/status"
        page.startsWith("run:") -> "$base/actions/runs/" + page.removePrefix("run:") + "/jobs?per_page=100"
        page.startsWith("log:") -> "$base/actions/jobs/" + page.removePrefix("log:") + "/logs"
        else -> base
    }
    LaunchedEffect(repo.id, page, generation) {
        loading = true; error = null; payload = null
        try { payload = vm.workspaceLoad(endpoint) }
        catch (e: CancellationException) { throw e }
        catch (e: Exception) { error = e.message ?: "Unable to load this view." }
        finally { loading = false }
    }
    fun back() {
        page = when {
            page == "overview" -> { onBack(); return }
            page.startsWith("changes:") || page.startsWith("comments:") -> "pull:" + page.substringAfter(':')
            page.startsWith("statuses:") -> "pulls"
            page.startsWith("pull:") -> "pulls"
            page.startsWith("log:") -> "runs"
            page.startsWith("run:") -> "runs"
            page.startsWith("files:") && page.removePrefix("files:").contains('/') -> "files:" + page.removePrefix("files:").substringBeforeLast('/')
            else -> "overview"
        }
    }
    androidx.activity.compose.BackHandler { back() }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                TextButton(onClick = { back() }) { Text("‹ Back") }
                TextButton(onClick = { generation++ }, enabled = !loading) { Text("Refresh") }
            }
            Text(repo.name, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
            Text("${repo.owner} / ${page.substringBefore(':').replaceFirstChar { it.uppercase() }}", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (loading) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
        error?.let { message -> item { Text(message, color = MaterialTheme.colorScheme.error) } }
        payload?.let { response ->
            if (response.cached) item { Text("Offline copy · saved ${DateFormat.getDateTimeInstance().format(Date(response.savedAt))}", color = MaterialTheme.colorScheme.primary) }
            if (page.startsWith("log:")) {
                item { Text(response.body, style = MaterialTheme.typography.bodySmall) }
            } else {
                val links = remember(page, response.body) { runCatching { workspaceLinks(page, response.body) }.getOrDefault(emptyList()) }
                if (page == "overview") item {
                    if (repo.description.isNotBlank()) Text(repo.description, style = MaterialTheme.typography.bodyLarge)
                }
                if (links.isEmpty()) item { Text("Nothing to show.", color = MaterialTheme.colorScheme.onSurfaceVariant) }
                items(links) { link ->
                    OutlinedCard(onClick = {
                        if (link.route.startsWith("https://github.com/")) uri.openUri(link.route)
                        else if (link.route.isNotBlank()) page = link.route
                    }, modifier = Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(link.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                            if (link.subtitle.isNotBlank()) Text(link.subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        }
    }
}

private fun workspaceLinks(page: String, body: String): List<WorkspaceLink> {
    if (page == "overview") return listOf(
        WorkspaceLink("Commits", "Recent changes", "commits"), WorkspaceLink("Issues", "Open issues", "issues"),
        WorkspaceLink("Pull requests", "Review changes and checks", "pulls"), WorkspaceLink("Workflows", "Runs, jobs and logs", "runs"),
        WorkspaceLink("Files", "Browse repository files", "files:")
    )
    val parsed = org.json.JSONTokener(body).nextValue()
    if (page.startsWith("pull:") && parsed is JSONObject) {
        val number = page.substringAfter(':')
        val sha = parsed.optJSONObject("head")?.optString("sha").orEmpty()
        return listOf(
            WorkspaceLink(jsonText(parsed, "title"), jsonText(parsed, "body", "No description"), ""),
            WorkspaceLink("Changed files", "View patches", "changes:$number"),
            WorkspaceLink("Comments", "Read conversation", "comments:$number"),
            WorkspaceLink("Commit status", "View reported statuses for the head commit", "statuses:$sha")
        )
    }
    if (page.startsWith("files:") && parsed is JSONObject) {
        val content = jsonText(parsed, "content").replace("\n", "")
        if (content.isBlank()) return listOf(WorkspaceLink(jsonText(parsed, "name", "File"), "This file cannot be displayed here.", jsonText(parsed, "html_url")))
        val decoded = try { String(Base64.decode(content, Base64.DEFAULT), Charsets.UTF_8) } catch (_: Exception) { "Unable to decode file." }
        return listOf(WorkspaceLink(jsonText(parsed, "name"), decoded.take(100_000), jsonText(parsed, "html_url")))
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
            page.startsWith("run:") -> WorkspaceLink(jsonText(o, "name", "Job"), "${jsonText(o, "status")} · ${jsonText(o, "conclusion")}", "log:${o.optLong("id")}")
            page.startsWith("files:") -> WorkspaceLink(jsonText(o, "name"), jsonText(o, "type"), "files:${jsonText(o, "path")}")
            page.startsWith("changes:") -> WorkspaceLink(jsonText(o, "filename"), jsonText(o, "patch", "Patch unavailable"), jsonText(o, "blob_url"))
            page.startsWith("comments:") -> WorkspaceLink(o.optJSONObject("user")?.optString("login").orEmpty(), jsonText(o, "body"), jsonText(o, "html_url"))
            else -> null
        }
    }
}

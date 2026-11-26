package no.vardir.varde

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

internal data class Release(
    val tag: String, val name: String, val draft: Boolean, val prerelease: Boolean, val publishedAt: String, val htmlUrl: String, val body: String,
)

internal fun parseReleases(body: String): List<Release> {
    val array = JSONArray(body)
    return (0 until array.length()).mapNotNull { array.optJSONObject(it) }.map {
        Release(jsonText(it, "tag_name"), jsonText(it, "name"), it.optBoolean("draft"), it.optBoolean("prerelease"),
            jsonText(it, "published_at"), jsonText(it, "html_url"), jsonText(it, "body"))
    }
}

/** The newest published, non-prerelease release; GitHub lists releases newest first. */
internal fun latestRelease(releases: List<Release>): Release? = releases.firstOrNull { !it.draft && !it.prerelease && it.tag.isNotBlank() }

private val SemVer = Regex("""^(v?)(\d+)\.(\d+)\.(\d+)$""")

/** The next patch version after [tag] ("v1.2.3" → "v1.2.4"), or "v0.1.0" when there's none; blank when the tag isn't a version. */
internal fun nextVersion(tag: String?): String {
    if (tag.isNullOrBlank()) return "v0.1.0"
    val m = SemVer.matchEntire(tag) ?: return ""
    val (prefix, major, minor, patch) = m.destructured
    return "$prefix$major.$minor.${patch.toInt() + 1}"
}

/** What the default branch has that the last release doesn't, from GET /compare/{tag}...{branch}. */
internal data class Unreleased(val ahead: Int, val commits: List<String>)

internal fun parseUnreleased(body: String): Unreleased {
    val o = JSONObject(body)
    val commits = o.optJSONArray("commits") ?: JSONArray()
    // Compare lists oldest first; newest first reads better on a phone.
    val messages = (0 until commits.length()).mapNotNull { commits.optJSONObject(it) }.map {
        it.optJSONObject("commit")?.optString("message")?.lineSequence()?.firstOrNull().orEmpty()
    }.reversed()
    return Unreleased(o.optInt("ahead_by"), messages)
}

/** Releases plus whether the default branch is ready for the next one, based only on what GitHub reports. */
@Composable
internal fun ReleasesPage(github: GitHubApi, repo: Repo, body: String, cached: Boolean, onChanged: (String) -> Unit) {
    val uri = LocalUriHandler.current
    val releases = remember(body) { runCatching { parseReleases(body) }.getOrDefault(emptyList()) }
    val latest = latestRelease(releases)
    val branch = repo.defaultBranch.ifBlank { "HEAD" }
    var unreleased by remember(latest?.tag) { mutableStateOf<Unreleased?>(null) }
    var unreleasedError by remember(latest?.tag) { mutableStateOf<String?>(null) }
    var checks by remember { mutableStateOf<CheckSummary?>(null) }
    var drafting by remember { mutableStateOf(false) }
    LaunchedEffect(repo.id, latest?.tag) {
        if (latest != null) try {
            unreleased = parseUnreleased(github.load(repoApi(repo) + "/compare/" + pathPart(latest.tag) + "..." + pathPart(branch) + "?per_page=100").body)
        } catch (e: CancellationException) { throw e } catch (e: Exception) { unreleasedError = e.message ?: "Couldn't compare with ${latest.tag}." }
        checks = try {
            val r = github.load(repoApi(repo) + "/commits/" + pathPart(branch) + "/check-runs?per_page=100")
            parseCheckRuns(r.body, r.cached)
        } catch (e: CancellationException) { throw e } catch (_: Exception) { null }
    }
    if (drafting) DraftReleaseDialog(github, repo, latest, onDismiss = { drafting = false }, onDone = { drafting = false; onChanged(it) })
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        VardeCard {
            Text("Release readiness", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text(latest?.let { "Last release: ${it.tag}" + relativeAge(it.publishedAt, java.time.Instant.now())?.let { a -> " · $a ago" }.orEmpty() }
                ?: "No releases yet.", style = MaterialTheme.typography.bodyMedium)
            when {
                latest == null -> Unit
                unreleasedError != null -> Text(unreleasedError.orEmpty(), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                unreleased == null -> LinearProgressIndicator(Modifier.fillMaxWidth())
                unreleased!!.ahead == 0 -> StatusBadge(StatusLabel(StatusTone.Neutral, "Nothing new since ${latest.tag}"))
                else -> {
                    StatusBadge(StatusLabel(StatusTone.Pending, "${unreleased!!.ahead} unreleased commit" + if (unreleased!!.ahead == 1) "" else "s"))
                    unreleased!!.commits.take(8).forEach { Text("• $it", style = MaterialTheme.typography.bodySmall) }
                    if (unreleased!!.ahead > 8) Text("and ${unreleased!!.ahead - 8} more", style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            val c = checks
            when {
                c == null -> Text("Checks on $branch: unavailable", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                c.failed > 0 -> StatusBadge(StatusLabel(StatusTone.Failure, "${c.failed} of ${c.total} checks failing on $branch"))
                c.pending > 0 -> StatusBadge(StatusLabel(StatusTone.Pending, "${c.pending} checks running on $branch"))
                c.total == 0 -> Text("No checks reported on $branch.", style = MaterialTheme.typography.bodySmall)
                else -> StatusBadge(StatusLabel(StatusTone.Success, "All ${c.total} checks passed on $branch"))
            }
        }
        if (cached) Notice("Actions unavailable offline", "Reconnect and refresh before drafting a release.")
        else Button(onClick = { drafting = true }, modifier = Modifier.fillMaxWidth()) { Text("Draft a release…") }
        if (releases.isEmpty()) Notice("No releases", "This repository has no releases on GitHub yet.")
        releases.forEach { r ->
            NavRow(r.name.ifBlank { r.tag }, listOfNotNull(r.tag.takeIf { r.name.isNotBlank() && it != r.name },
                when { r.draft -> "draft"; r.prerelease -> "pre-release"; else -> null },
                relativeAge(r.publishedAt, java.time.Instant.now())?.let { "$it ago" }).joinToString(" · "),
                onClick = if (r.htmlUrl.startsWith("https://github.com/")) ({ runCatching { uri.openUri(r.htmlUrl) } }) else null)
        }
    }
}

/**
 * Drafts a release on the default branch. Saved as a draft by default, so nothing is announced until it's published
 * (here, after a second confirmation, or on GitHub).
 */
@Composable
private fun DraftReleaseDialog(github: GitHubApi, repo: Repo, latest: Release?, onDismiss: () -> Unit, onDone: (String) -> Unit) {
    val scope = rememberCoroutineScope()
    val branch = repo.defaultBranch.ifBlank { "HEAD" }
    var tag by remember { mutableStateOf(nextVersion(latest?.tag)) }
    var name by remember { mutableStateOf("") }
    var notes by remember { mutableStateOf("") }
    var publish by remember { mutableStateOf(false) }
    var confirming by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var generating by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var unknown by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text(if (confirming) (if (publish) "Publish ${tag}?" else "Save draft ${tag}?") else "Draft a release") },
        text = {
            Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (!confirming) {
                    OutlinedTextField(tag, { tag = it.trim() }, Modifier.fillMaxWidth(), label = { Text("Tag") }, singleLine = true,
                        supportingText = { Text("Created from $branch when the release is published.") })
                    OutlinedTextField(name, { name = it }, Modifier.fillMaxWidth(), label = { Text("Title (optional)") }, singleLine = true)
                    OutlinedTextField(notes, { notes = it }, Modifier.fillMaxWidth(), label = { Text("Release notes") }, minLines = 4)
                    TextButton(enabled = !generating && tag.isNotBlank(), onClick = {
                        generating = true; error = null
                        scope.launch {
                            try {
                                val request = JSONObject().put("tag_name", tag).put("target_commitish", branch)
                                latest?.let { request.put("previous_tag_name", it.tag) }
                                // Generating notes only reads; GitHub doesn't save anything.
                                val result = JSONObject(github.send("POST", repoApi(repo) + "/releases/generate-notes", "Generating notes", request))
                                notes = jsonText(result, "body"); if (name.isBlank()) name = jsonText(result, "name")
                            } catch (e: CancellationException) { throw e }
                            catch (e: Exception) { error = e.message ?: "Couldn't generate notes." }
                            finally { generating = false }
                        }
                    }) { Text(if (generating) "Generating…" else "Fill notes from merged pull requests") }
                    Row(Modifier.fillMaxWidth().toggleable(publish, role = Role.Checkbox) { publish = it }, verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(publish, onCheckedChange = null)
                        Column(Modifier.padding(start = 12.dp)) {
                            Text("Publish now", style = MaterialTheme.typography.bodyMedium)
                            Text("Off saves a draft that only collaborators can see.", style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                } else {
                    Text(if (publish) "GitHub creates tag $tag on $branch, publishes the release and notifies people watching ${repo.owner}/${repo.name}."
                        else "GitHub saves a draft release for $tag. No tag is created and nobody is notified until it's published.")
                    Text(name.ifBlank { tag }, fontWeight = FontWeight.SemiBold)
                    if (notes.isNotBlank()) Text(notes.take(1500) + if (notes.length > 1500) "…" else "", style = MaterialTheme.typography.bodySmall)
                }
                error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            }
        },
        confirmButton = {
            if (!confirming) TextButton(enabled = tag.isNotBlank() && !generating, onClick = { error = null; confirming = true }) { Text("Review") }
            else if (!unknown) TextButton(enabled = !busy, onClick = {
                busy = true; error = null
                scope.launch {
                    try {
                        val created = JSONObject(github.send("POST", repoApi(repo) + "/releases", "Creating the release", JSONObject()
                            .put("tag_name", tag).put("target_commitish", branch).put("name", name.ifBlank { tag }).put("body", notes).put("draft", !publish)))
                        onDone(if (publish) "Published $tag. ${jsonText(created, "html_url")}" else "Saved draft $tag. Publish it on GitHub when it's ready.")
                    } catch (e: CancellationException) { throw e }
                    catch (e: WriteOutcomeUnknown) { unknown = true; error = e.message }
                    catch (e: Exception) { error = e.message ?: "GitHub refused the release." }
                    finally { busy = false }
                }
            }) { Text(if (busy) "Saving…" else if (publish) "Publish" else "Save draft") }
        },
        dismissButton = {
            TextButton(enabled = !busy, onClick = { if (confirming && !unknown) confirming = false else onDismiss() }) {
                Text(if (unknown) "Close and refresh" else if (confirming) "Back" else "Cancel")
            }
        },
    )
}

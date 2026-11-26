package no.vardir.varde

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import org.json.JSONArray
import org.json.JSONObject

/** [problem] explains why the feed is incomplete when there's a specific reason, such as the rate limit. */
internal data class FeedResult(val items: List<AttentionItem>, val incomplete: Boolean, val problem: String? = null)

/** The workflow runs request for a repository's feed: its default branch only, without the PR list each run carries. */
internal fun feedRunsPath(repo: Repo): String = repoApi(repo) + "/actions/runs?per_page=30&exclude_pull_requests=true" +
    if (repo.defaultBranch.isNotBlank()) "&branch=" + pathPart(repo.defaultBranch) else ""

/**
 * The latest run of each workflow on [repo]'s default branch that failed. Runs for pull requests are left to the
 * "checks failing" search, and feature branches are ignored: a red run on an old branch says nothing about the repository,
 * and a green run there must not hide a red default branch.
 */
internal fun failedDefaultBranchRuns(repo: Repo, body: String): List<AttentionItem> {
    val data = JSONObject(body)
    val runs = data.optJSONArray("workflow_runs") ?: JSONArray()
    val seen = mutableSetOf<Long>()
    val items = mutableListOf<AttentionItem>()
    for (i in 0 until runs.length()) {
        val run = runs.getJSONObject(i)
        val event = run.optString("event")
        if (event.startsWith("pull_request")) continue
        if (repo.defaultBranch.isNotBlank() && run.has("head_branch") && run.optString("head_branch") != repo.defaultBranch) continue
        if (!seen.add(run.optLong("workflow_id"))) continue
        if (run.optString("conclusion") == "failure") items.add(AttentionItem(jsonText(run, "name", "Workflow"), "${repo.owner}/${repo.name}",
            AttentionKind.WorkflowFailed, jsonText(run, "html_url"), repo.id, jsonText(run, "updated_at")))
    }
    return items
}

/** Read-only feed; a failed endpoint is reported as incomplete, not as an empty inbox. */
internal suspend fun loadAttentionFeed(repos: List<Repo>, user: String, load: suspend (String) -> String): FeedResult {
    val items = mutableListOf<AttentionItem>()
    // Updated from parallel requests, which may run on several threads in background checks.
    val incompleteFlag = java.util.concurrent.atomic.AtomicBoolean(false)
    val problemRef = java.util.concurrent.atomic.AtomicReference<String?>(null)
    fun failed(e: Exception) {
        if (e is CancellationException || e is Unauthorized) throw e
        incompleteFlag.set(true)
        if (e is RateLimited) problemRef.set(e.message)
    }
    val byName = repos.associateBy { (it.owner + "/" + it.name).lowercase(java.util.Locale.ROOT) }
    suspend fun search(query: String, kind: AttentionKind) {
        if (problemRef.get() != null) { incompleteFlag.set(true); return }
        try {
            val result = JSONObject(load("search/issues?q=" + pathPart(query) + "&per_page=100"))
            if (result.optInt("total_count") > 100 || result.optBoolean("incomplete_results")) incompleteFlag.set(true)
            val a = result.optJSONArray("items") ?: JSONArray()
            for (i in 0 until a.length()) {
                val o = a.getJSONObject(i)
                val ownerAndRepo = jsonText(o, "repository_url").substringAfter("/repos/").lowercase(java.util.Locale.ROOT)
                val repo = byName[ownerAndRepo] ?: continue
                items.add(AttentionItem(jsonText(o, "title"), "${repo.owner}/${repo.name}", kind, jsonText(o, "html_url"), repo.id,
                    jsonText(o, "updated_at")))
            }
        } catch (e: Exception) { failed(e) }
    }
    // Global searches are filtered against the user's included repositories locally.
    search("is:open is:pr review-requested:$user", AttentionKind.ReviewRequested)
    search("is:open is:pr author:$user review:changes_requested", AttentionKind.ChangesRequested)
    search("is:open is:pr author:$user review:approved", AttentionKind.Approved)
    search("is:open mentions:$user -author:$user updated:>=" + java.time.LocalDate.now().minusDays(14), AttentionKind.Mentioned)
    // Search accepts multiple repo qualifiers. Small batches keep URLs and result sets bounded. Search has a low
    // per-minute limit, so these run one at a time.
    for (batch in repos.chunked(8)) {
        val qualifiers = batch.joinToString(" ") { "repo:${it.owner}/${it.name}" }
        search("$qualifiers is:open is:pr status:failure", AttentionKind.ChecksFailing)
    }
    // Workflow runs use the regular API limit and conditional requests, so a few run at once.
    val gate = Semaphore(6)
    val runs = coroutineScope {
        repos.map { repo -> async { gate.withPermit {
            if (problemRef.get() != null) { incompleteFlag.set(true); return@withPermit null }
            try { failedDefaultBranchRuns(repo, load(feedRunsPath(repo))) } catch (e: Exception) { failed(e); null }
        } } }.awaitAll()
    }
    runs.forEach { if (it != null) items += it }
    return FeedResult(items.distinctBy { it.kind to it.url }, incompleteFlag.get(), problemRef.get())
}

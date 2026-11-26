package no.vardir.varde

import kotlinx.coroutines.CancellationException
import org.json.JSONArray
import org.json.JSONObject

internal data class FeedResult(val items: List<AttentionItem>, val incomplete: Boolean)

/** Read-only feed; a failed endpoint is reported as incomplete, not as an empty inbox. */
internal suspend fun loadAttentionFeed(repos: List<Repo>, user: String, load: suspend (String) -> String): FeedResult {
    val items = mutableListOf<AttentionItem>()
    var incomplete = false
    val byName = repos.associateBy { (it.owner + "/" + it.name).lowercase(java.util.Locale.ROOT) }
    suspend fun search(query: String, kind: AttentionKind) {
        try {
            val result = JSONObject(load("search/issues?q=" + pathPart(query) + "&per_page=100"))
            if (result.optInt("total_count") > 100 || result.optBoolean("incomplete_results")) incomplete = true
            val a = result.optJSONArray("items") ?: JSONArray()
            for (i in 0 until a.length()) {
                val o = a.getJSONObject(i)
                val ownerAndRepo = jsonText(o, "repository_url").substringAfter("/repos/").lowercase(java.util.Locale.ROOT)
                val repo = byName[ownerAndRepo] ?: continue
                items.add(AttentionItem(jsonText(o, "title"), "${repo.owner}/${repo.name}", kind, jsonText(o, "html_url"), repo.id,
                    jsonText(o, "updated_at")))
            }
        } catch (e: CancellationException) { throw e } catch (_: Exception) { incomplete = true }
    }
    // Global review search is filtered against the user's included repositories locally.
    search("is:open is:pr review-requested:$user", AttentionKind.ReviewRequested)
    // Search accepts multiple repo qualifiers. Small batches keep URLs and result sets bounded.
    for (batch in repos.chunked(8)) {
        val qualifiers = batch.joinToString(" ") { "repo:${it.owner}/${it.name}" }
        search("$qualifiers is:open is:pr status:failure", AttentionKind.ChecksFailing)
    }
    for (repo in repos) {
        try {
            val data = JSONObject(load(repoApi(repo) + "/actions/runs?per_page=30"))
            if (data.optInt("total_count") > 30) incomplete = true
            val runs = data.optJSONArray("workflow_runs") ?: JSONArray()
            val seen = mutableSetOf<Long>()
            for (i in 0 until runs.length()) {
                val run = runs.getJSONObject(i)
                val id = run.optLong("workflow_id")
                if (!seen.add(id)) continue
                if (run.optString("conclusion") == "failure") {
                    items.add(AttentionItem(jsonText(run, "name", "Workflow"), "${repo.owner}/${repo.name}",
                        AttentionKind.WorkflowFailed, jsonText(run, "html_url"), repo.id, jsonText(run, "updated_at")))
                }
            }
        } catch (e: CancellationException) { throw e } catch (_: Exception) { incomplete = true }
    }
    return FeedResult(items.distinctBy { it.kind to it.url }, incomplete)
}

package no.vardir.varde

import kotlinx.coroutines.CancellationException
import org.json.JSONArray

internal data class ProductEvent(val repositoryId: Long, val repository: String, val title: String,
    val kind: String, val timestamp: String, val route: String)

internal data class ProductEvidence(
    val pullRequests: List<AttentionItem> = emptyList(),
    val activity: List<String> = emptyList(),
    val events: List<ProductEvent> = emptyList(),
    val notes: List<String> = emptyList()
)

/** GitHub lists are bounded; missing or cached responses are always surfaced. */
internal suspend fun loadProductEvidence(
    repos: List<Repo>, load: suspend (String) -> CachedResponse
): ProductEvidence {
    val pulls = mutableListOf<AttentionItem>()
    val activity = mutableListOf<String>()
    val events = mutableListOf<ProductEvent>()
    val notes = mutableListOf<String>()
    if (repos.size > 10) notes.add("Showing GitHub activity for the first 10 repositories.")
    for (repo in repos.take(10)) {
        suspend fun response(path: String): CachedResponse? = try {
            load(path).also { if (it.cached) notes.add("${repo.owner}/${repo.name}: showing cached data.") }
        } catch (e: CancellationException) { throw e }
        catch (_: Exception) { notes.add("${repo.owner}/${repo.name}: some GitHub data could not be loaded."); null }
        val base = repoApi(repo)
        response("$base/pulls?state=open&per_page=30")?.let { result ->
            try {
                val array = JSONArray(result.body)
                if (array.length() == 30) notes.add("${repo.name}: pull requests may be incomplete.")
                for (i in 0 until array.length()) {
                    val item = array.getJSONObject(i)
                    events.add(ProductEvent(repo.id, repo.name, jsonText(item, "title"), "PR",
                        jsonText(item, "updated_at"), "pull:${item.optInt("number")}"))
                    pulls.add(AttentionItem(jsonText(item, "title"), "${repo.owner}/${repo.name}",
                        "Pull request", jsonText(item, "html_url"), repo.id))
                }
            } catch (_: Exception) { notes.add("${repo.name}: pull requests could not be read.") }
        }
        response("$base/issues?state=open&per_page=30")?.let { result ->
            try {
                val array = JSONArray(result.body)
                if (array.length() == 30) notes.add("${repo.name}: issues may be incomplete.")
                for (i in 0 until array.length()) {
                    val item = array.getJSONObject(i)
                    if (!item.has("pull_request")) events.add(ProductEvent(repo.id, repo.name, jsonText(item, "title"),
                        "Issue", jsonText(item, "updated_at"), "issue:${item.optInt("number")}"))
                }
            } catch (_: Exception) { notes.add("${repo.name}: issues could not be read.") }
        }
        response("$base/commits?per_page=3")?.let { result ->
            try {
                val array = JSONArray(result.body)
                for (i in 0 until array.length()) {
                    val item = array.getJSONObject(i)
                    val message = item.optJSONObject("commit")?.optString("message").orEmpty().lineSequence().firstOrNull().orEmpty()
                    activity.add("${repo.name} · ${jsonText(item, "sha").take(7)} · $message")
                    val timestamp = item.optJSONObject("commit")?.optJSONObject("committer")?.optString("date").orEmpty()
                    events.add(ProductEvent(repo.id, repo.name, message, "Commit", timestamp, "commits"))
                }
            } catch (_: Exception) { notes.add("${repo.name}: commits could not be read.") }
        }
    }
    return ProductEvidence(pulls, activity, events.sortedByDescending { it.timestamp }, notes.distinct())
}

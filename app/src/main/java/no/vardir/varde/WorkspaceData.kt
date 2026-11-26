package no.vardir.varde

import org.json.JSONObject
import java.io.IOException
import java.net.URLEncoder

/** A GitHub response. [cached] when it's the saved copy; [truncated] when a list had more items than were loaded. */
internal data class CachedResponse(val body: String, val cached: Boolean, val savedAt: Long, val truncated: Boolean = false)

/** Whether a saved copy should stand in for a failed request: offline, GitHub having trouble, or the rate limit used up. */
internal fun canUseSavedCopy(e: Exception): Boolean = e is IOException || e is RateLimited || (e is HttpFailure && e.code >= 500)

/** Loads screens' data, saving each response per account so it can be shown offline. Logs are never saved. */
internal class WorkspaceData(private val cache: ResponseCache, private val github: GitHub, private val user: String, private val token: String) {
    private fun key(path: String, all: Boolean) = user.lowercase(java.util.Locale.ROOT) + (if (all) ":all:" else ":") + path

    suspend fun load(path: String): CachedResponse {
        if (path.endsWith("/logs")) return CachedResponse(github.jobLog(path, token), false, System.currentTimeMillis())
        return fetch(key(path, false)) { github.get(path, token) to false }
    }

    /** Every page of a list, up to [maxItems]. */
    suspend fun loadAll(path: String, maxItems: Int): CachedResponse =
        fetch(key(path, true)) { github.getAll(path, token, maxItems).let { it.body to it.truncated } }

    private suspend fun fetch(key: String, request: suspend () -> Pair<String, Boolean>): CachedResponse = try {
        val (body, truncated) = request()
        val savedAt = System.currentTimeMillis()
        cache.put(key, body, etag = if (truncated) "truncated" else "", savedAt = savedAt)
        CachedResponse(body, false, savedAt, truncated)
    } catch (e: Exception) {
        if (!canUseSavedCopy(e)) throw e
        val saved = cache.get(key) ?: throw e
        CachedResponse(saved.body, true, saved.savedAt, saved.etag == "truncated")
    }
}

internal fun pathPart(value: String): String = URLEncoder.encode(value, "UTF-8").replace("+", "%20")
internal fun repoApi(repo: Repo): String = "repos/${pathPart(repo.owner)}/${pathPart(repo.name)}"
internal fun jsonText(item: JSONObject, key: String, fallback: String = ""): String =
    if (item.isNull(key)) fallback else item.optString(key, fallback)

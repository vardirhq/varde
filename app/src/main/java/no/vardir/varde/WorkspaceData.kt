package no.vardir.varde

import android.content.Context
import org.json.JSONObject
import java.io.IOException
import java.net.URLEncoder

internal data class CachedResponse(val body: String, val cached: Boolean, val savedAt: Long)

/** API responses are kept in app-private storage, separated by GitHub account. */
internal class WorkspaceData(private val context: Context, private val github: GitHub, private val user: String, private val token: String) {
    private val prefs = context.getSharedPreferences("workspace_cache", Context.MODE_PRIVATE)
    private fun key(path: String) = user.lowercase(java.util.Locale.ROOT) + ":" + path
    suspend fun load(path: String): CachedResponse {
        val key = key(path)
        return try {
            val body = github.get(path, token)
            val savedAt = System.currentTimeMillis()
            prefs.edit().putString(key, body).putLong("$key:time", savedAt).apply()
            CachedResponse(body, false, savedAt)
        } catch (e: IOException) {
            val body = prefs.getString(key, null) ?: throw e
            CachedResponse(body, true, prefs.getLong("$key:time", 0))
        }
    }
}

internal fun pathPart(value: String): String = URLEncoder.encode(value, "UTF-8").replace("+", "%20")
internal fun repoApi(repo: Repo): String = "repos/${pathPart(repo.owner)}/${pathPart(repo.name)}"
internal fun jsonText(item: JSONObject, key: String, fallback: String = ""): String =
    if (item.isNull(key)) fallback else item.optString(key, fallback)

package no.vardir.varde

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

internal class RepoSnapshot(context: Context) {
    private val prefs = context.getSharedPreferences("repo_snapshot", Context.MODE_PRIVATE)
    fun lastUser(): String = prefs.getString("last_user", "").orEmpty()
    fun clearLastUser() { prefs.edit().remove("last_user").apply() }
    fun save(user: String, repos: List<Repo>) {
        val array = JSONArray()
        repos.forEach { r -> array.put(JSONObject().put("id", r.id).put("name", r.name).put("owner", r.owner)
            .put("description", r.description).put("archived", r.archived).put("private", r.privateRepo).put("url", r.url)) }
        prefs.edit().putString("last_user", user).putString(user.lowercase(Locale.ROOT), array.toString())
            .putLong(user.lowercase(Locale.ROOT) + ":time", System.currentTimeMillis()).apply()
    }
    fun load(user: String): Pair<List<Repo>, Long>? {
        return try {
        val key = user.lowercase(Locale.ROOT)
        val raw = prefs.getString(key, null) ?: return null
        val a = JSONArray(raw)
        val repos = (0 until a.length()).map { i ->
            val o = a.getJSONObject(i)
            Repo(o.getLong("id"), o.getString("name"), o.getString("owner"), o.getString("description"),
                o.getBoolean("archived"), o.getBoolean("private"), o.getString("url"))
        }
        repos to prefs.getLong("$key:time", 0)
        } catch (_: Exception) { null }
    }
}

package no.vardir.varde

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import org.json.JSONObject
import java.util.Locale

/** The account's repositories plus local organization: visibility, pins and groups, all in app-private storage. */
internal class RepositoryState(context: Context, private val onVisibilityChanged: () -> Unit) {
    private val exclusionPrefs = context.getSharedPreferences("repository_exclusions", Context.MODE_PRIVATE)
    private val organization = context.getSharedPreferences("project_organization", Context.MODE_PRIVATE)
    private val snapshot = RepoSnapshot(context)
    private var key = ""

    var repos by mutableStateOf(emptyList<Repo>()); private set
    /** When repositories come from the saved copy rather than GitHub, the time it was saved. */
    var snapshotTime by mutableStateOf<Long?>(null); private set
    var excluded by mutableStateOf<Set<Long>>(emptySet()); private set
    var pinned by mutableStateOf<Set<Long>>(emptySet()); private set
    var groups by mutableStateOf<Map<Long, String>>(emptyMap()); private set

    val included: List<Repo> get() = includedRepositories(repos, excluded)
    fun find(id: Long): Repo? = repos.firstOrNull { it.id == id }
    fun lastUser(): String = snapshot.lastUser()

    /** Loads local organization for [user]; with [fromSnapshot], also shows the saved repository list. */
    fun open(user: String, fromSnapshot: Boolean = false) {
        key = user.lowercase(Locale.ROOT)
        excluded = exclusionPrefs.getStringSet(key, emptySet()).orEmpty().mapNotNull { it.toLongOrNull() }.toSet()
        pinned = organization.getStringSet("$key:pins", emptySet()).orEmpty().mapNotNull { it.toLongOrNull() }.toSet()
        val json = try { JSONObject(organization.getString("$key:groups", "{}") ?: "{}") } catch (_: Exception) { JSONObject() }
        groups = json.keys().asSequence().mapNotNull { k -> k.toLongOrNull()?.let { it to json.optString(k) } }.toMap()
        if (fromSnapshot) snapshot.load(user)?.let { (cached, time) -> repos = cached; snapshotTime = time }
    }

    /** Replaces the list with fresh GitHub data and saves it for offline use. */
    fun replace(user: String, fetched: List<Repo>) {
        repos = fetched
        snapshot.save(user, fetched)
        snapshotTime = null
    }

    fun togglePin(id: Long) {
        if (key.isBlank()) return
        pinned = if (id in pinned) pinned - id else pinned + id
        organization.edit().putStringSet("$key:pins", pinned.map { it.toString() }.toSet()).apply()
    }

    fun setGroup(id: Long, group: String) {
        if (key.isBlank()) return
        val name = group.trim().take(80)
        groups = if (name.isEmpty()) groups - id else groups + (id to name)
        val json = JSONObject()
        groups.forEach { (repo, value) -> json.put(repo.toString(), value) }
        organization.edit().putString("$key:groups", json.toString()).apply()
    }

    fun setExcluded(id: Long, exclude: Boolean) {
        if (key.isBlank()) return
        excluded = if (exclude) excluded + id else excluded - id
        exclusionPrefs.edit().putStringSet(key, excluded.map { it.toString() }.toSet()).apply()
        onVisibilityChanged()
    }

    fun clear() {
        snapshot.clearLastUser()
        key = ""; repos = emptyList(); snapshotTime = null
        excluded = emptySet(); pinned = emptySet(); groups = emptyMap()
    }
}

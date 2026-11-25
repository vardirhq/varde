package no.vardir.varde

import android.app.Application
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.*

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { VardeTheme { Varde() } }
    }
}
class Workspace(application: Application) : AndroidViewModel(application) {
    private val store = TokenStore(application)
    private val exclusionPrefs = application.getSharedPreferences("repository_exclusions", android.content.Context.MODE_PRIVATE)
    private val github = GitHub(BuildConfig.GH_CLIENT_ID)
    private val appContext = application.applicationContext
    private val snapshot = RepoSnapshot(appContext)
    private val organization = appContext.getSharedPreferences("project_organization", android.content.Context.MODE_PRIVATE)
    private var token = store.load()
    private var job: Job? = null
    private var feedJob: Job? = null
    private var feedGeneration = 0
    var signedIn by mutableStateOf(token != null); private set
    var snapshotTime by mutableStateOf<Long?>(null); private set
    var user by mutableStateOf(""); private set
    var repos by mutableStateOf(emptyList<Repo>()); private set
    var excludedRepos by mutableStateOf<Set<Long>>(emptySet()); private set
    var pinnedRepos by mutableStateOf<Set<Long>>(emptySet()); private set
    var repoGroups by mutableStateOf<Map<Long, String>>(emptyMap()); private set
    var attention by mutableStateOf(emptyList<AttentionItem>()); private set
    var feedItems by mutableStateOf(emptyList<AttentionItem>()); private set
    var feedLoading by mutableStateOf(false); private set
    var feedIncomplete by mutableStateOf(false); private set
    var attentionLoaded by mutableStateOf(false); private set
    var attentionError by mutableStateOf<String?>(null); private set
    var code by mutableStateOf<String?>(null); private set
    var busy by mutableStateOf(false); private set
    var error by mutableStateOf<String?>(null); private set
    init {
        if (signedIn) {
            val previousUser = snapshot.lastUser()
            if (previousUser.isNotBlank()) {
                user = previousUser
                loadOrganization()
                excludedRepos = exclusionPrefs.getStringSet(user.lowercase(java.util.Locale.ROOT), emptySet())
                    .orEmpty().mapNotNull { it.toLongOrNull() }.toSet()
                snapshot.load(user)?.let { (cached, time) -> repos = cached; snapshotTime = time }
            }
            refresh()
        }
    }
    private fun runTask(block: suspend () -> Unit) {
        if (busy) return
        busy = true; error = null
        job = viewModelScope.launch {
            try { block() }
            catch (e: CancellationException) { throw e }
            catch (_: Unauthorized) { reset(); error = "Session expired. Sign in again." }
            catch (e: Exception) { error = e.message ?: "Request failed." }
            finally { busy = false; code = null }
        }
    }
    fun signIn(privateAccess: Boolean) = runTask {
        val device = github.start(privateAccess)
        code = device.userCode
        val access = github.authorize(device,
            onConnectionProblem = { error = "Can't reach GitHub. Check your connection; retrying until the code expires." },
            onReconnected = { error = null }
        )
        store.save(access); token = access; signedIn = true
        sync(access)
    }
    private suspend fun sync(access: String) {
        user = github.login(access)
        loadOrganization()
        excludedRepos = exclusionPrefs.getStringSet(user.lowercase(java.util.Locale.ROOT), emptySet())
            .orEmpty().mapNotNull { it.toLongOrNull() }.toSet()
        repos = github.repositories(access)
        snapshot.save(user, repos)
        snapshotTime = null
        try {
            attention = github.assignedWork(access)
            attentionLoaded = true
            attentionError = null
        } catch (e: CancellationException) {
            throw e
        } catch (e: Unauthorized) {
            throw e
        } catch (e: Exception) {
            attention = emptyList()
            attentionLoaded = false
            attentionError = "Check your connection and refresh. Assigned work may require additional GitHub permissions."
        }
        startAttentionFeed()
    }
    private fun startAttentionFeed() {
        feedJob?.cancel()
        val generation = ++feedGeneration
        val access = token ?: return
        val account = user
        if (account.isBlank()) return
        val selected = repos.filterNot { it.id in excludedRepos }
        feedLoading = true
        feedItems = emptyList()
        feedIncomplete = false
        feedJob = viewModelScope.launch {
            try {
                val result = loadAttentionFeed(selected, account) { github.get(it, access) }
                feedItems = result.items
                feedIncomplete = result.incomplete
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { feedIncomplete = true }
            finally { if (generation == feedGeneration) feedLoading = false }
        }
    }
    private fun organizationKey(): String = user.lowercase(java.util.Locale.ROOT)
    private fun loadOrganization() {
        if (user.isBlank()) return
        val key = organizationKey()
        pinnedRepos = organization.getStringSet("$key:pins", emptySet()).orEmpty().mapNotNull { it.toLongOrNull() }.toSet()
        val json = try { org.json.JSONObject(organization.getString("$key:groups", "{}") ?: "{}") } catch (_: Exception) { org.json.JSONObject() }
        repoGroups = json.keys().asSequence().mapNotNull { k -> k.toLongOrNull()?.let { it to json.optString(k) } }.toMap()
    }
    fun togglePin(repoId: Long) {
        if (user.isBlank()) return
        pinnedRepos = if (repoId in pinnedRepos) pinnedRepos - repoId else pinnedRepos + repoId
        organization.edit().putStringSet("${organizationKey()}:pins", pinnedRepos.map { it.toString() }.toSet()).apply()
    }
    fun setGroup(repoId: Long, group: String) {
        if (user.isBlank()) return
        val name = group.trim().take(80)
        repoGroups = if (name.isEmpty()) repoGroups - repoId else repoGroups + (repoId to name)
        val json = org.json.JSONObject()
        repoGroups.forEach { (id, value) -> json.put(id.toString(), value) }
        organization.edit().putString("${organizationKey()}:groups", json.toString()).apply()
    }
    fun setExcluded(repoId: Long, excluded: Boolean) {
        if (user.isBlank()) return
        excludedRepos = if (excluded) excludedRepos + repoId else excludedRepos - repoId
        exclusionPrefs.edit().putStringSet(user.lowercase(java.util.Locale.ROOT), excludedRepos.map { it.toString() }.toSet()).apply()
        startAttentionFeed()
    }
    internal suspend fun workspaceLoad(path: String): CachedResponse {
        val access = token ?: throw IllegalStateException("Sign in to load GitHub data.")
        if (user.isBlank()) throw IllegalStateException("Your account is not loaded yet. Refresh and try again.")
        return WorkspaceData(appContext, github, user, access).load(path)
    }
    internal suspend fun rerunFailedJobs(repo: Repo, runId: Long) {
        val access = token ?: throw IllegalStateException("Sign in to rerun jobs.")
        github.rerunFailedJobs(repo, runId, access)
    }
    internal suspend fun submitWrite(repo: Repo, action: WriteAction) {
        val access = token ?: throw IllegalStateException("Sign in before submitting an action.")
        github.submitWrite(repo, action, access)
    }
    fun refresh() = runTask { token?.let { sync(it) } }
    fun cancel() { job?.cancel() }
    private fun reset() { snapshot.clearLastUser(); snapshotTime = null; feedGeneration++; feedJob?.cancel(); feedItems = emptyList(); feedIncomplete = false; feedLoading = false; store.clear(); token = null; signedIn = false; user = ""; repos = emptyList(); excludedRepos = emptySet(); pinnedRepos = emptySet(); repoGroups = emptyMap(); attention = emptyList(); attentionLoaded = false; attentionError = null }
    fun logout() { job?.cancel(); reset(); error = null }
}

package no.vardir.varde

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** Account-wide sync state that tab screens show and can trigger. */
internal interface SyncInfo {
    val busy: Boolean
    val error: String?
    /** Non-null when repositories come from the saved offline copy. */
    val snapshotTime: Long?
    fun refresh()
}

/**
 * Owns the GitHub session and keeps the feature state in step with it: repositories, portfolio and inbox are
 * opened for the signed-in account, refreshed together and cleared on sign-out. Screens receive only the part they use.
 */
class AppViewModel(application: Application) : AndroidViewModel(application), SyncInfo {
    private val store = TokenStore(application)
    private val client = GitHub(BuildConfig.GH_CLIENT_ID)
    private var token = store.load()
    private var job: Job? = null

    var signedIn by mutableStateOf(token != null); private set
    var user by mutableStateOf(""); private set
    /** Device-flow code to enter on GitHub while signing in. */
    var code by mutableStateOf<String?>(null); private set
    override var busy by mutableStateOf(false); private set
    override var error by mutableStateOf<String?>(null); private set

    internal val github: GitHubApi = SessionGitHub(application.applicationContext, client) { token?.let { user to it } }
    internal val repositories = RepositoryState(application.applicationContext, onVisibilityChanged = ::restartFeed)
    internal val portfolio = PortfolioStore(application.applicationContext)
    internal val inbox = AttentionInbox(viewModelScope)
    override val snapshotTime: Long? get() = repositories.snapshotTime

    init {
        if (signedIn) {
            val previous = repositories.lastUser()
            if (previous.isNotBlank()) {
                user = previous
                portfolio.open(previous)
                repositories.open(previous, fromSnapshot = true)
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

    fun signIn(privateAccess: Boolean, publicWrite: Boolean) = runTask {
        val device = client.start(privateAccess, publicWrite)
        code = device.userCode
        val access = client.authorize(device,
            onConnectionProblem = { error = "Can't reach GitHub. Check your connection; retrying until the code expires." },
            onReconnected = { error = null }
        )
        store.save(access); token = access; signedIn = true
        sync(access)
    }

    private suspend fun sync(access: String) {
        user = client.login(access)
        portfolio.open(user)
        repositories.open(user)
        repositories.replace(user, client.repositories(access))
        inbox.loadAssigned { client.assignedWork(access) }
        restartFeed()
    }

    private fun restartFeed() {
        val access = token ?: return
        inbox.startFeed(repositories.included, user) { client.get(it, access) }
    }

    override fun refresh() = runTask { token?.let { sync(it) } }
    fun cancel() { job?.cancel() }

    private fun reset() {
        inbox.clear(); repositories.clear(); portfolio.clear()
        store.clear(); token = null; signedIn = false; user = ""
    }

    fun logout() { job?.cancel(); reset(); error = null }
}
